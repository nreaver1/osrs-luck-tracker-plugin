package com.osrslucktracker;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackfillPlannerTest
{
    // Real item ids, trimmed to what the tests need.
    private static final int PET_GENERAL_GRAARDOR = 12650;
    private static final int BANDOS_CHESTPLATE = 11832;
    private static final int PET_KREEARRA = 12649;
    private static final int ARMADYL_HELMET = 11826;
    private static final int GODSWORD_SHARD_1 = 11818; // on all four GWD pages
    private static final int PET_SNAKELING = 12921;
    private static final int TANZANITE_FANG = 12922;

    private final CollectionLogIndex index = new CollectionLogIndex(pages(
        "General Graardor", set(PET_GENERAL_GRAARDOR, BANDOS_CHESTPLATE, GODSWORD_SHARD_1),
        "Kree'arra", set(PET_KREEARRA, ARMADYL_HELMET, GODSWORD_SHARD_1),
        "Zulrah", set(PET_SNAKELING, TANZANITE_FANG),
        "All Pets", set(PET_GENERAL_GRAARDOR, PET_KREEARRA, PET_SNAKELING)
    ));

    private final List<CatalogEntry> catalog = Arrays.asList(
        entry(BANDOS_CHESTPLATE, "General Graardor"),
        entry(GODSWORD_SHARD_1, "General Graardor"),
        entry(ARMADYL_HELMET, "Kree'arra"),
        entry(GODSWORD_SHARD_1, "Kree'arra"),
        entry(TANZANITE_FANG, "Zulrah", "points_based") // not really, but a non-flat rate to test with
    );

    @Test
    void singlePageItemFromCatalogSourceIsReady()
    {
        BackfillPlanner.Plan plan = plan(pages("General Graardor", set(BANDOS_CHESTPLATE)));

        assertEquals(keys(BANDOS_CHESTPLATE + "|General Graardor"), keysOf(plan.ready));
        assertTrue(plan.shared.isEmpty());
    }

    @Test
    void itemOnSeveralPagesIsSharedNotReady()
    {
        // The shard shows obtained on every GWD page no matter who dropped it.
        BackfillPlanner.Plan plan = plan(pages(
            "General Graardor", set(GODSWORD_SHARD_1),
            "Kree'arra", set(GODSWORD_SHARD_1)
        ));

        assertTrue(plan.ready.isEmpty());
        assertEquals(keys(GODSWORD_SHARD_1 + "|General Graardor", GODSWORD_SHARD_1 + "|Kree'arra"), keysOf(plan.shared));
    }

    @Test
    void sharedEvenWhenOnlyOneOfItsPagesWasOpened()
    {
        BackfillPlanner.Plan plan = plan(pages("General Graardor", set(GODSWORD_SHARD_1)));

        assertTrue(plan.ready.isEmpty());
        assertEquals(1, plan.shared.size());
    }

    @Test
    void itemsNotInCatalogAndPagesWithoutASourceAreIgnored()
    {
        BackfillPlanner.Plan plan = plan(pages(
            "General Graardor", set(PET_GENERAL_GRAARDOR),
            "All Pets", set(PET_SNAKELING)
        ));

        assertTrue(plan.ready.isEmpty());
        assertTrue(plan.shared.isEmpty());
        assertEquals(2, plan.pagesRead);
        assertEquals(set(PET_GENERAL_GRAARDOR, PET_SNAKELING), plan.obtainedWithoutRate);
    }

    @Test
    void petOnItsBossPageAndAllPetsIsReady()
    {
        // "All Pets" lists every pet but isn't a drop source, so it doesn't make the pet shared.
        List<CatalogEntry> withPet = Arrays.asList(
            entry(PET_SNAKELING, "Zulrah"),
            entry(TANZANITE_FANG, "Zulrah")
        );

        BackfillPlanner.Plan plan = BackfillPlanner.plan(
            withPet,
            pages("Zulrah", set(PET_SNAKELING), "All Pets", set(PET_SNAKELING)),
            index,
            Collections.emptySet());

        assertEquals(keys(PET_SNAKELING + "|Zulrah"), keysOf(plan.ready));
        assertTrue(plan.shared.isEmpty());
    }

    @Test
    void itemOnAnotherPageWithoutARateThereIsReady()
    {
        // Only Graardor has a rate for the shard here, so Kree'arra's page doesn't compete.
        List<CatalogEntry> graardorOnly = Collections.singletonList(entry(GODSWORD_SHARD_1, "General Graardor"));

        BackfillPlanner.Plan plan = BackfillPlanner.plan(
            graardorOnly, pages("General Graardor", set(GODSWORD_SHARD_1)), index, Collections.emptySet());

        assertEquals(keys(GODSWORD_SHARD_1 + "|General Graardor"), keysOf(plan.ready));
    }

    @Test
    void alreadySentItemsAreHidden()
    {
        BackfillPlanner.Plan plan = BackfillPlanner.plan(
            catalog,
            pages("Zulrah", set(TANZANITE_FANG)),
            index,
            keys(TANZANITE_FANG + "|Zulrah")
        );

        assertTrue(plan.ready.isEmpty());
    }

    @Test
    void pagesToOpenListsUnreadCatalogSources()
    {
        BackfillPlanner.Plan plan = plan(pages("Zulrah", set()));

        assertEquals(Arrays.asList("General Graardor", "Kree'arra"), Arrays.asList(plan.pagesToOpen.toArray()));
    }

    @Test
    void sourceNamesMatchPagesDespiteCaseAndPunctuation()
    {
        List<CatalogEntry> driftedCatalog = Collections.singletonList(entry(ARMADYL_HELMET, "kreearra"));

        BackfillPlanner.Plan plan = BackfillPlanner.plan(
            driftedCatalog, pages("Kree'arra", set(ARMADYL_HELMET)), index, Collections.emptySet());

        assertEquals(keys(ARMADYL_HELMET + "|kreearra"), keysOf(plan.ready));
    }

    @Test
    void readyItemCarriesItsPagesSnapshot()
    {
        Map<String, LogPageSnapshot> snapshots = new HashMap<>();
        snapshots.put("General Graardor", new LogPageSnapshot(1100, quantities(BANDOS_CHESTPLATE, 2)));

        BackfillPlanner.Plan plan = BackfillPlanner.plan(catalog,
            pages("General Graardor", set(BANDOS_CHESTPLATE)), index, Collections.emptySet(),
            snapshots, Collections.emptySet());

        BackfillPlanner.Candidate c = plan.ready.get(0);
        assertEquals(Integer.valueOf(1100), c.snapshotKc);
        assertEquals(Integer.valueOf(2), c.snapshotQuantity);
    }

    @Test
    void noSnapshotWithoutAQuantityOrForASharedItem()
    {
        // Page KC known, but the slot had no quantity captured (stackable),
        // and the shard is on every GWD page, so its quantity isn't Graardor's.
        Map<String, LogPageSnapshot> snapshots = new HashMap<>();
        snapshots.put("General Graardor", new LogPageSnapshot(1100, quantities(GODSWORD_SHARD_1, 1)));

        BackfillPlanner.Plan plan = BackfillPlanner.plan(catalog,
            pages("General Graardor", set(BANDOS_CHESTPLATE, GODSWORD_SHARD_1),
                "Kree'arra", set(GODSWORD_SHARD_1)),
            index, Collections.emptySet(), snapshots, Collections.emptySet());

        assertFalse(plan.ready.get(0).hasSnapshot());
        assertNull(plan.ready.get(0).snapshotQuantity);
        plan.shared.forEach(c -> assertFalse(c.hasSnapshot()));
    }

    @Test
    void importedFlatRateItemWithoutSnapshotBecomesAnUpdate()
    {
        Map<String, LogPageSnapshot> snapshots = new HashMap<>();
        snapshots.put("General Graardor", new LogPageSnapshot(1100, quantities(BANDOS_CHESTPLATE, 1)));
        snapshots.put("Zulrah", new LogPageSnapshot(300, quantities(TANZANITE_FANG, 1)));
        Set<String> recorded = keys(BANDOS_CHESTPLATE + "|General Graardor", TANZANITE_FANG + "|Zulrah");

        BackfillPlanner.Plan plan = BackfillPlanner.plan(catalog,
            pages("General Graardor", set(BANDOS_CHESTPLATE), "Zulrah", set(TANZANITE_FANG)),
            index, recorded, snapshots, recorded);

        assertTrue(plan.ready.isEmpty());
        // The fang's rate isn't flat, so the backend couldn't rate its snapshot.
        assertEquals(keys(BANDOS_CHESTPLATE + "|General Graardor"), keysOf(plan.snapshotUpdates));
    }

    @Test
    void importedItemThatAlreadyHasASnapshotIsLeftAlone()
    {
        Map<String, LogPageSnapshot> snapshots = new HashMap<>();
        snapshots.put("General Graardor", new LogPageSnapshot(1100, quantities(BANDOS_CHESTPLATE, 1)));
        Set<String> recorded = keys(BANDOS_CHESTPLATE + "|General Graardor");

        BackfillPlanner.Plan plan = BackfillPlanner.plan(catalog,
            pages("General Graardor", set(BANDOS_CHESTPLATE)), index, recorded, snapshots, Collections.emptySet());

        assertTrue(plan.snapshotUpdates.isEmpty());
    }

    @Test
    void emptySlotsOnAConsistentPageAreStillHunting()
    {
        // Graardor read with 1 obtained slot, and the header agrees.
        Map<String, LogPageSnapshot> snapshots = new HashMap<>();
        snapshots.put("General Graardor", new LogPageSnapshot(1100, Collections.emptyMap(), 1));

        BackfillPlanner.HuntingPlan plan = BackfillPlanner.planHunting(catalog,
            pages("General Graardor", set(BANDOS_CHESTPLATE)), index, snapshots, Collections.emptyMap(), Collections.emptyMap());

        // The shard counts too: an empty slot means none from any source.
        assertEquals(keys(GODSWORD_SHARD_1 + "|General Graardor"), huntingKeys(plan.toSync));
        assertEquals(1100, plan.toSync.get(0).kc);
        assertTrue(plan.obtained.isEmpty());
    }

    @Test
    void noHuntingFromAPageWhoseSlotsDontMatchItsHeader()
    {
        // The header says 2 obtained but only 1 slot read that way: drawn
        // mid-fade, so its empty slots can't be trusted. Same for a save
        // from before the header count was kept.
        Map<String, LogPageSnapshot> snapshots = new HashMap<>();
        snapshots.put("General Graardor", new LogPageSnapshot(1100, Collections.emptyMap(), 2));
        snapshots.put("Kree'arra", new LogPageSnapshot(500, Collections.emptyMap()));

        BackfillPlanner.HuntingPlan plan = BackfillPlanner.planHunting(catalog,
            pages("General Graardor", set(BANDOS_CHESTPLATE), "Kree'arra", set()),
            index, snapshots, Collections.emptyMap(), Collections.emptyMap());

        assertTrue(plan.isEmpty());
    }

    @Test
    void huntingSyncsOnlyChangesAndClearsObtainedItems()
    {
        Map<String, LogPageSnapshot> snapshots = new HashMap<>();
        snapshots.put("General Graardor", new LogPageSnapshot(1100, Collections.emptyMap(), 1));
        snapshots.put("Kree'arra", new LogPageSnapshot(500, Collections.emptyMap(), 0));
        Map<String, Integer> server = new HashMap<>();
        server.put(BANDOS_CHESTPLATE + "|General Graardor", 900); // since obtained
        server.put(GODSWORD_SHARD_1 + "|General Graardor", 1100); // unchanged
        server.put(ARMADYL_HELMET + "|Kree'arra", 400);           // more kills since

        BackfillPlanner.HuntingPlan plan = BackfillPlanner.planHunting(catalog,
            pages("General Graardor", set(BANDOS_CHESTPLATE), "Kree'arra", set()), index, snapshots, server, Collections.emptyMap());

        assertEquals(keys(ARMADYL_HELMET + "|Kree'arra", GODSWORD_SHARD_1 + "|Kree'arra"), huntingKeys(plan.toSync));
        assertEquals(keys(BANDOS_CHESTPLATE + "|General Graardor"), keysOf(plan.obtained));
    }

    @Test
    void noHuntingForNonFlatRates()
    {
        // The fang's rate here isn't flat.
        Map<String, LogPageSnapshot> snapshots = new HashMap<>();
        snapshots.put("Zulrah", new LogPageSnapshot(300, Collections.emptyMap(), 0));

        BackfillPlanner.HuntingPlan plan = BackfillPlanner.planHunting(catalog,
            pages("Zulrah", set()), index, snapshots, Collections.emptyMap(), Collections.emptyMap());

        assertTrue(plan.toSync.isEmpty());
        assertTrue(plan.obtained.isEmpty());
    }

    @Test
    void consistentPagesAreSentWholeUntilTheBackendHasThem()
    {
        Map<String, LogPageSnapshot> snapshots = new HashMap<>();
        Map<Integer, Integer> quantities = new HashMap<>();
        quantities.put(BANDOS_CHESTPLATE, 2);
        snapshots.put("General Graardor", new LogPageSnapshot(1100, quantities, 1));
        snapshots.put("Kree'arra", new LogPageSnapshot(500, Collections.emptyMap(), 2)); // mid-fade: header says 2

        Map<String, Set<Integer>> read = pages("General Graardor", set(BANDOS_CHESTPLATE), "Kree'arra", set());
        BackfillPlanner.HuntingPlan plan = BackfillPlanner.planHunting(catalog, read, index, snapshots,
            Collections.emptyMap(), Collections.emptyMap());

        assertEquals(1, plan.pages.size());
        SyncHuntingRequest.Page page = plan.pages.get(0);
        assertEquals("General Graardor", page.sourceName);
        assertEquals(1100, page.kc);
        assertEquals(Collections.singletonList(BANDOS_CHESTPLATE), page.obtained);
        assertEquals(Integer.valueOf(2), page.quantities.get(String.valueOf(BANDOS_CHESTPLATE)));

        // Once the backend holds the same read, it isn't sent again.
        Map<String, SyncHuntingRequest.Page> server = new HashMap<>();
        server.put("General Graardor", page);
        assertTrue(BackfillPlanner.planHunting(catalog, read, index, snapshots, Collections.emptyMap(), server)
            .pages.isEmpty());
    }

    @Test
    void dropdownFilterKeepsOnlySlotsOnTheSourcesOwnPage()
    {
        assertTrue(BackfillPlanner.isLogItemForSource(BANDOS_CHESTPLATE, "General Graardor", index));
        assertTrue(BackfillPlanner.isLogItemForSource(GODSWORD_SHARD_1, "Kree'arra", index));
        // Not a log slot on that boss's page (e.g. bones, or another boss's unique)
        assertFalse(BackfillPlanner.isLogItemForSource(ARMADYL_HELMET, "General Graardor", index));
        // Source with no log page at all
        assertFalse(BackfillPlanner.isLogItemForSource(BANDOS_CHESTPLATE, "Hill Giant", index));
    }

    @Test
    void normalizeDropsLeadingTheOnlyAsAWord()
    {
        assertEquals("gauntlet", BackfillPlanner.normalize("The Gauntlet"));
        assertEquals("theatreofblood", BackfillPlanner.normalize("Theatre of Blood"));
        assertEquals("kreearra", BackfillPlanner.normalize("Kree'arra"));
    }

    private BackfillPlanner.Plan plan(Map<String, Set<Integer>> obtainedByPage)
    {
        return BackfillPlanner.plan(catalog, obtainedByPage, index, Collections.emptySet());
    }

    private static CatalogEntry entry(int itemId, String source)
    {
        return entry(itemId, source, "flat_geometric");
    }

    private static CatalogEntry entry(int itemId, String source, String distributionType)
    {
        CatalogEntry e = new CatalogEntry();
        e.itemId = itemId;
        e.sourceName = source;
        e.distributionType = distributionType;
        return e;
    }

    private static Map<Integer, Integer> quantities(int itemId, int quantity)
    {
        Map<Integer, Integer> map = new HashMap<>();
        map.put(itemId, quantity);
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<Integer>> pages(Object... pairs)
    {
        Map<String, Set<Integer>> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            map.put((String) pairs[i], (Set<Integer>) pairs[i + 1]);
        }
        return map;
    }

    private static Set<Integer> set(Integer... ids)
    {
        return new HashSet<>(Arrays.asList(ids));
    }

    private static Set<String> keys(String... keys)
    {
        return new HashSet<>(Arrays.asList(keys));
    }

    private static Set<String> huntingKeys(List<BackfillPlanner.HuntingItem> items)
    {
        Set<String> out = new HashSet<>();
        items.forEach(h -> out.add(h.key()));
        return out;
    }

    private static Set<String> keysOf(List<BackfillPlanner.Candidate> candidates)
    {
        Set<String> out = new HashSet<>();
        candidates.forEach(c -> out.add(c.key()));
        return out;
    }
}
