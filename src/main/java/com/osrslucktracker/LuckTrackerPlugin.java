package com.osrslucktracker;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reports new collection log drops to the OSRS Luck Tracker backend so
 * players can see how spooned or dry they were, compared against the
 * published drop rate and the wider player base.
 *
 * IMPORTANT — this plugin's correctness has NOT been verified against a
 * real compile of the RuneLite client from this environment. The
 * sandbox this was built in has no network access to repo.runelite.net
 * or Maven Central, so `gradlew build` was never run here directly.
 * Everything in this file has, however, now been confirmed working
 * against a real RuneLite test client by the person using it — the
 * account-hash, chat-message, and config APIs below are proven correct
 * in practice, not just in theory.
 */
@Slf4j
@PluginDescriptor(
    name = "Collection Log Luck Tracker",
    description = "Reports how spooned or dry you are for new collection log drops",
    tags = {"collection", "log", "luck", "drop rate"}
)
public class LuckTrackerPlugin extends Plugin
{
    // Matches game messages like "Your Zulrah kill count is: 127."
    private static final Pattern KILL_COUNT_PATTERN =
        Pattern.compile("Your (.+?) kill count is: ([0-9,]+)\\.");

    // Matches the collection log popup message:
    // "New item added to your collection log: Twisted bow"
    private static final Pattern COLLECTION_LOG_PATTERN =
        Pattern.compile("New item added to your collection log: (.+)");

    // How long after a kill-count message we'll still attribute a
    // collection log drop to that kill. In practice both messages fire
    // on the same game tick as the kill, so this is a generous margin,
    // not a tight coupling. Drops from non-boss sources (clues, skilling,
    // minigames that don't print a "kill count" message) will not match
    // any recent kill context and are intentionally skipped — see the
    // KNOWN LIMITATIONS note in the README.
    private static final long KILL_CONTEXT_WINDOW_MS = 5000;

    // Title of the adventure log opened in a POH ("The Exploits of X").
    private static final Pattern ADVENTURE_LOG_TITLE_PATTERN = Pattern.compile("The Exploits of (.+)");

    // Child of the collection log header that holds the page title —
    // same index RuneLite's own Chat Commands plugin reads.
    private static final int COLLECTION_LOG_HEADER_TITLE_INDEX = 0;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ConfigManager configManager;

    @Inject
    private ItemManager itemManager;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private LuckTrackerConfig config;

    @Inject
    private ApiClient apiClient;

    private final Map<String, Integer> bossKillCounts = new HashMap<>();
    private String lastKillSource = null;
    private long lastKillTimestampMs = 0;

    // --- Collection log import state (see readCollectionLogPage) ---
    // Written on the client thread, read by the panel on the EDT.
    private final Map<String, Set<Integer>> obtainedByPage = new ConcurrentHashMap<>();
    private volatile CollectionLogIndex collectionLogIndex;
    private volatile boolean collectionLogIndexFailed;
    private String scannedAccountHash;
    // Set while the player is looking at someone else's adventure log in
    // a POH, whose collection log must never be imported as their own.
    private String adventureLogOwner;
    private boolean adventureLogLoaded;
    // Which account the panel was last told about, so it can reload that
    // account's recorded drops and the collection log layout on login.
    private String announcedAccountHash;
    private volatile String localPlayerName;

    private LuckTrackerPanel panel;
    private NavigationButton navButton;

    @Provides
    LuckTrackerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(LuckTrackerConfig.class);
    }

    @Override
    protected void startUp()
    {
        panel = new LuckTrackerPanel(this, apiClient, itemManager, clientThread);

        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
        navButton = NavigationButton.builder()
            .tooltip("Luck Tracker")
            .icon(icon)
            .priority(6)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);

        ensureRegistered();
    }

    @Override
    protected void shutDown()
    {
        clientToolbar.removeNavigation(navButton);
        bossKillCounts.clear();
        lastKillSource = null;
        obtainedByPage.clear();
        scannedAccountHash = null;
        adventureLogOwner = null;
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        switch (event.getGameState())
        {
            case LOGGED_IN:
                ensureRegistered();
                break;
            case LOADING:
            case HOPPING:
                adventureLogOwner = null;
                break;
            case LOGIN_SCREEN:
                announcedAccountHash = null;
                localPlayerName = null;
                clearCollectionLogScan();
                break;
            default:
                break;
        }
    }

    // getLocalPlayer() can still be null for a tick or two right after
    // GameState flips to LOGGED_IN (a known RuneLite timing gap between
    // the state transition and the local player object actually being
    // populated) — this is exactly what happened in testing: the
    // GameStateChanged-triggered attempt logged ign=null and bailed out
    // without ever calling register(). ensureRegistered() is cheap and
    // idempotent (it no-ops immediately once a token exists or the
    // state/player aren't ready), so retrying every tick until it
    // succeeds is simpler and more robust than trying to catch the
    // exact right moment once.
    @Subscribe
    public void onGameTick(GameTick event)
    {
        ensureRegistered();
        announceAccount();

        // The adventure log's title widget is only populated a tick after
        // it loads — same timing RuneLite's Chat Commands plugin handles.
        if (adventureLogLoaded)
        {
            adventureLogLoaded = false;
            String owner = readAdventureLogOwner();
            if (owner != null)
            {
                adventureLogOwner = owner;
            }
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (event.getGroupId() == InterfaceID.MENU || event.getGroupId() == InterfaceID.MENU_NEW)
        {
            adventureLogLoaded = true;
        }
    }

    /**
     * Fires after the game's own script finishes drawing a collection log
     * page. Nothing here is guessed: the item widgets are what the game
     * just drew, and an obtained slot is drawn at full opacity (0), a
     * missing one faded. RuneLite's built-in Chat Commands plugin reads
     * the "All Pets" page the exact same way, so this hook is kept
     * working by RuneLite itself across game updates.
     *
     * Only pages the player actually opens are read — there is no way to
     * pull the whole log without the player clicking through it.
     */
    @Subscribe
    public void onScriptPostFired(ScriptPostFired event)
    {
        if (event.getScriptId() == ScriptID.COLLECTION_DRAW_LIST)
        {
            readCollectionLogPage();
        }
    }

    private void readCollectionLogPage()
    {
        if (client.getLocalPlayer() == null)
        {
            return;
        }

        String localName = client.getLocalPlayer().getName();
        if (adventureLogOwner != null && !Text.standardize(adventureLogOwner).equals(Text.standardize(localName)))
        {
            log.debug("Ignoring collection log belonging to {}", adventureLogOwner);
            return;
        }

        String accountHash = getCurrentAccountHash();
        if (accountHash == null)
        {
            return;
        }

        CollectionLogIndex index = getCollectionLogIndex();
        if (index == null)
        {
            notifyPanel();
            return;
        }

        Widget header = client.getWidget(InterfaceID.Collection.HEADER_TEXT);
        Widget titleWidget = header == null ? null : header.getChild(COLLECTION_LOG_HEADER_TITLE_INDEX);
        Widget items = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
        if (titleWidget == null || items == null || items.getChildren() == null)
        {
            return;
        }

        // Search results and anything else that isn't a real page won't
        // match a page name from the cache, so they're ignored here.
        String title = Text.removeTags(titleWidget.getText()).trim();
        if (!index.hasPage(title))
        {
            return;
        }

        Set<Integer> expected = index.itemsOn(title);
        Set<Integer> shown = new HashSet<>();
        Set<Integer> obtained = new HashSet<>();
        for (Widget child : items.getChildren())
        {
            if (child == null || child.getItemId() <= 0)
            {
                continue;
            }
            shown.add(child.getItemId());
            if (child.getOpacity() == 0)
            {
                obtained.add(child.getItemId());
            }
        }

        // Cross-check the drawn page against the cache layout. If they
        // disagree, something changed under us — skip the page rather
        // than import from a page we don't understand.
        if (shown.isEmpty() || !expected.containsAll(shown))
        {
            log.warn("Collection log page '{}' doesn't match the cache layout — not importing it", title);
            return;
        }

        if (!accountHash.equals(scannedAccountHash))
        {
            obtainedByPage.clear();
            scannedAccountHash = accountHash;
        }

        Set<Integer> previous = obtainedByPage.put(title, Collections.unmodifiableSet(obtained));
        if (!obtained.equals(previous))
        {
            log.debug("Read collection log page '{}': {} obtained", title, obtained.size());
            notifyPanel();
        }
    }

    /**
     * Once per login, as soon as the local player exists: builds the
     * collection log layout (the dropdown filters on it) and tells the
     * panel to load this account's recorded drops.
     */
    private void announceAccount()
    {
        if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
        {
            return;
        }
        long accountHash = client.getAccountHash();
        String name = client.getLocalPlayer().getName();
        if (accountHash == -1 || name == null)
        {
            return;
        }
        String hash = Long.toHexString(accountHash);
        if (hash.equals(announcedAccountHash))
        {
            return;
        }
        announcedAccountHash = hash;
        localPlayerName = name;
        getCollectionLogIndex();
        notifyPanel();
    }

    /** Built once per session on the client thread; null if the cache layout is unrecognised. */
    private CollectionLogIndex getCollectionLogIndex()
    {
        if (collectionLogIndex == null && !collectionLogIndexFailed)
        {
            collectionLogIndex = CollectionLogIndex.build(client);
            collectionLogIndexFailed = collectionLogIndex == null;
        }
        return collectionLogIndex;
    }

    private String readAdventureLogOwner()
    {
        for (int componentId : new int[]{InterfaceID.MenuNew.TITLE, InterfaceID.Menu.LJ_LAYER2})
        {
            Widget container = client.getWidget(componentId);
            if (container == null)
            {
                continue;
            }
            String owner = matchAdventureLogOwner(container.getText());
            if (owner != null)
            {
                return owner;
            }
            Widget[] children = container.getChildren();
            if (children != null)
            {
                for (Widget child : children)
                {
                    owner = child == null ? null : matchAdventureLogOwner(child.getText());
                    if (owner != null)
                    {
                        return owner;
                    }
                }
            }
        }
        return null;
    }

    private static String matchAdventureLogOwner(String text)
    {
        if (text == null)
        {
            return null;
        }
        Matcher matcher = ADVENTURE_LOG_TITLE_PATTERN.matcher(Text.removeTags(text));
        return matcher.find() ? matcher.group(1) : null;
    }

    private void clearCollectionLogScan()
    {
        obtainedByPage.clear();
        scannedAccountHash = null;
        adventureLogOwner = null;
        notifyPanel();
    }

    private void notifyPanel()
    {
        LuckTrackerPanel p = panel;
        if (p != null)
        {
            SwingUtilities.invokeLater(p::refresh);
        }
    }

    /**
     * Calls /register once per account (result cached in RuneLite's
     * per-profile config), so the plugin has an install_token before it
     * ever needs to submit a drop. Safe to call repeatedly — it's a
     * no-op if a token is already stored for this account.
     */
    private void ensureRegistered()
    {
        clientThread.invokeLater(() ->
        {
            if (client.getGameState() != GameState.LOGGED_IN)
            {
                log.info("[LuckTracker DEBUG] ensureRegistered: not logged in yet (state={})", client.getGameState());
                return;
            }

            long accountHash = client.getAccountHash();
            if (accountHash == -1)
            {
                log.info("[LuckTracker DEBUG] ensureRegistered: getAccountHash() returned -1 despite LOGGED_IN state");
                return;
            }
            String hash = Long.toHexString(accountHash);

            String existingToken = configManager.getConfiguration("lucktracker", hash, "installToken");
            log.info("[LuckTracker DEBUG] ensureRegistered: hash={}, existingToken={}", hash,
                existingToken == null ? "null" : "present (" + existingToken.length() + " chars)");
            if (existingToken != null && !existingToken.isEmpty())
            {
                return;
            }

            String ign = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
            if (ign == null)
            {
                log.info("[LuckTracker DEBUG] ensureRegistered: local player not ready yet, will retry next tick");
                return;
            }
            log.info("[LuckTracker DEBUG] ensureRegistered: ign={}, calling apiClient.register()", ign);

            apiClient.register(hash, ign, token ->
            {
                configManager.setConfiguration("lucktracker", hash, "installToken", token);
                log.info("[LuckTracker DEBUG] Registered successfully, token stored for account {}", hash);
            });
        });
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (event.getType() != ChatMessageType.GAMEMESSAGE)
        {
            return;
        }

        String message = event.getMessage();

        Matcher killMatcher = KILL_COUNT_PATTERN.matcher(message);
        if (killMatcher.find())
        {
            String boss = killMatcher.group(1);
            int kc = Integer.parseInt(killMatcher.group(2).replace(",", ""));
            bossKillCounts.put(boss, kc);
            lastKillSource = boss;
            lastKillTimestampMs = System.currentTimeMillis();
            return;
        }

        Matcher dropMatcher = COLLECTION_LOG_PATTERN.matcher(message);
        if (dropMatcher.find())
        {
            handleCollectionLogDrop(dropMatcher.group(1).trim());
        }
    }

    private void handleCollectionLogDrop(String itemName)
    {
        long now = System.currentTimeMillis();
        if (lastKillSource == null || now - lastKillTimestampMs > KILL_CONTEXT_WINDOW_MS)
        {
            log.debug(
                "Collection log drop '{}' had no recent boss-kill context within {}ms — "
                    + "skipping (likely a non-boss source this plugin doesn't track yet)",
                itemName, KILL_CONTEXT_WINDOW_MS
            );
            return;
        }

        Integer kcAtDrop = bossKillCounts.get(lastKillSource);
        if (kcAtDrop == null)
        {
            return;
        }

        String sourceName = lastKillSource;

        itemManager.search(itemName).stream()
            .findFirst()
            .ifPresentOrElse(
                itemPrice -> submitDrop(itemPrice.getId(), sourceName, kcAtDrop),
                () -> log.warn("Could not resolve item id for '{}' — skipping submission", itemName)
            );
    }

    private void submitDrop(int itemId, String sourceName, int kcReceived)
    {
        long accountHash = client.getAccountHash();
        if (accountHash == -1)
        {
            return;
        }
        String hash = Long.toHexString(accountHash);
        String token = configManager.getConfiguration("lucktracker", hash, "installToken");
        if (token == null || token.isEmpty())
        {
            log.warn("No install token yet — cannot submit drop for item {}. Will register and retry next time.", itemId);
            ensureRegistered();
            return;
        }

        Integer currentKc = bossKillCounts.get(sourceName);
        apiClient.ingestDrop(token, hash, itemId, sourceName, kcReceived, currentKc != null ? currentKc : kcReceived);
    }

    Map<String, Integer> getBossKillCounts()
    {
        return bossKillCounts;
    }

    /**
     * Package-private accessor for the panel's manual backfill flow — the
     * hex-encoded account hash for whoever is currently logged in, or
     * null if nobody is (backfill isn't available from the login screen).
     */
    String getCurrentAccountHash()
    {
        long accountHash = client.getAccountHash();
        String result = accountHash == -1 ? null : Long.toHexString(accountHash);
        log.info("[LuckTracker DEBUG] getCurrentAccountHash: raw={}, result={}", accountHash, result);
        return result;
    }

    /**
     * Package-private accessor for the panel's manual backfill flow — the
     * stored install_token for the current account, or null if
     * registration hasn't completed yet.
     */
    String getInstallToken()
    {
        String hash = getCurrentAccountHash();
        if (hash == null)
        {
            return null;
        }
        String token = configManager.getConfiguration("lucktracker", hash, "installToken");
        log.info("[LuckTracker DEBUG] getInstallToken: hash={}, token={}", hash,
            token == null ? "null" : "present (" + token.length() + " chars)");
        return token;
    }

    /** Snapshot of collection log pages read this session: page title -> obtained item ids. */
    Map<String, Set<Integer>> getObtainedByPage()
    {
        return new HashMap<>(obtainedByPage);
    }

    /** Null until the first collection log page is opened, or if the cache layout is unrecognised. */
    CollectionLogIndex getLoadedCollectionLogIndex()
    {
        return collectionLogIndex;
    }

    /** Name of the logged-in player, or null before the first tick after login. */
    String getLocalPlayerName()
    {
        return localPlayerName;
    }

    boolean isCollectionLogIndexFailed()
    {
        return collectionLogIndexFailed;
    }
}
