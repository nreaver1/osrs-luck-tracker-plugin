package com.osrslucktracker;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
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
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
    // Raid uniques arrive when the player loots the reward chest, which
    // can be well after the completion-count message. A later kill-count
    // message replaces the context, and the server rejects any item that
    // has no drop rate from the raid.
    private static final long RAID_CONTEXT_WINDOW_MS = 10 * 60_000;
    // ApiClient doesn't report register failures back, so space out
    // retries instead of sending a new request every tick.
    private static final long REGISTER_RETRY_MS = 60_000;
    // Per-account config key: the IGN the backend last accepted for this
    // account, so a name change can be sent to /register.
    private static final String REGISTERED_IGN_KEY = "registeredIgn";

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

    @Inject
    private Gson gson;

    // Per-account config key holding the log pages read so far (page ->
    // obtained item ids), so an import survives logout and the panel
    // doesn't ask for pages the player already opened.
    private static final String READ_LOG_PAGES_KEY = "readLogPages";
    private static final Type READ_LOG_PAGES_TYPE = new TypeToken<Map<String, Set<Integer>>>() {}.getType();

    private final Map<String, Integer> bossKillCounts = new HashMap<>();
    private String lastKillSource = null;
    private long lastKillTimestampMs = 0;
    private long lastKillWindowMs = KILL_CONTEXT_WINDOW_MS;

    // --- Collection log import state (see readCollectionLogPage) ---
    // Written on the client thread, read by the panel on the EDT.
    private final Map<String, Set<Integer>> obtainedByPage = new ConcurrentHashMap<>();
    private final Set<String> mismatchWarnedPages = new HashSet<>();
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
    // Account known to have an install_token, so the per-tick
    // registration check can stop once it's done.
    private volatile String registeredAccountHash;
    private long lastRegisterAttemptMs;

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
        registeredAccountHash = null;
        lastRegisterAttemptMs = 0;
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
                registeredAccountHash = null;
                lastRegisterAttemptMs = 0;
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
    // without ever calling register(). Retrying on each tick until the
    // account has a token is simpler and more robust than trying to catch
    // the exact right moment once; after that the check stops.
    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (registeredAccountHash == null)
        {
            ensureRegistered();
        }
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
            int itemId = child.getItemId();
            if (!expected.contains(itemId))
            {
                Integer sameName = expectedItemWithSameName(itemId, expected);
                if (sameName != null)
                {
                    itemId = sameName;
                }
            }
            shown.add(itemId);
            if (child.getOpacity() == 0)
            {
                obtained.add(itemId);
            }
        }

        // Cross-check the drawn page against the cache layout. If they
        // disagree, something changed under us — skip the page rather
        // than import from a page we don't understand.
        if (shown.isEmpty() || !expected.containsAll(shown))
        {
            // The page redraws constantly while open, so warn once per page.
            if (mismatchWarnedPages.add(title))
            {
                Set<Integer> unexpected = new HashSet<>(shown);
                unexpected.removeAll(expected);
                log.warn("Collection log page '{}' doesn't match the cache layout — not importing it "
                    + "(shown={}, expected={}, unexpected={})", title, shown, expected, unexpected);
            }
            return;
        }

        if (!accountHash.equals(scannedAccountHash))
        {
            loadReadPages(accountHash);
        }

        // Right after switching log tabs the game can draw a page with its
        // obtained slots still faded (Beginner Treasure Trails read 2, then
        // 0, in one visit). The log only ever gains items, so keep
        // everything seen obtained this session instead of letting a later
        // read drop it.
        Set<Integer> previous = obtainedByPage.get(title);
        Set<Integer> merged = new HashSet<>(obtained);
        if (previous != null)
        {
            merged.addAll(previous);
        }
        if (!merged.equals(previous))
        {
            obtainedByPage.put(title, Collections.unmodifiableSet(merged));
            log.debug("Read collection log page '{}': {} obtained", title, merged.size());
            saveReadPages(accountHash);
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
        String name = playerName();
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
        loadReadPages(hash);
        notifyPanel();
    }

    /** Restores the pages this account read in earlier sessions. */
    private void loadReadPages(String accountHash)
    {
        obtainedByPage.clear();
        scannedAccountHash = accountHash;
        String json = configManager.getConfiguration("lucktracker", accountHash, READ_LOG_PAGES_KEY);
        if (json == null || json.isEmpty())
        {
            return;
        }
        try
        {
            Map<String, Set<Integer>> saved = gson.fromJson(json, READ_LOG_PAGES_TYPE);
            if (saved != null)
            {
                saved.forEach((page, ids) -> obtainedByPage.put(page, Collections.unmodifiableSet(new HashSet<>(ids))));
            }
        }
        catch (JsonSyntaxException e)
        {
            log.warn("Ignoring unreadable saved collection log pages", e);
        }
    }

    private void saveReadPages(String accountHash)
    {
        Map<String, Set<Integer>> sorted = new TreeMap<>();
        obtainedByPage.forEach((page, ids) -> sorted.put(page, new TreeSet<>(ids)));
        configManager.setConfiguration("lucktracker", accountHash, READ_LOG_PAGES_KEY, gson.toJson(sorted));
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

    /**
     * Some log slots draw a display-only copy of an item rather than the
     * item the cache lists for that page (the Abyssal Sire page shows
     * UNSIRED_DUMMY 25624 in place of Unsired 13273). Maps such a copy to
     * the page's item with the same name, or null unless exactly one
     * item on the page matches.
     */
    private Integer expectedItemWithSameName(int itemId, Set<Integer> expected)
    {
        String name = client.getItemDefinition(itemId).getName();
        Integer match = null;
        for (int candidate : expected)
        {
            if (client.getItemDefinition(candidate).getName().equalsIgnoreCase(name))
            {
                if (match != null)
                {
                    return null;
                }
                match = candidate;
            }
        }
        return match;
    }

    private void clearCollectionLogScan()
    {
        obtainedByPage.clear();
        mismatchWarnedPages.clear();
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
     * ever needs to submit a drop. Also calls it again, with the stored
     * token, when the account's IGN has changed since the backend last
     * saw it, so profile lookups by name find the renamed account. Safe
     * to call repeatedly — it's a no-op once the token and IGN are both
     * current, and it sends at most one register request per
     * REGISTER_RETRY_MS.
     */
    private void ensureRegistered()
    {
        clientThread.invokeLater(() ->
        {
            if (client.getGameState() != GameState.LOGGED_IN)
            {
                return;
            }

            long accountHash = client.getAccountHash();
            if (accountHash == -1)
            {
                return;
            }
            String hash = Long.toHexString(accountHash);

            String existingToken = configManager.getConfiguration("lucktracker", hash, "installToken");
            boolean hasToken = existingToken != null && !existingToken.isEmpty();

            String ign = playerName();
            if (ign == null)
            {
                return;
            }

            if (hasToken && ign.equals(configManager.getConfiguration("lucktracker", hash, REGISTERED_IGN_KEY)))
            {
                registeredAccountHash = hash;
                return;
            }

            long now = System.currentTimeMillis();
            if (now - lastRegisterAttemptMs < REGISTER_RETRY_MS)
            {
                return;
            }
            lastRegisterAttemptMs = now;
            log.debug("Registering {} with the Luck Tracker backend", ign);

            apiClient.register(hash, ign, hasToken ? existingToken : null, token ->
            {
                configManager.setConfiguration("lucktracker", hash, "installToken", token);
                configManager.setConfiguration("lucktracker", hash, REGISTERED_IGN_KEY, ign);
                registeredAccountHash = hash;
                log.info("Registered {} with the Luck Tracker backend", ign);
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

        KillCountMessage killCount = KillCountMessage.parse(message);
        if (killCount != null)
        {
            bossKillCounts.put(killCount.source, killCount.kc);
            lastKillSource = killCount.source;
            lastKillTimestampMs = System.currentTimeMillis();
            lastKillWindowMs = killCount.isRaid() ? RAID_CONTEXT_WINDOW_MS : KILL_CONTEXT_WINDOW_MS;
            return;
        }

        Matcher dropMatcher = COLLECTION_LOG_PATTERN.matcher(Text.removeTags(message));
        if (dropMatcher.find())
        {
            handleCollectionLogDrop(dropMatcher.group(1).trim());
        }
    }

    private void handleCollectionLogDrop(String itemName)
    {
        long now = System.currentTimeMillis();
        if (lastKillSource == null || now - lastKillTimestampMs > lastKillWindowMs)
        {
            log.debug(
                "Collection log drop '{}' had no recent boss-kill context — "
                    + "skipping (likely a non-boss source this plugin doesn't track yet)",
                itemName
            );
            return;
        }

        Integer kcAtDrop = bossKillCounts.get(lastKillSource);
        if (kcAtDrop == null)
        {
            return;
        }

        String sourceName = lastKillSource;

        Integer itemId = resolveItemId(itemName, sourceName);
        if (itemId == null)
        {
            log.warn("Could not resolve item id for '{}' — skipping submission", itemName);
            return;
        }
        submitDrop(itemId, sourceName, kcAtDrop);
    }

    /**
     * Looks the name up among the collection log's own items first, since
     * itemManager.search() only knows tradeable items and would miss pets
     * and untradeables like the thread of Elidinis. When several log items
     * share the name, the one on the source's log page wins.
     */
    private Integer resolveItemId(String itemName, String sourceName)
    {
        CollectionLogIndex index = getCollectionLogIndex();
        if (index != null)
        {
            Set<Integer> matches = new TreeSet<>();
            for (int id : index.allItemIds())
            {
                if (client.getItemDefinition(id).getName().equalsIgnoreCase(itemName))
                {
                    matches.add(id);
                }
            }
            if (matches.size() == 1)
            {
                return matches.iterator().next();
            }
            // Mode variants share one page: "Tombs of Amascut: Expert Mode"
            // drops are listed on "Tombs of Amascut".
            String source = BackfillPlanner.normalize(sourceName);
            Set<Integer> onSourcePage = new TreeSet<>();
            for (int id : matches)
            {
                if (index.pagesFor(id).stream().anyMatch(page -> source.startsWith(BackfillPlanner.normalize(page))))
                {
                    onSourcePage.add(id);
                }
            }
            if (onSourcePage.size() == 1)
            {
                return onSourcePage.iterator().next();
            }
        }

        return itemManager.search(itemName).stream()
            .findFirst()
            .map(itemPrice -> itemPrice.getId())
            .orElse(null);
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
        return accountHash == -1 ? null : Long.toHexString(accountHash);
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
        return configManager.getConfiguration("lucktracker", hash, "installToken");
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

    /**
     * The local player's name in the form the backend stores: the game can
     * report spaces as non-breaking spaces, and OSRS treats space, "_" and
     * "-" in a name as the same character, so all of them become a space
     * (Text.toJagexName). Null when there is no local player yet.
     */
    private String playerName()
    {
        String raw = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        if (raw == null)
        {
            return null;
        }
        String name = Text.toJagexName(raw);
        return name.isEmpty() ? null : name;
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
