package com.osrslucktracker;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// Header lines after the page title, as the collection log draws them:
// the obtained count first, then one line per kill counter.
class LogPageSnapshotTest
{
    @Test
    void readsTheOnlyKillCountWithColourTagsAndCommas()
    {
        assertEquals(Integer.valueOf(1234), LogPageSnapshot.parseKillCount("Test page", Arrays.asList(
            "Obtained: <col=ff0000>12/24</col>",
            "Barrows Chests: <col=ff0000>1,234</col>")));
    }

    @Test
    void ignoresTheObtainedLine()
    {
        assertNull(LogPageSnapshot.parseKillCount("Test page", Collections.singletonList("Obtained: <col=ff0000>12/24</col>")));
        assertNull(LogPageSnapshot.parseKillCount("Test page", Collections.singletonList("Obtained: 12")));
    }

    @Test
    void ignoresAPersonalBestTime()
    {
        // Brutus's header, as drawn in-game.
        assertEquals(Integer.valueOf(568), LogPageSnapshot.parseKillCount("Test page", Arrays.asList(
            "Obtained: <col=0dc10d>4/4</col>",
            "Personal Best: <col=ffffff>0:02</col>",
            "Brutus kills: <col=ffffff>568</col>")));
        assertNull(LogPageSnapshot.parseKillCount("Test page", Collections.singletonList("Personal Best: <col=ffffff>1:02:13</col>")));
    }

    @Test
    void temporossReadsItsRewardPermits()
    {
        // Its reward pool rolls once per permit, not per kill.
        List<String> header = Arrays.asList(
            "Obtained: <col=ffff00>3/12</col>",
            "Personal Best: <col=ffffff>5:43</col>",
            "Reward permits claimed: <col=ffffff>126</col>",
            "Tempoross kills: <col=ffffff>33</col>");
        assertEquals(Integer.valueOf(126), LogPageSnapshot.parseKillCount("Tempoross", header));
        // The same counters on a page without a named roll counter are ambiguous.
        assertNull(LogPageSnapshot.parseKillCount("Test page", header));
        // No permit line, no snapshot, even though a kill count is there.
        assertNull(LogPageSnapshot.parseKillCount("Tempoross", Arrays.asList(
            "Obtained: <col=ffff00>3/12</col>",
            "Tempoross kills: <col=ffffff>33</col>")));
    }

    @Test
    void severalCountersMeanNoSnapshot()
    {
        // Wintertodt: a claimed cart gives a points-dependent number of rolls.
        assertNull(LogPageSnapshot.parseKillCount("Wintertodt", Arrays.asList(
            "Obtained: <col=ffff00>2/10</col>",
            "Rewards claimed: <col=ffffff>85</col>",
            "Wintertodt kills: <col=ffffff>41</col>")));
        // Can't tell which counter an item's kills belong to.
        assertNull(LogPageSnapshot.parseKillCount("Test page", Arrays.asList(
            "Obtained: <col=ff0000>3/12</col>",
            "Gauntlet completions: <col=ff0000>40</col>",
            "Corrupted Gauntlet completions: <col=ff0000>210</col>")));
    }

    @Test
    void noCounterMeansNoSnapshot()
    {
        assertNull(LogPageSnapshot.parseKillCount("Test page", Collections.emptyList()));
    }

    @Test
    void mergeKeepsTheLargerOfEachNumber()
    {
        Map<Integer, Integer> first = new HashMap<>();
        first.put(1, 2);
        first.put(2, 1);
        Map<Integer, Integer> second = new HashMap<>();
        second.put(1, 1); // a read that caught the slot mid-redraw
        second.put(3, 1);

        LogPageSnapshot merged = new LogPageSnapshot(210, second).merge(new LogPageSnapshot(200, first));

        assertEquals(210, merged.kc);
        assertEquals(Integer.valueOf(2), merged.quantityOf(1));
        assertEquals(Integer.valueOf(1), merged.quantityOf(2));
        assertEquals(Integer.valueOf(1), merged.quantityOf(3));
        assertNull(merged.quantityOf(4));
    }
}
