package com.osrslucktracker;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToaRaidTrackerTest
{
    private static final Instant NOW = Instant.parse("2026-11-11T22:00:00Z");
    private static final int[] NO_CHESTS = new int[8];

    private static Map<String, Integer> counters(int entry, int normal, int expert)
    {
        Map<String, Integer> m = new HashMap<>();
        m.put("entry", entry);
        m.put("normal", normal);
        m.put("expert", expert);
        return m;
    }

    @Test
    void namesTheModeFromTheCompletionMessage()
    {
        assertEquals("normal", ToaRaidTracker.modeFor("Tombs of Amascut"));
        assertEquals("entry", ToaRaidTracker.modeFor("Tombs of Amascut: Entry Mode"));
        assertEquals("expert", ToaRaidTracker.modeFor("Tombs of Amascut: Expert Mode"));
        assertNull(ToaRaidTracker.modeFor("Theatre of Blood"));
        assertNull(ToaRaidTracker.modeFor(null));
    }

    @Test
    void readyOnlyOnceBothTheMessageAndThePointsArrive()
    {
        ToaRaidTracker t = new ToaRaidTracker();
        assertFalse(t.ready(0));
        t.onPoints(18_000);
        assertFalse(t.ready(0));
        t.onCompletionMessage("Tombs of Amascut: Expert Mode", 3, 0);
        assertTrue(t.ready(1_000));

        // Points before the message work too, and zero points are ignored.
        ToaRaidTracker u = new ToaRaidTracker();
        u.onCompletionMessage("Tombs of Amascut", 21, 0);
        u.onPoints(0);
        assertFalse(u.ready(0));
        u.onPoints(15_000);
        assertTrue(u.ready(0));
    }

    @Test
    void ignoresOtherKillCounts()
    {
        ToaRaidTracker t = new ToaRaidTracker();
        t.onCompletionMessage("Zulrah", 100, 0);
        t.onPoints(10_000);
        assertFalse(t.ready(0));
    }

    @Test
    void aStaleMessageExpires()
    {
        ToaRaidTracker t = new ToaRaidTracker();
        t.onCompletionMessage("Tombs of Amascut", 21, 0);
        assertFalse(t.ready(16 * 60_000));
        t.onPoints(10_000);
        assertFalse(t.ready(16 * 60_000));
    }

    @Test
    void buildsTheRecordAndResets()
    {
        ToaRaidTracker t = new ToaRaidTracker();
        t.onCompletionMessage("Tombs of Amascut", 21, 0);
        t.onPoints(18_250);
        // The normal counter hasn't caught up yet: the message's 21 wins.
        RaidRecord r = t.build(counters(5, 20, 0), 225, 1, 0, NO_CHESTS, NOW);
        assertEquals("Tombs of Amascut", r.raid);
        assertEquals("normal", r.mode);
        assertEquals(counters(5, 21, 0), r.modeCounts);
        assertEquals(26, r.totalCount());
        assertEquals(Integer.valueOf(1), r.teamSize);
        assertEquals(Integer.valueOf(18_250), r.personalPoints);
        assertEquals(225, r.details.get("raid_level"));
        assertEquals(Boolean.FALSE, r.purple);
        assertEquals(Boolean.FALSE, r.purpleMine);
        assertEquals("2026-11-11T22:00:00Z", r.completedAt);
        assertFalse(t.ready(0));
    }

    @Test
    void readsWhoseThePurpleIs()
    {
        ToaRaidTracker mine = new ToaRaidTracker();
        mine.onCompletionMessage("Tombs of Amascut: Expert Mode", 1, 0);
        mine.onPoints(30_000);
        RaidRecord r = mine.build(counters(0, 0, 0), 400, 2, 3, NO_CHESTS, NOW);
        assertEquals(Boolean.TRUE, r.purple);
        assertEquals(Boolean.TRUE, r.purpleMine);
        assertEquals(counters(0, 0, 1), r.modeCounts);

        ToaRaidTracker theirs = new ToaRaidTracker();
        theirs.onCompletionMessage("Tombs of Amascut: Expert Mode", 2, 0);
        theirs.onPoints(30_000);
        int[] chests = new int[8];
        chests[1] = ToaRaidTracker.CHEST_HOLDS_PURPLE;
        RaidRecord s = theirs.build(counters(0, 0, 2), 400, 2, 1, chests, NOW);
        assertEquals(Boolean.TRUE, s.purple);
        assertEquals(Boolean.FALSE, s.purpleMine);
    }

    @Test
    void anUnreadableTeamSizeIsLeftOut()
    {
        ToaRaidTracker t = new ToaRaidTracker();
        t.onCompletionMessage("Tombs of Amascut", 1, 0);
        t.onPoints(5_000);
        assertNull(t.build(counters(0, 1, 0), 150, 0, 0, NO_CHESTS, NOW).teamSize);
    }
}
