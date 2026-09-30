package com.osrslucktracker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns "collection log pages the player has opened" into "items we can
 * safely backfill". Pure logic — no client access — so it can be unit
 * tested without a game client.
 *
 * An obtained slot on page P becomes an import candidate only when all
 * of these hold:
 *  - P matches a source in the drop-rate catalog (we only track luck for
 *    catalog sources),
 *  - the catalog has a rate for that item from that source, and
 *  - no other log page that is a catalog source with a rate for the
 *    item also lists it, so P is provably where it came from (see
 *    {@link CollectionLogIndex}). Pages that aren't a drop source, like
 *    "All Pets", don't count: a pet is still attributable to its boss.
 * Obtained catalog items that fail only the last check are reported as
 * shared, so the panel can point the player at the manual dropdown.
 *
 * A ready item also carries its page's KC snapshot when the page header
 * showed exactly one kill count and the slot a quantity (see
 * {@link LogPageSnapshot}). Flat-rate items imported before snapshots
 * existed are listed as snapshot updates, which the backend fills in once.
 */
final class BackfillPlanner
{
    private static final String FLAT_RATE = "flat_geometric";

    private BackfillPlanner()
    {
    }

    static final class Candidate
    {
        final int itemId;
        final String sourceName;
        // Both or neither; not part of equality, which is the (item, source) pair.
        final Integer snapshotKc;
        final Integer snapshotQuantity;

        Candidate(int itemId, String sourceName)
        {
            this(itemId, sourceName, null, null);
        }

        Candidate(int itemId, String sourceName, Integer snapshotKc, Integer snapshotQuantity)
        {
            this.itemId = itemId;
            this.sourceName = sourceName;
            boolean both = snapshotKc != null && snapshotQuantity != null;
            this.snapshotKc = both ? snapshotKc : null;
            this.snapshotQuantity = both ? snapshotQuantity : null;
        }

        boolean hasSnapshot()
        {
            return snapshotKc != null;
        }

        String key()
        {
            return itemId + "|" + sourceName;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) return true;
            if (!(o instanceof Candidate)) return false;
            Candidate that = (Candidate) o;
            return itemId == that.itemId && sourceName.equals(that.sourceName);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(itemId, sourceName);
        }

        @Override
        public String toString()
        {
            return key();
        }
    }

    static final class Plan
    {
        /** Safe to import: single-page items from a catalog source. */
        final List<Candidate> ready = new ArrayList<>();
        /** Already-imported items without a snapshot that a read page has one for. */
        final List<Candidate> snapshotUpdates = new ArrayList<>();
        /** Obtained catalog items that several rated sources' pages list — source unknown. */
        final List<Candidate> shared = new ArrayList<>();
        /** Catalog sources with a log page the player hasn't opened yet. */
        final Set<String> pagesToOpen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        /** Collection log pages read so far. */
        int pagesRead;
        /** Obtained items on read pages that have no drop rate for that page's source. */
        final Set<Integer> obtainedWithoutRate = new TreeSet<>();
    }

    /**
     * @param catalog       every (item_id, source_name) pair with a drop rate
     * @param obtainedByPage page title -> item ids shown as obtained on it
     * @param index         item/page layout from the game cache
     * @param alreadySent   {@link Candidate#key()}s submitted earlier this session
     */
    static Plan plan(
        Collection<CatalogEntry> catalog,
        Map<String, Set<Integer>> obtainedByPage,
        CollectionLogIndex index,
        Set<String> alreadySent)
    {
        return plan(catalog, obtainedByPage, index, alreadySent, Collections.emptyMap(), Collections.emptySet());
    }

    /**
     * @param snapshots      page title -> that page's KC snapshot
     * @param needsSnapshot  {@link Candidate#key()}s recorded as backfilled with no snapshot yet
     */
    static Plan plan(
        Collection<CatalogEntry> catalog,
        Map<String, Set<Integer>> obtainedByPage,
        CollectionLogIndex index,
        Set<String> alreadySent,
        Map<String, LogPageSnapshot> snapshots,
        Set<String> needsSnapshot)
    {
        // source name as the log page would show it -> catalog item ids
        Map<String, Set<Integer>> catalogBySource = new HashMap<>();
        Map<String, String> sourceByNormalizedName = new HashMap<>();
        // The backend only rates snapshots of flat rates, so only those are
        // worth a snapshot update; a new import still sends one for any type.
        Set<String> flatRated = new HashSet<>();
        for (CatalogEntry entry : catalog)
        {
            if (FLAT_RATE.equals(entry.distributionType))
            {
                flatRated.add(new Candidate(entry.itemId, entry.sourceName).key());
            }
            catalogBySource.computeIfAbsent(entry.sourceName, k -> new TreeSet<>()).add(entry.itemId);
            sourceByNormalizedName.putIfAbsent(normalize(entry.sourceName), entry.sourceName);
        }

        Plan plan = new Plan();

        for (String source : catalogBySource.keySet())
        {
            String page = pageFor(source, index);
            if (page != null && !obtainedByPage.containsKey(page)
                && catalogBySource.get(source).stream().anyMatch(id -> index.itemsOn(page).contains(id)))
            {
                plan.pagesToOpen.add(page);
            }
        }

        plan.pagesRead = obtainedByPage.size();
        List<String> pages = new ArrayList<>(obtainedByPage.keySet());
        pages.sort(Comparator.naturalOrder());
        for (String page : pages)
        {
            String source = sourceByNormalizedName.get(normalize(page));
            Set<Integer> tracked = source == null ? Collections.emptySet() : catalogBySource.get(source);
            LogPageSnapshot snapshot = snapshots.get(page);

            for (int itemId : new TreeSet<>(obtainedByPage.get(page)))
            {
                if (!tracked.contains(itemId))
                {
                    plan.obtainedWithoutRate.add(itemId);
                    continue;
                }
                boolean singlePage = ratedPagesFor(itemId, index, sourceByNormalizedName, catalogBySource) == 1;
                // A shared item's quantity counts copies from every source, so only single-page items get one.
                Candidate candidate = snapshot != null && singlePage
                    ? new Candidate(itemId, source, snapshot.kc, snapshot.quantityOf(itemId))
                    : new Candidate(itemId, source);
                if (alreadySent.contains(candidate.key()))
                {
                    if (candidate.hasSnapshot() && needsSnapshot.contains(candidate.key())
                        && flatRated.contains(candidate.key()))
                    {
                        plan.snapshotUpdates.add(candidate);
                    }
                    continue;
                }
                if (singlePage)
                {
                    plan.ready.add(candidate);
                }
                else
                {
                    plan.shared.add(candidate);
                }
            }
        }

        return plan;
    }

    /** How many of the item's log pages belong to a catalog source that rates it. */
    private static long ratedPagesFor(
        int itemId,
        CollectionLogIndex index,
        Map<String, String> sourceByNormalizedName,
        Map<String, Set<Integer>> catalogBySource)
    {
        return index.pagesFor(itemId).stream()
            .map(p -> sourceByNormalizedName.get(normalize(p)))
            .filter(s -> s != null && catalogBySource.get(s).contains(itemId))
            .count();
    }

    /** True if the item is a collection log slot on the page for this catalog source. */
    static boolean isLogItemForSource(int itemId, String source, CollectionLogIndex index)
    {
        String page = pageFor(source, index);
        return page != null && index.itemsOn(page).contains(itemId);
    }

    /** The log page a catalog source corresponds to, or null if it has none. */
    private static String pageFor(String source, CollectionLogIndex index)
    {
        if (index.hasPage(source))
        {
            return source;
        }
        String wanted = normalize(source);
        return index.pageNames().stream()
            .filter(p -> normalize(p).equals(wanted))
            .findFirst()
            .orElse(null);
    }

    /**
     * Catalog sources are wiki page names and log pages are in-game
     * titles; they agree for every current source, but tolerate
     * case/punctuation/leading-"The" drift rather than silently dropping
     * a whole boss.
     */
    static String normalize(String name)
    {
        return name.toLowerCase(Locale.ROOT)
            .replaceFirst("^the\\s+", "")
            .replaceAll("[^a-z0-9]", "");
    }
}
