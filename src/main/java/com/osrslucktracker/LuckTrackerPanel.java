package com.osrslucktracker;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

class LuckTrackerPanel extends PluginPanel
{
    // Keeps each request well under the backend's per-request cap.
    private static final int IMPORT_BATCH_SIZE = 200;
    private static final int MAX_LISTED_PAGES = 12;
    private static final String WEBSITE_URL = "https://osrs-luck-tracker.vercel.app";
    static final String SUPPORT_URL = "https://github.com/nreaver1/osrs-luck-tracker-plugin/issues";
    private static final String NO_BOSS_KILLS_TEXT = "No boss kills tracked yet this session.";

    private final LuckTrackerPlugin plugin;
    private final ApiClient apiClient;
    private final ItemManager itemManager;
    private final ClientThread clientThread;

    private final JLabel statusLabel;
    private final JLabel tokenRejectedLabel;
    private final JLabel bossKcLabel;
    private final JLabel importSummary;
    private final JLabel importPages;
    private final JButton importButton;
    private final JComboBox<CatalogChoice> backfillDropdown;
    private final JButton backfillButton;

    // EDT-only state
    private List<CatalogEntry> catalog;
    private List<CatalogChoice> allChoices;
    // account hash -> keys the database already has a row for (from /get-player-luck)
    private final Map<String, Set<String>> recordedByAccount = new HashMap<>();
    // account hash -> recorded backfilled keys with no KC snapshot yet
    private final Map<String, Set<String>> needsSnapshotByAccount = new HashMap<>();
    // account hash -> still-hunting key -> kill count the backend holds
    private final Map<String, Map<String, Integer>> huntingByAccount = new HashMap<>();
    // account hash -> catalog source -> page read the backend holds
    private final Map<String, Map<String, SyncHuntingRequest.Page>> pagesByAccount = new HashMap<>();
    private String recordedFetchInFlight;
    private final Map<String, String> itemNames = new HashMap<>();
    // account hash -> Candidate keys already submitted this session
    private final Map<String, Set<String>> sentByAccount = new HashMap<>();
    private List<BackfillPlanner.Candidate> readyToImport = Collections.emptyList();
    private List<BackfillPlanner.Candidate> snapshotUpdates = Collections.emptyList();
    private BackfillPlanner.HuntingPlan huntingPlan = new BackfillPlanner.HuntingPlan();
    private boolean importInFlight;
    // Snapshot updates in the import in flight, which the backend counts as already recorded.
    private int importSnapshotUpdates;
    // Still-hunting changes sent once the import's batches are done.
    private BackfillPlanner.HuntingPlan importHunting = new BackfillPlanner.HuntingPlan();

    LuckTrackerPanel(LuckTrackerPlugin plugin, ApiClient apiClient, ItemManager itemManager, ClientThread clientThread)
    {
        super();
        this.plugin = plugin;
        this.apiClient = apiClient;
        this.itemManager = itemManager;
        this.clientThread = clientThread;

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Collection Log Luck Tracker");
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(title);

        content.add(Box.createVerticalStrut(6));

        JLabel description = new JLabel(
            "<html><div style='text-align:center'>Records your boss collection log drops and shows "
                + "how spooned or dry you were for each one.</div></html>"
        );
        description.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(description);

        content.add(Box.createVerticalStrut(4));

        JLabel websiteLink = new JLabel("<html><u>View your luck on the website</u></html>");
        websiteLink.setForeground(ColorScheme.BRAND_ORANGE);
        websiteLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        websiteLink.setToolTipText(WEBSITE_URL);
        websiteLink.setAlignmentX(Component.CENTER_ALIGNMENT);
        websiteLink.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                LinkBrowser.browse(WEBSITE_URL);
            }
        });
        content.add(websiteLink);

        content.add(Box.createVerticalStrut(8));

        statusLabel = new JLabel("Tracking new drops automatically.");
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(statusLabel);

        // Shown when /register refused this install; see LuckTrackerPlugin.isTokenRejected().
        tokenRejectedLabel = new JLabel(
            "<html><div style='text-align:center'><b>Drops aren't being recorded.</b> This account was set up "
                + "from another RuneLite install (another PC, or before a reinstall). Log in to the same RuneLite "
                + "account here so your settings sync, then restart RuneLite. Still stuck? <u>Get support</u>.</div></html>"
        );
        tokenRejectedLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
        tokenRejectedLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        tokenRejectedLabel.setToolTipText(SUPPORT_URL);
        tokenRejectedLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        tokenRejectedLabel.setVisible(false);
        tokenRejectedLabel.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                LinkBrowser.browse(SUPPORT_URL);
            }
        });
        content.add(tokenRejectedLabel);

        content.add(Box.createVerticalStrut(8));

        bossKcLabel = new JLabel(NO_BOSS_KILLS_TEXT);
        bossKcLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(bossKcLabel);

        content.add(Box.createVerticalStrut(12));

        // Backlogging is a one-time step, so it stays collapsed by default.
        JPanel backlog = new JPanel();
        backlog.setLayout(new BoxLayout(backlog, BoxLayout.Y_AXIS));
        backlog.setAlignmentX(Component.CENTER_ALIGNMENT);
        backlog.setVisible(false);

        JCheckBox backlogToggle = new JCheckBox("Backlog existing collection log items");
        backlogToggle.setAlignmentX(Component.CENTER_ALIGNMENT);
        backlogToggle.addItemListener(e -> backlog.setVisible(backlogToggle.isSelected()));
        content.add(backlogToggle);

        backlog.add(Box.createVerticalStrut(8));

        // --- Import from the in-game collection log ---

        JLabel importTitle = new JLabel("Import from collection log");
        importTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        backlog.add(importTitle);

        JLabel importHelp = new JLabel(
            "<html><i>A one-time step to add items you got before installing the plugin. "
                + "1. Open your collection log in-game and click through the pages listed below; "
                + "the plugin reads each page as it opens. 2. Click Import items. "
                + "New drops are tracked automatically, so you won't need to do this again.</i></html>"
        );
        importHelp.setAlignmentX(Component.CENTER_ALIGNMENT);
        backlog.add(importHelp);

        backlog.add(Box.createVerticalStrut(6));

        importSummary = new JLabel("Loading items...");
        importSummary.setAlignmentX(Component.CENTER_ALIGNMENT);
        backlog.add(importSummary);

        importPages = new JLabel();
        importPages.setAlignmentX(Component.CENTER_ALIGNMENT);
        backlog.add(importPages);

        backlog.add(Box.createVerticalStrut(6));

        importButton = new JButton("Import items");
        importButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        importButton.setEnabled(false);
        importButton.addActionListener(e -> onImportClicked());
        backlog.add(importButton);

        backlog.add(Box.createVerticalStrut(16));

        // --- Manual backfill, one item at a time ---

        JLabel backfillTitle = new JLabel("Add a single item");
        backfillTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        backlog.add(backfillTitle);

        JLabel backfillNote = new JLabel(
            "<html><i>Collection log items you haven't recorded yet &mdash; useful for shared items "
                + "the import can't attribute to one source. Marks it as obtained with unknown luck; "
                + "this can't be undone or matched to a KC.</i></html>"
        );
        backfillNote.setAlignmentX(Component.CENTER_ALIGNMENT);
        backlog.add(backfillNote);

        backlog.add(Box.createVerticalStrut(6));

        backfillDropdown = new JComboBox<>();
        backfillDropdown.setAlignmentX(Component.CENTER_ALIGNMENT);
        backfillDropdown.addItem(new CatalogChoice(-1, "Loading items...", ""));
        backlog.add(backfillDropdown);

        backlog.add(Box.createVerticalStrut(6));

        backfillButton = new JButton("Mark as already obtained");
        backfillButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        backfillButton.setEnabled(false);
        backfillButton.addActionListener(e -> onBackfillClicked());
        backlog.add(backfillButton);

        content.add(backlog);

        add(content, BorderLayout.NORTH);

        loadCatalog();
    }

    private void loadCatalog()
    {
        apiClient.fetchCatalog(entries ->
            // ItemManager.getItemComposition() (called inside buildChoices,
            // via resolveItemName) asserts it's running on the client
            // thread — it will throw otherwise. The OkHttp callback that
            // calls this lambda runs on OkHttp's own dispatcher thread, not
            // the client thread and not the Swing EDT, so both hops below
            // are required, not optional: first hop onto the client thread
            // to safely resolve item names, then a second hop onto the EDT
            // to safely touch Swing components (they may only be read or
            // written from the EDT).
            clientThread.invoke(() ->
            {
                List<CatalogChoice> choices = buildChoices(entries);
                SwingUtilities.invokeLater(() ->
                {
                    catalog = entries;
                    for (CatalogChoice choice : choices)
                    {
                        itemNames.put(new BackfillPlanner.Candidate(choice.itemId, choice.sourceName).key(), choice.itemName);
                    }
                    allChoices = choices;
                    refresh();
                });
            })
        );
    }

    /** Runs on the client thread — safe to call itemManager here. */
    private List<CatalogChoice> buildChoices(List<CatalogEntry> entries)
    {
        List<CatalogChoice> choices = new ArrayList<>();
        for (CatalogEntry entry : entries)
        {
            choices.add(new CatalogChoice(entry.itemId, resolveItemName(entry.itemId), entry.sourceName));
        }
        return choices;
    }

    /** Runs on the client thread — safe to call itemManager here. */
    private String resolveItemName(int itemId)
    {
        try
        {
            return itemManager.getItemComposition(itemId).getName();
        }
        catch (Exception e)
        {
            return "Item #" + itemId;
        }
    }

    /**
     * Runs on the EDT; the plugin calls it after each kill-count message,
     * each new collection log slot and on logout. Slots are listed under
     * the boss they were credited to.
     */
    void showBossKillCounts(Map<String, Integer> counts, Map<String, List<String>> logSlots)
    {
        if (counts.isEmpty())
        {
            bossKcLabel.setText(NO_BOSS_KILLS_TEXT);
            return;
        }
        StringBuilder text = new StringBuilder("<html>Boss KCs this session:");
        for (Map.Entry<String, Integer> entry : counts.entrySet())
        {
            text.append("<br>&bull; ").append(entry.getKey()).append(": ").append(entry.getValue());
            for (String slot : logSlots.getOrDefault(entry.getKey(), Collections.emptyList()))
            {
                text.append("<br>&nbsp;&nbsp;&nbsp;&ndash; ").append(slot);
            }
        }
        bossKcLabel.setText(text.append("</html>").toString());
    }

    /**
     * Brings the whole panel up to date. Runs on the EDT; the plugin
     * calls it on login and whenever a collection log page is read.
     */
    void refresh()
    {
        tokenRejectedLabel.setVisible(plugin.isTokenRejected());
        fetchRecordedDropsIfNeeded();
        refreshCollectionLogImport();
        rebuildDropdown();
    }

    /** Loads which drops the database already has for this account, once per account per session. */
    private void fetchRecordedDropsIfNeeded()
    {
        String accountHash = plugin.getCurrentAccountHash();
        String ign = plugin.getLocalPlayerName();
        if (accountHash == null || ign == null
            || recordedByAccount.containsKey(accountHash) || accountHash.equals(recordedFetchInFlight))
        {
            return;
        }

        recordedFetchInFlight = accountHash;
        apiClient.fetchPlayerLuck(accountHash, plugin.getInstallToken(), ign, response ->
            SwingUtilities.invokeLater(() ->
            {
                recordedFetchInFlight = null;
                if (response != null)
                {
                    List<PlayerLuckResponse.Result> results = response.results;
                    Map<String, Integer> hunting = new HashMap<>();
                    for (PlayerLuckResponse.Hunting h : response.hunting)
                    {
                        hunting.put(new BackfillPlanner.Candidate(h.itemId, h.sourceName).key(), h.kc);
                    }
                    huntingByAccount.put(accountHash, hunting);
                    Map<String, SyncHuntingRequest.Page> pages = new HashMap<>();
                    for (PlayerLuckResponse.LogPage p : response.logPages)
                    {
                        pages.put(p.sourceName, new SyncHuntingRequest.Page(p.sourceName, p.kc, p.obtained, p.quantities));
                    }
                    pagesByAccount.put(accountHash, pages);
                    Set<String> keys = new HashSet<>();
                    Set<String> tracked = new HashSet<>();
                    Set<String> needsSnapshot = new HashSet<>();
                    for (PlayerLuckResponse.Result result : results)
                    {
                        String key = new BackfillPlanner.Candidate(result.itemId, result.sourceName).key();
                        keys.add(key);
                        if (!result.backfilled)
                        {
                            tracked.add(key);
                        }
                        else if (result.snapshot == null)
                        {
                            needsSnapshot.add(key);
                        }
                    }
                    // backfill-drop won't add a snapshot to a pair tracking has a drop for.
                    needsSnapshot.removeAll(tracked);
                    recordedByAccount.put(accountHash, keys);
                    needsSnapshotByAccount.put(accountHash, needsSnapshot);
                    refresh();
                }
            })
        );
    }

    /** Everything this account has in the database or submitted this session. */
    private Set<String> recordedKeys(String accountHash)
    {
        Set<String> keys = new HashSet<>(recordedByAccount.getOrDefault(accountHash, Collections.emptySet()));
        keys.addAll(sentByAccount.getOrDefault(accountHash, Collections.emptySet()));
        return keys;
    }

    /**
     * The dropdown lists only collection log items (on the page for their
     * source) that this account hasn't recorded yet. If the log layout
     * couldn't be read from the cache, it falls back to the full catalog
     * minus recorded items rather than showing nothing.
     */
    private void rebuildDropdown()
    {
        if (allChoices == null)
        {
            return;
        }

        String accountHash = plugin.getCurrentAccountHash();
        CollectionLogIndex index = plugin.getLoadedCollectionLogIndex();
        if (accountHash == null || (index == null && !plugin.isCollectionLogIndexFailed()))
        {
            setDropdownPlaceholder("Log in to see items");
            return;
        }

        Set<String> recorded = recordedKeys(accountHash);
        List<CatalogChoice> visible = new ArrayList<>();
        for (CatalogChoice choice : allChoices)
        {
            if (recorded.contains(new BackfillPlanner.Candidate(choice.itemId, choice.sourceName).key()))
            {
                continue;
            }
            if (index != null && !BackfillPlanner.isLogItemForSource(choice.itemId, choice.sourceName, index))
            {
                continue;
            }
            visible.add(choice);
        }
        visible.sort(Comparator.comparing((CatalogChoice c) -> c.sourceName).thenComparing(c -> c.itemName));

        if (visible.isEmpty())
        {
            setDropdownPlaceholder("Nothing left to add");
            return;
        }

        Object selected = backfillDropdown.getSelectedItem();
        backfillDropdown.removeAllItems();
        for (CatalogChoice choice : visible)
        {
            backfillDropdown.addItem(choice);
        }
        if (selected instanceof CatalogChoice && visible.contains(selected))
        {
            backfillDropdown.setSelectedItem(selected);
        }
        backfillButton.setEnabled(true);
    }

    private void setDropdownPlaceholder(String text)
    {
        backfillDropdown.removeAllItems();
        backfillDropdown.addItem(new CatalogChoice(-1, text, ""));
        backfillButton.setEnabled(false);
    }

    /**
     * Recomputes the import list from the collection log pages read so
     * far. Runs on the EDT; the plugin calls it whenever a page is read.
     */
    void refreshCollectionLogImport()
    {
        readyToImport = Collections.emptyList();
        snapshotUpdates = Collections.emptyList();
        huntingPlan = new BackfillPlanner.HuntingPlan();
        importPages.setText("");
        importButton.setText("Import items");
        importButton.setEnabled(false);

        if (catalog == null)
        {
            importSummary.setText("Loading items...");
            return;
        }
        if (plugin.isCollectionLogIndexFailed())
        {
            importSummary.setText("<html>Couldn't read the collection log layout from the game &mdash; "
                + "a game update may have changed it. Use \"Add a single item\" below for now.</html>");
            return;
        }
        CollectionLogIndex index = plugin.getLoadedCollectionLogIndex();
        String accountHash = plugin.getCurrentAccountHash();
        if (index == null || accountHash == null)
        {
            importSummary.setText("Open your collection log to start.");
            return;
        }

        BackfillPlanner.Plan plan = BackfillPlanner.plan(
            catalog,
            plugin.getObtainedByPage(),
            index,
            recordedKeys(accountHash),
            plugin.getSnapshotByPage(),
            needsSnapshotByAccount.getOrDefault(accountHash, Collections.emptySet())
        );
        readyToImport = plan.ready;
        snapshotUpdates = plan.snapshotUpdates;
        // Needs the backend's list first, to know what's new and what to clear.
        Map<String, Integer> serverHunting = huntingByAccount.get(accountHash);
        if (serverHunting != null)
        {
            huntingPlan = BackfillPlanner.planHunting(catalog, plugin.getObtainedByPage(), index,
                plugin.getSnapshotByPage(), serverHunting,
                pagesByAccount.getOrDefault(accountHash, Collections.emptyMap()));
        }

        StringBuilder summary = new StringBuilder("<html>");
        summary.append(plan.ready.size()).append(plan.ready.size() == 1 ? " item" : " items")
            .append(" ready to import.");
        if (!plan.snapshotUpdates.isEmpty())
        {
            summary.append("<br>").append(plan.snapshotUpdates.size())
                .append(plan.snapshotUpdates.size() == 1 ? " imported item" : " imported items")
                .append(" can get a luck estimate from the kill count on its log page.");
        }
        int huntingChanges = huntingPlan.toSync.size() + huntingPlan.obtained.size();
        if (huntingChanges > 0)
        {
            summary.append("<br>").append(huntingChanges)
                .append(huntingChanges == 1 ? " still-hunting item" : " still-hunting items")
                .append(" to update on the website.");
        }
        if (!huntingPlan.pages.isEmpty())
        {
            summary.append("<br>").append(huntingPlan.pages.size())
                .append(huntingPlan.pages.size() == 1 ? " log page" : " log pages")
                .append(" to update on the website.");
        }
        if (plan.pagesRead > 0)
        {
            summary.append("<br><i>Read ").append(plan.pagesRead).append(plan.pagesRead == 1 ? " page" : " pages");
            if (!plan.obtainedWithoutRate.isEmpty())
            {
                summary.append("; ").append(plan.obtainedWithoutRate.size())
                    .append(" obtained item(s) there have no drop rate yet, so they can't be imported");
            }
            summary.append(".</i>");
        }
        for (BackfillPlanner.Candidate c : plan.ready)
        {
            summary.append("<br>&bull; ").append(displayName(c));
        }
        if (!plan.shared.isEmpty())
        {
            summary.append("<br><br>").append(plan.shared.size())
                .append(" shared item(s) skipped &mdash; several bosses drop them and the log fills every "
                    + "boss's page, so it doesn't prove which one dropped yours. Add them manually below:");
            for (BackfillPlanner.Candidate c : plan.shared)
            {
                summary.append("<br>&bull; ").append(displayName(c));
            }
        }
        importSummary.setText(summary.append("</html>").toString());

        if (!plan.pagesToOpen.isEmpty())
        {
            StringBuilder pages = new StringBuilder("<html><br>Pages still to open:");
            int listed = 0;
            for (String page : plan.pagesToOpen)
            {
                if (listed++ == MAX_LISTED_PAGES)
                {
                    pages.append("<br>&hellip;and ").append(plan.pagesToOpen.size() - MAX_LISTED_PAGES).append(" more");
                    break;
                }
                pages.append("<br>&bull; ").append(page);
            }
            importPages.setText(pages.append("</html>").toString());
        }

        if (!importInFlight && !plan.ready.isEmpty())
        {
            importButton.setText("Import " + plan.ready.size() + (plan.ready.size() == 1 ? " item" : " items"));
            importButton.setEnabled(true);
        }
        else if (!importInFlight && !plan.snapshotUpdates.isEmpty())
        {
            importButton.setText("Add luck estimates");
            importButton.setEnabled(true);
        }
        else if (!importInFlight && !huntingPlan.isEmpty())
        {
            importButton.setText("Update the website");
            importButton.setEnabled(true);
        }
    }

    /**
     * Sends the still-hunting changes a chunk at a time, removals riding
     * along with the first chunk, then reports the whole import. Runs on
     * the EDT, like sendImportBatch.
     */
    private void sendHunting(String token, String accountHash, BackfillPlanner.HuntingPlan hunting, int offset,
        String imported)
    {
        List<BackfillPlanner.HuntingItem> items = hunting.toSync;
        boolean first = offset == 0;
        if (hunting.isEmpty() || (!first && offset >= items.size()))
        {
            importInFlight = false;
            String updated = hunting.isEmpty() ? "" : "Updated " + (items.size() + hunting.obtained.size())
                + " still-hunting item(s) and " + hunting.pages.size() + " log page(s).";
            statusLabel.setText("<html>" + (imported + updated).trim() + "</html>");
            // Reload what the backend holds, so the next plan diffs against it.
            if (!hunting.isEmpty())
            {
                huntingByAccount.remove(accountHash);
                pagesByAccount.remove(accountHash);
                recordedByAccount.remove(accountHash);
            }
            refresh();
            return;
        }

        List<SyncHuntingRequest.Item> chunk = new ArrayList<>();
        for (BackfillPlanner.HuntingItem h : items.subList(offset, Math.min(offset + IMPORT_BATCH_SIZE, items.size())))
        {
            chunk.add(new SyncHuntingRequest.Item(h.itemId, h.sourceName, h.kc));
        }
        List<SyncHuntingRequest.Pair> obtained = new ArrayList<>();
        List<SyncHuntingRequest.Page> pages = new ArrayList<>();
        if (first)
        {
            for (BackfillPlanner.Candidate c : hunting.obtained)
            {
                obtained.add(new SyncHuntingRequest.Pair(c.itemId, c.sourceName));
            }
            // Well under the backend's 200 per request: the log has about 120 boss pages.
            pages.addAll(hunting.pages);
        }

        apiClient.syncHunting(token, accountHash, chunk, obtained, pages, result ->
            SwingUtilities.invokeLater(() ->
            {
                if (result == null)
                {
                    importInFlight = false;
                    statusLabel.setText("<html>" + imported + "Couldn't update still hunting &mdash; "
                        + "check your API settings and try again.</html>");
                    huntingByAccount.remove(accountHash);
                    pagesByAccount.remove(accountHash);
                    recordedByAccount.remove(accountHash);
                    refresh();
                    return;
                }
                // At least one pass even when only removals were sent.
                int next = offset + Math.max(chunk.size(), 1);
                sendHunting(token, accountHash, hunting, next, imported);
            })
        );
    }

    private String displayName(BackfillPlanner.Candidate c)
    {
        return itemNames.getOrDefault(c.key(), "Item #" + c.itemId) + " (" + c.sourceName + ")";
    }

    private void onImportClicked()
    {
        List<BackfillPlanner.Candidate> toSend = new ArrayList<>(readyToImport);
        toSend.addAll(snapshotUpdates);
        BackfillPlanner.HuntingPlan hunting = huntingPlan;
        if (toSend.isEmpty() && hunting.isEmpty())
        {
            return;
        }
        int newItems = readyToImport.size();

        String accountHash = plugin.getCurrentAccountHash();
        String token = plugin.getInstallToken();
        if (accountHash == null || token == null || token.isEmpty())
        {
            statusLabel.setText("Not registered yet — log in first, then try again.");
            return;
        }

        String question;
        if (newItems > 0)
        {
            question = "Mark " + newItems + " item(s) as already obtained?\n\n"
                + "They'll show as \"logged before tracking\", with a luck estimate\n"
                + "from the kill count on their log page where there is one.\n";
        }
        else if (!toSend.isEmpty())
        {
            question = "Add a luck estimate to " + toSend.size() + " imported item(s)?\n\n"
                + "It's based on the kill count and quantity on their log page.\n";
        }
        else
        {
            question = "Update your log pages and \"still hunting\" list on the website?\n\n"
                + "They show what your log pages have and are missing, with their kill counts.\n";
        }
        if (!hunting.isEmpty() && !toSend.isEmpty())
        {
            question += "\nYour log pages and \"still hunting\" list on the website update too.\n";
        }
        int choice = JOptionPane.showConfirmDialog(
            this,
            question + (toSend.isEmpty() ? "" : "Imports can't be undone."),
            "Import from collection log",
            JOptionPane.OK_CANCEL_OPTION
        );
        if (choice != JOptionPane.OK_OPTION)
        {
            return;
        }

        importInFlight = true;
        importButton.setEnabled(false);
        importSnapshotUpdates = toSend.size() - newItems;
        importHunting = hunting;
        statusLabel.setText(toSend.isEmpty() ? "Updating still hunting..." : "Importing " + toSend.size() + " item(s)...");
        sendImportBatch(token, accountHash, toSend, 0, 0, 0, 0);
    }

    /**
     * Sends one chunk, then chains the next from its callback, so chunks
     * go out one at a time and a failure stops the rest. Runs on the EDT.
     */
    private void sendImportBatch(String token, String accountHash, List<BackfillPlanner.Candidate> all,
        int offset, int inserted, int alreadyRecorded, int snapshotsAdded)
    {
        if (offset >= all.size())
        {
            int recordedBefore = Math.max(0, alreadyRecorded - importSnapshotUpdates);
            String imported = all.isEmpty() ? "" : "Imported " + inserted + " item(s)"
                + (snapshotsAdded > 0 ? ", added " + snapshotsAdded + " luck estimate(s)" : "")
                + (recordedBefore > 0 ? ", " + recordedBefore + " were already recorded" : "") + ". ";
            sendHunting(token, accountHash, importHunting, 0, imported);
            return;
        }

        List<BackfillPlanner.Candidate> chunk = all.subList(offset, Math.min(offset + IMPORT_BATCH_SIZE, all.size()));
        List<BackfillBatchRequest.Drop> drops = new ArrayList<>();
        for (BackfillPlanner.Candidate c : chunk)
        {
            drops.add(new BackfillBatchRequest.Drop(c.itemId, c.sourceName, c.snapshotKc, c.snapshotQuantity));
        }

        apiClient.backfillDrops(token, accountHash, drops, result ->
            SwingUtilities.invokeLater(() ->
            {
                if (result == null)
                {
                    importInFlight = false;
                    statusLabel.setText("<html>Import failed after " + inserted
                        + " item(s) &mdash; check your API settings and try again.</html>");
                    refresh();
                    return;
                }

                Set<String> sent = sentByAccount.computeIfAbsent(accountHash, k -> new HashSet<>());
                Set<String> needsSnapshot = needsSnapshotByAccount.getOrDefault(accountHash, Collections.emptySet());
                for (BackfillPlanner.Candidate c : chunk)
                {
                    sent.add(c.key());
                    // Offered once: the backend only ever fills a missing snapshot.
                    needsSnapshot.remove(c.key());
                }
                plugin.invalidateLuckResults();
                sendImportBatch(token, accountHash, all, offset + chunk.size(),
                    inserted + result.inserted, alreadyRecorded + result.alreadyRecorded,
                    snapshotsAdded + result.snapshotsAdded);
            })
        );
    }

    private void onBackfillClicked()
    {
        CatalogChoice selected = (CatalogChoice) backfillDropdown.getSelectedItem();
        if (selected == null || selected.itemId == -1)
        {
            return;
        }

        String accountHash = plugin.getCurrentAccountHash();
        String token = plugin.getInstallToken();
        if (accountHash == null || token == null || token.isEmpty())
        {
            statusLabel.setText("Not registered yet — log in first, then try again.");
            return;
        }

        backfillButton.setEnabled(false);
        statusLabel.setText("Submitting " + selected.itemName + "...");

        apiClient.backfillDrop(token, accountHash, selected.itemId, selected.sourceName, success ->
            SwingUtilities.invokeLater(() ->
            {
                backfillButton.setEnabled(true);
                statusLabel.setText(success
                    ? "Marked " + selected.itemName + " as obtained."
                    : "Failed to submit " + selected.itemName + " — check your API settings.");
                if (success)
                {
                    sentByAccount.computeIfAbsent(accountHash, k -> new HashSet<>())
                        .add(new BackfillPlanner.Candidate(selected.itemId, selected.sourceName).key());
                    plugin.invalidateLuckResults();
                    refresh();
                }
            })
        );
    }

    /**
     * Dropdown entry pairing a display label with the underlying
     * item_id/source_name the backend actually needs. toString() drives
     * what JComboBox renders, so it doubles as the display label.
     */
    private static class CatalogChoice
    {
        final int itemId;
        final String itemName;
        final String sourceName;

        CatalogChoice(int itemId, String itemName, String sourceName)
        {
            this.itemId = itemId;
            this.itemName = itemName;
            this.sourceName = sourceName;
        }

        @Override
        public String toString()
        {
            return sourceName.isEmpty() ? itemName : itemName + " (" + sourceName + ")";
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) return true;
            if (!(o instanceof CatalogChoice)) return false;
            CatalogChoice that = (CatalogChoice) o;
            return itemId == that.itemId && sourceName.equals(that.sourceName);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(itemId, sourceName);
        }
    }
}
