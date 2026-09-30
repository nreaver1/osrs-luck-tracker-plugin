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
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ChatIconManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
    // How long after a kill-count message we'll still attribute a
    // collection log drop to that kill. Loot that lands on the floor only
    // enters the log when it's picked up, which can be well after the
    // kill-count message, so this is a generous margin; the server rejects
    // any item with no drop rate from the boss, so a stray non-boss item
    // can't be misattributed. Drops from non-boss sources (clues, skilling,
    // minigames that don't print a "kill count" message) will not match
    // any recent kill context and are intentionally skipped — see the
    // KNOWN LIMITATIONS note in the README.
    private static final long KILL_CONTEXT_WINDOW_MS = 60_000;
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
    // Per-account config key: the visibility settings the backend last
    // stored for this account ("<showProfile>,<showOnLeaderboard>").
    private static final String SYNCED_SETTINGS_KEY = "syncedSettings";
    // Per-account config key, set once the player has synced their log
    // from the panel. From then on log reads and kill counts sync on their own.
    private static final String LOG_SYNCED_KEY = "logSynced";
    // Kill counts from chat are sent in batches at most this often, and at logout.
    private static final long KC_SEND_INTERVAL_MS = 60_000;
    private static final long SETTINGS_RETRY_MS = 60_000;

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
    private ChatMessageManager chatMessageManager;

    @Inject
    private ChatIconManager chatIconManager;

    // Plugin icon shown at the start of the collection log check line.
    // Registered once; RuneLite has no way to unregister a chat icon.
    private static final int CHAT_ICON_SIZE = 13;
    private int chatIconId = -1;

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
    // Per-account config key holding each read page's kill count and item
    // quantities (see LogPageSnapshot), sent with an import as its KC snapshot.
    private static final String LOG_SNAPSHOTS_KEY = "logSnapshots";
    private static final Type LOG_SNAPSHOTS_TYPE = new TypeToken<Map<String, StoredSnapshot>>() {}.getType();

    /** LogPageSnapshot as saved in config. */
    private static final class StoredSnapshot
    {
        int kc;
        Map<Integer, Integer> quantities;
        Integer obtainedShown; // null in saves from before still-hunting rows
    }

    // Lifetime KC as the game prints it, which the API needs for kc_received.
    private final Map<String, Integer> bossKillCounts = new HashMap<>();
    // Kill counts not sent to /update-kc yet, for pendingKcAccount. Client thread only.
    private final Map<String, Integer> pendingKc = new HashMap<>();
    private String pendingKcAccount;
    private long lastKcSendMs;
    // Kills seen since login, for the panel; one kill-count message is one kill.
    private final Map<String, Integer> sessionKillCounts = new HashMap<>();
    // New collection log slots since login, by the source they were credited to.
    private final Map<String, List<String>> sessionLogSlots = new HashMap<>();

    // This account's recorded drops from /get-player-luck, for the line
    // added when an item is checked in the collection log. Fetched on the
    // first check and again after any new drop is recorded. Client thread only.
    private String luckResultsAccount = null;
    private List<PlayerLuckResponse.Result> luckResults = null;
    private boolean luckFetchInFlight = false;
    private String lastKillSource = null;
    private long lastKillTimestampMs = 0;
    private long lastKillWindowMs = KILL_CONTEXT_WINDOW_MS;

    // A player with both the chat and popup notification on gets each
    // drop twice; the second one within DUPLICATE_DROP_MS is ignored.
    private static final long DUPLICATE_DROP_MS = 10_000;
    private String lastDropItem = null;
    private long lastDropTimestampMs = 0;
    // Set by the popup's start script; its delay script runs once the
    // title and body varcs hold the text (same as RuneLite's screenshot plugin).
    private boolean notificationStarted = false;

    // --- Collection log import state (see readCollectionLogPage) ---
    // Written on the client thread, read by the panel on the EDT.
    private final Map<String, Set<Integer>> obtainedByPage = new ConcurrentHashMap<>();
    private final Map<String, LogPageSnapshot> snapshotByPage = new ConcurrentHashMap<>();
    private final Set<String> mismatchWarnedPages = new HashSet<>();
    private final Set<String> noSnapshotLoggedPages = new HashSet<>();
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
    // Account whose /register was refused (409): it was set up from another
    // install and this one has no valid token. Kept for the session so the
    // panel can say drops aren't being recorded, and so register isn't retried.
    private volatile String tokenRejectedAccountHash;
    private long lastRegisterAttemptMs;
    // "<account>:<settings>" the backend is known to hold, so the per-tick
    // settings check is a string compare once they're in sync. Client thread only.
    private String settingsSynced;
    private boolean settingsInFlight;
    private long lastSettingsAttemptMs;

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

        if (chatIconId == -1)
        {
            chatIconId = chatIconManager.registerChatIcon(ImageUtil.resizeImage(icon, CHAT_ICON_SIZE, CHAT_ICON_SIZE));
        }

        ensureRegistered();
    }

    @Override
    protected void shutDown()
    {
        clientToolbar.removeNavigation(navButton);
        bossKillCounts.clear();
        sessionKillCounts.clear();
        sessionLogSlots.clear();
        luckResults = null;
        luckResultsAccount = null;
        lastKillSource = null;
        obtainedByPage.clear();
        snapshotByPage.clear();
        scannedAccountHash = null;
        adventureLogOwner = null;
        registeredAccountHash = null;
        lastRegisterAttemptMs = 0;
        settingsSynced = null;
        lastSettingsAttemptMs = 0;
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
                sendPendingKillCounts();
                announcedAccountHash = null;
                localPlayerName = null;
                registeredAccountHash = null;
                lastRegisterAttemptMs = 0;
                clearCollectionLogScan();
                resetSessionKillCounts();
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
        syncSettings();
        if (!pendingKc.isEmpty() && System.currentTimeMillis() - lastKcSendMs >= KC_SEND_INTERVAL_MS)
        {
            sendPendingKillCounts();
        }

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
    public void onConfigChanged(ConfigChanged event)
    {
        if ("lucktracker".equals(event.getGroup())
            && (LuckTrackerConfig.SHOW_PROFILE_KEY.equals(event.getKey())
                || LuckTrackerConfig.SHOW_ON_LEADERBOARD_KEY.equals(event.getKey())))
        {
            clientThread.invokeLater(() ->
            {
                // A deliberate change goes out now, not after the retry wait.
                lastSettingsAttemptMs = 0;
                syncSettings();
            });
        }
    }

    /**
     * Sends the "show my log" / "show me on the leaderboard" settings to
     * the backend when it doesn't have them yet for this account: after a
     * change, and once for each account that registered before they
     * existed. They're stored per RuneLite profile, so every account
     * played on it shares them. Runs on the client thread each tick;
     * a failed send is retried after SETTINGS_RETRY_MS.
     */
    private void syncSettings()
    {
        String hash = registeredAccountHash;
        if (hash == null || settingsInFlight || !hash.equals(getCurrentAccountHash()))
        {
            return;
        }

        boolean profilePublic = config.showProfile();
        boolean leaderboard = config.showOnLeaderboard();
        String settings = profilePublic + "," + leaderboard;
        String synced = hash + ":" + settings;
        if (synced.equals(settingsSynced))
        {
            return;
        }
        if (settings.equals(configManager.getConfiguration("lucktracker", hash, SYNCED_SETTINGS_KEY)))
        {
            settingsSynced = synced;
            return;
        }

        String token = configManager.getConfiguration("lucktracker", hash, "installToken");
        long now = System.currentTimeMillis();
        if (token == null || token.isEmpty() || now - lastSettingsAttemptMs < SETTINGS_RETRY_MS)
        {
            return;
        }
        lastSettingsAttemptMs = now;
        settingsInFlight = true;

        apiClient.updateSettings(token, hash, profilePublic, leaderboard, ok -> clientThread.invoke(() ->
        {
            settingsInFlight = false;
            if (ok)
            {
                configManager.setConfiguration("lucktracker", hash, SYNCED_SETTINGS_KEY, settings);
                settingsSynced = synced;
                log.info("Luck Tracker visibility saved: profile {}, leaderboard {}",
                    profilePublic ? "shown" : "hidden", leaderboard ? "on" : "off");
            }
        }));
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

    @Subscribe
    public void onScriptPreFired(ScriptPreFired event)
    {
        if (event.getScriptId() == ScriptID.NOTIFICATION_START)
        {
            notificationStarted = true;
            return;
        }
        if (event.getScriptId() != ScriptID.NOTIFICATION_DELAY || !notificationStarted)
        {
            return;
        }
        notificationStarted = false;

        String itemName = CollectionLogMessage.parsePopupItemName(
            client.getVarcStrValue(VarClientID.NOTIFICATION_TITLE),
            client.getVarcStrValue(VarClientID.NOTIFICATION_MAIN));
        if (itemName != null)
        {
            log.info("Collection log drop '{}' seen (popup)", itemName);
            handleCollectionLogDrop(itemName);
        }
    }

    /** The open page's header lines after the title (the obtained count, then any kill counts). */
    private List<String> openLogPageHeaderLines()
    {
        Widget header = client.getWidget(InterfaceID.Collection.HEADER_TEXT);
        Widget[] children = header == null ? null : header.getDynamicChildren();
        List<String> lines = new ArrayList<>();
        if (children != null)
        {
            for (int i = COLLECTION_LOG_HEADER_TITLE_INDEX + 1; i < children.length; i++)
            {
                if (children[i] != null)
                {
                    lines.add(children[i].getText());
                }
            }
        }
        return lines;
    }

    /** The open collection log page's title, or null if the log isn't open. */
    private String openLogPageTitle()
    {
        Widget header = client.getWidget(InterfaceID.Collection.HEADER_TEXT);
        Widget titleWidget = header == null ? null : header.getChild(COLLECTION_LOG_HEADER_TITLE_INDEX);
        return titleWidget == null ? null : Text.removeTags(titleWidget.getText()).trim();
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

        String title = openLogPageTitle();
        Widget items = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
        if (title == null || items == null || items.getChildren() == null)
        {
            return;
        }

        // Search results and anything else that isn't a real page won't
        // match a page name from the cache, so they're ignored here.
        if (!index.hasPage(title))
        {
            return;
        }

        Set<Integer> expected = index.itemsOn(title);
        Set<Integer> shown = new HashSet<>();
        Set<Integer> obtained = new HashSet<>();
        Map<Integer, Integer> quantities = new HashMap<>();
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
                // A stackable slot counts items, not drops (Barrows bolt
                // racks come dozens at a time), so it gets no snapshot.
                if (child.getItemQuantity() > 0 && !itemManager.getItemComposition(itemId).isStackable())
                {
                    quantities.put(itemId, child.getItemQuantity());
                }
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
        boolean changed = false;
        if (!merged.equals(previous))
        {
            obtainedByPage.put(title, Collections.unmodifiableSet(merged));
            log.debug("Read collection log page '{}': {} obtained", title, merged.size());
            saveReadPages(accountHash);
            changed = true;
        }

        List<String> headerLines = openLogPageHeaderLines();
        Integer kc = LogPageSnapshot.parseKillCount(title, headerLines);
        if (kc == null && noSnapshotLoggedPages.add(title))
        {
            log.debug("Collection log page '{}' has no single kill count, so no snapshot (header: {})", title, headerLines);
        }
        if (kc != null)
        {
            LogPageSnapshot prior = snapshotByPage.get(title);
            LogPageSnapshot snapshot = new LogPageSnapshot(kc, quantities,
                LogPageSnapshot.parseObtainedCount(headerLines)).merge(prior);
            if (!snapshot.equals(prior))
            {
                snapshotByPage.put(title, snapshot);
                log.debug("Collection log page '{}' snapshot: {} KC, quantities {}", title, snapshot.kc, snapshot.quantities);
                saveSnapshots(accountHash);
                changed = true;
            }
        }

        if (changed)
        {
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
        loadSnapshots(accountHash);
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

    private void loadSnapshots(String accountHash)
    {
        snapshotByPage.clear();
        String json = configManager.getConfiguration("lucktracker", accountHash, LOG_SNAPSHOTS_KEY);
        if (json == null || json.isEmpty())
        {
            return;
        }
        try
        {
            Map<String, StoredSnapshot> saved = gson.fromJson(json, LOG_SNAPSHOTS_TYPE);
            if (saved != null)
            {
                saved.forEach((page, s) -> snapshotByPage.put(page,
                    new LogPageSnapshot(s.kc, s.quantities == null ? Collections.emptyMap() : s.quantities, s.obtainedShown)));
            }
        }
        catch (JsonSyntaxException e)
        {
            log.warn("Ignoring unreadable saved collection log snapshots", e);
        }
    }

    private void saveSnapshots(String accountHash)
    {
        Map<String, StoredSnapshot> sorted = new TreeMap<>();
        snapshotByPage.forEach((page, snapshot) ->
        {
            StoredSnapshot stored = new StoredSnapshot();
            stored.kc = snapshot.kc;
            stored.quantities = snapshot.quantities;
            stored.obtainedShown = snapshot.obtainedShown;
            sorted.put(page, stored);
        });
        configManager.setConfiguration("lucktracker", accountHash, LOG_SNAPSHOTS_KEY, gson.toJson(sorted));
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
        snapshotByPage.clear();
        mismatchWarnedPages.clear();
        noSnapshotLoggedPages.clear();
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
            if (hash.equals(tokenRejectedAccountHash))
            {
                return;
            }

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
            }, () ->
            {
                tokenRejectedAccountHash = hash;
                notifyPanel();
            });
        });
    }

    private void resetSessionKillCounts()
    {
        if (sessionKillCounts.isEmpty() && sessionLogSlots.isEmpty())
        {
            return;
        }
        sessionKillCounts.clear();
        sessionLogSlots.clear();
        showSessionKillCounts();
    }

    private void showSessionKillCounts()
    {
        LuckTrackerPanel p = panel;
        if (p != null)
        {
            // Snapshot here: the map is only touched on the client thread, the panel reads it on the EDT.
            Map<String, Integer> kills = new TreeMap<>(sessionKillCounts);
            Map<String, List<String>> slots = new HashMap<>();
            sessionLogSlots.forEach((source, items) -> slots.put(source, new ArrayList<>(items)));
            SwingUtilities.invokeLater(() -> p.showBossKillCounts(kills, slots));
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (!CollectionLogMessage.isGameMessageType(event.getType()))
        {
            return;
        }

        String message = event.getMessage();

        KillCountMessage killCount = KillCountMessage.parse(message);
        if (killCount != null)
        {
            bossKillCounts.put(killCount.source, killCount.kc);
            queueKillCount(killCount);
            sessionKillCounts.merge(killCount.source, 1, Integer::sum);
            showSessionKillCounts();
            lastKillSource = killCount.source;
            lastKillTimestampMs = System.currentTimeMillis();
            lastKillWindowMs = killCount.isRaid() ? RAID_CONTEXT_WINDOW_MS : KILL_CONTEXT_WINDOW_MS;
            return;
        }

        String itemName = CollectionLogMessage.parseItemName(message);
        if (itemName != null)
        {
            log.info("Collection log drop '{}' seen ({} message)", itemName, event.getType());
            handleCollectionLogDrop(itemName);
            return;
        }

        String checkedName = LuckCheckMessage.parseCheckedItemName(message);
        if (checkedName != null)
        {
            handleCollectionLogCheck(checkedName);
        }
    }

    /**
     * Adds a line under the game's "You have received 4x Soiled page."
     * when the player checks an item in their own collection log, with the
     * KC and luck the backend recorded for it. Items with no recorded drop
     * get nothing.
     */
    private void handleCollectionLogCheck(String itemName)
    {
        String title = openLogPageTitle();
        CollectionLogIndex index = getCollectionLogIndex();
        String accountHash = getCurrentAccountHash();
        if (title == null || index == null || accountHash == null || localPlayerName == null || !index.hasPage(title))
        {
            return;
        }
        if (adventureLogOwner != null && !Text.standardize(adventureLogOwner).equals(Text.standardize(localPlayerName)))
        {
            return;
        }

        Set<Integer> itemIds = new HashSet<>();
        for (int id : index.itemsOn(title))
        {
            if (client.getItemDefinition(id).getName().equalsIgnoreCase(itemName))
            {
                itemIds.add(id);
            }
        }
        if (itemIds.isEmpty())
        {
            return;
        }

        if (accountHash.equals(luckResultsAccount) && luckResults != null)
        {
            printLuckCheck(itemName, itemIds, title);
            return;
        }
        if (luckFetchInFlight)
        {
            return;
        }

        luckFetchInFlight = true;
        apiClient.fetchPlayerLuck(accountHash, getInstallToken(), localPlayerName, response -> clientThread.invoke(() ->
        {
            luckFetchInFlight = false;
            if (response == null || !accountHash.equals(getCurrentAccountHash()))
            {
                return;
            }
            luckResultsAccount = accountHash;
            luckResults = response.results;
            printLuckCheck(itemName, itemIds, title);
        }));
    }

    /**
     * Prints the recorded drops of the checked item. When an item is
     * recorded from several sources, the ones matching the open page win;
     * the source is named whenever more than one line is printed or the
     * drop came from another page's source.
     */
    private void printLuckCheck(String itemName, Set<Integer> itemIds, String pageTitle)
    {
        List<PlayerLuckResponse.Result> matches = new ArrayList<>();
        for (PlayerLuckResponse.Result result : luckResults)
        {
            if (itemIds.contains(result.itemId))
            {
                matches.add(result);
            }
        }

        String page = BackfillPlanner.normalize(pageTitle);
        List<PlayerLuckResponse.Result> onPage = new ArrayList<>();
        for (PlayerLuckResponse.Result result : matches)
        {
            if (BackfillPlanner.normalize(result.sourceName).startsWith(page))
            {
                onPage.add(result);
            }
        }
        List<PlayerLuckResponse.Result> shown = onPage.isEmpty() ? matches : onPage;
        boolean includeSource = onPage.isEmpty() || shown.size() > 1;

        int iconIndex = chatIconId == -1 ? -1 : chatIconManager.chatIconIndex(chatIconId);
        // Same test RuneLite's ChatMessageManager uses: the box is only see-through in resizable mode.
        LuckCheckMessage.Palette palette = LuckCheckMessage.Palette.forChatbox(
            client.isResized() && client.getVarbitValue(VarbitID.CHATBOX_TRANSPARENCY) == 1);
        for (PlayerLuckResponse.Result result : shown)
        {
            String line = LuckCheckMessage.format(result, itemName, includeSource, iconIndex, palette);
            if (line != null)
            {
                chatMessageManager.queue(QueuedMessage.builder()
                    .type(ChatMessageType.GAMEMESSAGE)
                    .runeLiteFormattedMessage(line)
                    .build());
            }
        }
    }

    /** Called after any drop is recorded, so the next check fetches fresh results. */
    void invalidateLuckResults()
    {
        clientThread.invoke(() ->
        {
            luckResults = null;
        });
    }

    private void handleCollectionLogDrop(String itemName)
    {
        // Skips are logged at info: a drop lost here is lost for good, and
        // debug output is hidden in a normal client.
        long now = System.currentTimeMillis();
        if (itemName.equalsIgnoreCase(lastDropItem) && now - lastDropTimestampMs <= DUPLICATE_DROP_MS)
        {
            log.info("Collection log drop '{}' already handled from the other notification — ignoring", itemName);
            return;
        }
        lastDropItem = itemName;
        lastDropTimestampMs = now;

        if (lastKillSource == null || now - lastKillTimestampMs > lastKillWindowMs)
        {
            log.info(
                "Collection log drop '{}' had no recent boss-kill context (last kill: {}, {} ms ago) — "
                    + "skipping (likely a non-boss source this plugin doesn't track yet)",
                itemName, lastKillSource, lastKillSource == null ? -1 : now - lastKillTimestampMs
            );
            return;
        }

        Integer kcAtDrop = bossKillCounts.get(lastKillSource);
        if (kcAtDrop == null)
        {
            log.info("Collection log drop '{}' skipped: no kill count stored for {}", itemName, lastKillSource);
            return;
        }

        String sourceName = lastKillSource;
        sessionLogSlots.computeIfAbsent(sourceName, k -> new ArrayList<>())
            .add(itemName + " (" + kcAtDrop + " kc)");
        showSessionKillCounts();

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
            log.info("Drop for item {} skipped: no account hash (not logged in?)", itemId);
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
        apiClient.ingestDrop(token, hash, itemId, sourceName, kcReceived, currentKc != null ? currentKc : kcReceived,
            this::invalidateLuckResults);
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

    /** True once this account has synced its log from the panel; from then on syncing is automatic. */
    boolean isLogSynced(String accountHash)
    {
        return accountHash != null
            && "true".equals(configManager.getConfiguration("lucktracker", accountHash, LOG_SYNCED_KEY));
    }

    void markLogSynced(String accountHash)
    {
        if (accountHash != null && !isLogSynced(accountHash))
        {
            configManager.setConfiguration("lucktracker", accountHash, LOG_SYNCED_KEY, "true");
        }
    }

    /**
     * Queues a kill count for /update-kc, which keeps the website's still
     * hunting rows and page reads current between log reads. Only for
     * accounts that have synced their log, since there's nothing to raise
     * otherwise. Client thread.
     */
    private void queueKillCount(KillCountMessage killCount)
    {
        String accountHash = getCurrentAccountHash();
        if (!isLogSynced(accountHash))
        {
            return;
        }
        if (!accountHash.equals(pendingKcAccount))
        {
            sendPendingKillCounts();
            pendingKcAccount = accountHash;
        }
        pendingKc.merge(killCount.source, killCount.kc, Math::max);
    }

    /** Sends the queued kill counts for the account they were seen on. Client thread. */
    private void sendPendingKillCounts()
    {
        if (pendingKc.isEmpty() || pendingKcAccount == null)
        {
            return;
        }
        String token = configManager.getConfiguration("lucktracker", pendingKcAccount, "installToken");
        if (token != null && !token.isEmpty())
        {
            apiClient.updateKillCounts(token, pendingKcAccount, new HashMap<>(pendingKc));
        }
        pendingKc.clear();
        lastKcSendMs = System.currentTimeMillis();
    }

    /** Each read page's kill count and item quantities: page title -> snapshot. */
    Map<String, LogPageSnapshot> getSnapshotByPage()
    {
        return new HashMap<>(snapshotByPage);
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

    /** True when this account was set up from another install, so drops can't be submitted from this one. */
    boolean isTokenRejected()
    {
        String hash = getCurrentAccountHash();
        return hash != null && hash.equals(tokenRejectedAccountHash);
    }

    boolean isCollectionLogIndexFailed()
    {
        return collectionLogIndexFailed;
    }
}
