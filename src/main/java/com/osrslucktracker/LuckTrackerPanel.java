package com.osrslucktracker;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.PluginPanel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
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

    private final LuckTrackerPlugin plugin;
    private final ApiClient apiClient;
    private final ItemManager itemManager;
    private final ClientThread clientThread;

    private final JLabel statusLabel;
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
    private String recordedFetchInFlight;
    private final Map<String, String> itemNames = new HashMap<>();
    // account hash -> Candidate keys already submitted this session
    private final Map<String, Set<String>> sentByAccount = new HashMap<>();
    private List<BackfillPlanner.Candidate> readyToImport = Collections.emptyList();
    private boolean importInFlight;

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

        content.add(Box.createVerticalStrut(8));

        statusLabel = new JLabel("Tracking new drops automatically.");
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(statusLabel);

        content.add(Box.createVerticalStrut(8));

        JButton refreshButton = new JButton("Show tracked boss KCs");
        refreshButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        refreshButton.addActionListener(e ->
            statusLabel.setText(plugin.getBossKillCounts().size() + " boss KC(s) tracked this session")
        );
        content.add(refreshButton);

        content.add(Box.createVerticalStrut(16));

        // --- Import from the in-game collection log ---

        JLabel importTitle = new JLabel("Import from collection log");
        importTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(importTitle);

        JLabel importHelp = new JLabel(
            "<html><i>Open your collection log in-game and click through the pages listed "
                + "below. Items you already have are picked up as each page opens.</i></html>"
        );
        importHelp.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(importHelp);

        content.add(Box.createVerticalStrut(6));

        importSummary = new JLabel("Loading items...");
        importSummary.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(importSummary);

        importPages = new JLabel();
        importPages.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(importPages);

        content.add(Box.createVerticalStrut(6));

        importButton = new JButton("Import items");
        importButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        importButton.setEnabled(false);
        importButton.addActionListener(e -> onImportClicked());
        content.add(importButton);

        content.add(Box.createVerticalStrut(16));

        // --- Manual backfill, one item at a time ---

        JLabel backfillTitle = new JLabel("Add a single item");
        backfillTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(backfillTitle);

        JLabel backfillNote = new JLabel(
            "<html><i>Collection log items you haven't recorded yet &mdash; useful for shared items "
                + "the import can't attribute to one source. Marks it as obtained with unknown luck; "
                + "this can't be undone or matched to a KC.</i></html>"
        );
        backfillNote.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(backfillNote);

        content.add(Box.createVerticalStrut(6));

        backfillDropdown = new JComboBox<>();
        backfillDropdown.setAlignmentX(Component.CENTER_ALIGNMENT);
        backfillDropdown.addItem(new CatalogChoice(-1, "Loading items...", ""));
        content.add(backfillDropdown);

        content.add(Box.createVerticalStrut(6));

        backfillButton = new JButton("Mark as already obtained");
        backfillButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        backfillButton.setEnabled(false);
        backfillButton.addActionListener(e -> onBackfillClicked());
        content.add(backfillButton);

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
     * Brings the whole panel up to date. Runs on the EDT; the plugin
     * calls it on login and whenever a collection log page is read.
     */
    void refresh()
    {
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
        apiClient.fetchRecordedDrops(ign, keys ->
            SwingUtilities.invokeLater(() ->
            {
                recordedFetchInFlight = null;
                if (keys != null)
                {
                    recordedByAccount.put(accountHash, keys);
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
            recordedKeys(accountHash)
        );
        readyToImport = plan.ready;

        StringBuilder summary = new StringBuilder("<html>");
        summary.append(plan.ready.size()).append(plan.ready.size() == 1 ? " item" : " items")
            .append(" ready to import.");
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
                .append(" shared item(s) skipped &mdash; they appear on several log pages, so the page "
                    + "doesn't prove which boss dropped them:");
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

        if (!plan.ready.isEmpty() && !importInFlight)
        {
            importButton.setText("Import " + plan.ready.size() + (plan.ready.size() == 1 ? " item" : " items"));
            importButton.setEnabled(true);
        }
    }

    private String displayName(BackfillPlanner.Candidate c)
    {
        return itemNames.getOrDefault(c.key(), "Item #" + c.itemId) + " (" + c.sourceName + ")";
    }

    private void onImportClicked()
    {
        List<BackfillPlanner.Candidate> toSend = new ArrayList<>(readyToImport);
        if (toSend.isEmpty())
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

        int choice = JOptionPane.showConfirmDialog(
            this,
            "Mark " + toSend.size() + " item(s) as already obtained?\n\n"
                + "They'll show as \"logged before tracking — luck unknown\".\n"
                + "This can't be undone.",
            "Import from collection log",
            JOptionPane.OK_CANCEL_OPTION
        );
        if (choice != JOptionPane.OK_OPTION)
        {
            return;
        }

        importInFlight = true;
        importButton.setEnabled(false);
        statusLabel.setText("Importing " + toSend.size() + " item(s)...");
        sendImportBatch(token, accountHash, toSend, 0, 0, 0);
    }

    /**
     * Sends one chunk, then chains the next from its callback, so chunks
     * go out one at a time and a failure stops the rest. Runs on the EDT.
     */
    private void sendImportBatch(String token, String accountHash, List<BackfillPlanner.Candidate> all,
        int offset, int inserted, int alreadyRecorded)
    {
        if (offset >= all.size())
        {
            importInFlight = false;
            statusLabel.setText("<html>Imported " + inserted + " item(s)"
                + (alreadyRecorded > 0 ? ", " + alreadyRecorded + " were already recorded" : "") + ".</html>");
            refresh();
            return;
        }

        List<BackfillPlanner.Candidate> chunk = all.subList(offset, Math.min(offset + IMPORT_BATCH_SIZE, all.size()));
        List<BackfillBatchRequest.Drop> drops = new ArrayList<>();
        for (BackfillPlanner.Candidate c : chunk)
        {
            drops.add(new BackfillBatchRequest.Drop(c.itemId, c.sourceName));
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
                for (BackfillPlanner.Candidate c : chunk)
                {
                    sent.add(c.key());
                }
                sendImportBatch(token, accountHash, all, offset + chunk.size(),
                    inserted + result.inserted, alreadyRecorded + result.alreadyRecorded);
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
