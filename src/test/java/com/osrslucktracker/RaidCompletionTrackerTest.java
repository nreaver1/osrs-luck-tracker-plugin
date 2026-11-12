package com.osrslucktracker;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaidCompletionTrackerTest
{
    private static final Instant NOW = Instant.parse("2026-11-12T20:00:00Z");

    private static Map<String, Integer> counters(String... modeAndCount)
    {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < modeAndCount.length; i += 2)
        {
            m.put(modeAndCount[i], Integer.valueOf(modeAndCount[i + 1]));
        }
        return m;
    }

    @Test
    void namesTheRaidAndModeFromTheCompletionMessage()
    {
        assertArrayEquals(new String[]{"Chambers of Xeric", "normal"}, RaidCompletionTracker.raidAndMode("Chambers of Xeric"));
        assertArrayEquals(new String[]{"Chambers of Xeric", "challenge"},
            RaidCompletionTracker.raidAndMode("Chambers of Xeric Challenge Mode"));
        assertArrayEquals(new String[]{"Theatre of Blood", "entry"},
            RaidCompletionTracker.raidAndMode("Theatre of Blood: Entry Mode"));
        assertArrayEquals(new String[]{"Theatre of Blood", "hard"},
            RaidCompletionTracker.raidAndMode("Theatre of Blood: Hard Mode"));
        assertNull(RaidCompletionTracker.raidAndMode("Tombs of Amascut"));
        assertNull(RaidCompletionTracker.raidAndMode(null));
    }

    @Test
    void buildsACoxCompletionWithTheTeamsPoints()
    {
        RaidCompletionTracker t = new RaidCompletionTracker();
        assertFalse(t.pending(0));
        t.onCompletionMessage("Chambers of Xeric Challenge Mode", 13, 0);
        assertTrue(t.pending(1_000));
        assertEquals("Chambers of Xeric", t.pendingRaid());

        // The counter lags the message for its own mode.
        RaidRecord r = t.buildCox(counters("normal", "51", "challenge", "12"), 3, 45_000, 140_000, 2, NOW);
        assertEquals("Chambers of Xeric", r.raid);
        assertEquals("challenge", r.mode);
        assertEquals(Integer.valueOf(13), r.modeCounts.get("challenge"));
        assertEquals(64, r.totalCount());
        assertEquals(Integer.valueOf(3), r.teamSize);
        assertEquals(Integer.valueOf(45_000), r.personalPoints);
        assertEquals(140_000, r.details.get("total_points"));
        assertEquals(2, r.details.get("reward_indicator"));
        assertNull(r.purple);
        assertFalse(t.pending(0));
    }

    @Test
    void clearedCoxVarbitsAreSentAsUnknown()
    {
        RaidCompletionTracker t = new RaidCompletionTracker();
        t.onCompletionMessage("Chambers of Xeric", 52, 0);
        RaidRecord r = t.buildCox(counters("normal", "52"), 0, 0, 0, 0, NOW);
        assertNull(r.teamSize);
        assertNull(r.personalPoints);
        assertFalse(r.details.containsKey("total_points"));
        assertEquals(Integer.valueOf(0), r.modeCounts.get("challenge"));
    }

    @Test
    void buildsATobCompletionWithTheTeamSize()
    {
        RaidCompletionTracker t = new RaidCompletionTracker();
        t.onCompletionMessage("Theatre of Blood: Hard Mode", 2, 0);
        RaidRecord r = t.buildTob(counters("entry", "73", "normal", "5", "hard", "2"), new int[]{1, 1, 3, 0, 0},
            new int[]{0, 1, 0, 0, 0}, NOW);
        assertEquals("Theatre of Blood", r.raid);
        assertEquals("hard", r.mode);
        assertEquals(80, r.totalCount());
        assertEquals(Integer.valueOf(3), r.teamSize);
        assertNull(r.personalPoints);
        assertArrayEquals(new int[]{0, 1, 0, 0, 0}, (int[]) r.details.get("chests"));
    }

    @Test
    void dropsAStaleCompletionAndRefusesTheWrongRaid()
    {
        RaidCompletionTracker t = new RaidCompletionTracker();
        t.onCompletionMessage("Theatre of Blood", 5, 0);
        assertThrows(IllegalStateException.class, () -> t.buildCox(counters(), 1, 1, 1, 0, NOW));
        assertFalse(t.pending(3 * 60_000));
        t.onCompletionMessage("Zulrah", 5, 0);
        assertFalse(t.pending(0));
    }
}
