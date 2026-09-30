package com.osrslucktracker;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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
        assertEquals(Integer.valueOf(1234), LogPageSnapshot.parseKillCount(Arrays.asList(
            "Obtained: <col=ff0000>12/24</col>",
            "Barrows Chests: <col=ff0000>1,234</col>")));
    }

    @Test
    void ignoresTheObtainedLine()
    {
        assertNull(LogPageSnapshot.parseKillCount(Collections.singletonList("Obtained: <col=ff0000>12/24</col>")));
        assertNull(LogPageSnapshot.parseKillCount(Collections.singletonList("Obtained: 12")));
    }

    @Test
    void severalCountersMeanNoSnapshot()
    {
        // Can't tell which counter an item's kills belong to.
        assertNull(LogPageSnapshot.parseKillCount(Arrays.asList(
            "Obtained: <col=ff0000>3/12</col>",
            "Gauntlet completions: <col=ff0000>40</col>",
            "Corrupted Gauntlet completions: <col=ff0000>210</col>")));
    }

    @Test
    void noCounterMeansNoSnapshot()
    {
        assertNull(LogPageSnapshot.parseKillCount(Collections.emptyList()));
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
