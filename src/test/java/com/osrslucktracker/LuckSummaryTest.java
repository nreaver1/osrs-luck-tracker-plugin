package com.osrslucktracker;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LuckSummaryTest
{
    private static PlayerLuckResponse.Result rated(double p)
    {
        PlayerLuckResponse.Result r = new PlayerLuckResponse.Result();
        r.supported = true;
        r.probability = p;
        return r;
    }

    private static PlayerLuckResponse.Result backfilled()
    {
        PlayerLuckResponse.Result r = new PlayerLuckResponse.Result();
        r.supported = true;
        r.backfilled = true;
        return r;
    }

    @Test
    void needsThreeRatedDrops()
    {
        assertNull(LuckSummary.overall(Arrays.asList(rated(0.1), rated(0.2), backfilled())));
    }

    @Test
    void overallIsIrwinHallPercentileOfRatedDrops()
    {
        List<PlayerLuckResponse.Result> results = new ArrayList<>(Arrays.asList(rated(0.1), rated(0.1), rated(0.1)));
        results.add(backfilled());
        LuckSummary.Overall overall = LuckSummary.overall(results);
        assertEquals(3, overall.ratedDrops);
        assertEquals(1, overall.unrated);
        assertEquals(0.1, overall.average, 1e-9);
        // Sum 0.3 of three uniforms: 0.3^3 / 3!.
        assertEquals(0.0045, overall.percentile, 1e-9);
        assertEquals("Luckier than 97%", LuckSummary.describePercentile(0.03));
    }

    @Test
    void evenLuckIsTheMiddle()
    {
        assertEquals(0.5, LuckSummary.uniformSumCdf(2, 4), 1e-9);
        assertEquals(0.5, LuckSummary.uniformSumCdf(25, 50), 1e-6);
    }

    @Test
    void tiersMatchTheWebsite()
    {
        assertEquals("MEGA JACKPOT", LuckSummary.tier(0.005).text);
        assertEquals("JACKPOT", LuckSummary.tier(0.01).text);
        assertEquals("even money", LuckSummary.tier(0.58).text);
        assertEquals("dry streak", LuckSummary.tier(0.95).text);
        assertEquals("desert", LuckSummary.tier(0.995).text);
    }

    private static PlayerLuckResponse.Hunting hunt(String source, int kc, double p, String updatedAt)
    {
        PlayerLuckResponse.Hunting h = new PlayerLuckResponse.Hunting();
        h.sourceName = source;
        h.kc = kc;
        h.probability = p;
        h.updatedAt = updatedAt;
        return h;
    }

    @Test
    void huntingGroupsLatestFirstWithTheMostOverdueLeading()
    {
        PlayerLuckResponse.Hunting early = hunt("Vorkath", 100, 0.2, "2026-10-01");
        PlayerLuckResponse.Hunting overdue = hunt("Vorkath", 100, 0.9, "2026-10-02");
        PlayerLuckResponse.Hunting zulrah = hunt("Zulrah", 500, 0.6, "2026-09-01");

        List<LuckSummary.SourceGroup<PlayerLuckResponse.Hunting>> groups =
            LuckSummary.groupHunting(Arrays.asList(zulrah, early, overdue));
        assertEquals("Vorkath", groups.get(0).sourceName);
        assertEquals(Arrays.asList(overdue, early), groups.get(0).items);
        assertEquals(100, groups.get(0).kc);
        assertEquals("Zulrah", groups.get(1).sourceName);
    }

    @Test
    void huntingNeverReadsAsSpooned()
    {
        assertEquals("still early", LuckSummary.huntingTier(0.02).text);
        assertEquals("still early", LuckSummary.huntingTier(0.3).text);
        assertEquals("even money", LuckSummary.huntingTier(0.5).text);
        assertEquals("bone dry", LuckSummary.huntingTier(0.97).text);
    }

    @Test
    void formatsPercentagesWithoutClaimingAHundred()
    {
        assertEquals("88%", LuckSummary.formatPct(0.876));
        assertEquals("99.5%", LuckSummary.formatPct(0.995));
        assertEquals("99.9%+", LuckSummary.formatPct(0.9997));
        assertEquals("Drier than 88%", LuckSummary.describePercentile(0.876));
    }
}
