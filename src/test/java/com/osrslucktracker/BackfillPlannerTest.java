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
        entry(TANZANITE_FANG, "Zulrah")
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
        CatalogEntry e = new CatalogEntry();
        e.itemId = itemId;
        e.sourceName = source;
        return e;
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

    private static Set<String> keysOf(List<BackfillPlanner.Candidate> candidates)
    {
        Set<String> out = new HashSet<>();
        candidates.forEach(c -> out.add(c.key()));
        return out;
    }
}
