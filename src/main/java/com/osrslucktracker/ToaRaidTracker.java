package com.osrslucktracker;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds a Tombs of Amascut completion out of what the client learns at the
 * end of a raid. Kept free of client calls so it can be unit tested: the
 * plugin feeds it the completion-count message and the points varp, then
 * hands it the varbits it reads once both have arrived.
 *
 * <ul>
 * <li>The completion message ("Your completed Tombs of Amascut: Expert Mode
 * count is: 12.") names the mode and its new count.</li>
 * <li>TOA_PERSONAL_CONTRIBUTION holds the player's points without the
 * starting 5,000, and the game only sends it at the end of the raid.</li>
 * <li>The other modes' counts come from the client's own counters
 * (TOTAL_COMPLETED_TOMBSOFAMASCUT_ENTRY and friends).</li>
 * </ul>
 *
 * Purple detection reads TOA_VAULT_SARCOPHAGUS (odd when a unique dropped)
 * and the team's vault chest varbits, where 2 marks the chest of the
 * teammate who got it, the same reads LlemonDuck's Tombs of Amascut plugin
 * uses.
 */
final class ToaRaidTracker
{
    static final String RAID = "Tombs of Amascut";
    static final String ENTRY = "entry";
    static final String NORMAL = "normal";
    static final String EXPERT = "expert";

    // A completion message with no points after this long (a wipe, a
    // logout) is dropped, so it can't pair with a later raid's points.
    private static final long PENDING_TIMEOUT_MS = 15 * 60_000;
    // Value of a teammate's vault chest varbit when the purple is theirs.
    static final int CHEST_HOLDS_PURPLE = 2;

    private String mode;
    private int modeKc;
    private long messageAtMs;
    private Integer points;

    /** The mode a completion-count source names, or null if it isn't ToA. */
    static String modeFor(String killCountSource)
    {
        if (killCountSource == null)
        {
            return null;
        }
        switch (killCountSource)
        {
            case "Tombs of Amascut":
                return NORMAL;
            case "Tombs of Amascut: Entry Mode":
                return ENTRY;
            case "Tombs of Amascut: Expert Mode":
                return EXPERT;
            default:
                return null;
        }
    }

    void onCompletionMessage(String killCountSource, int kc, long nowMs)
    {
        String m = modeFor(killCountSource);
        if (m == null)
        {
            return;
        }
        mode = m;
        modeKc = kc;
        messageAtMs = nowMs;
    }

    void onPoints(int value)
    {
        if (value > 0)
        {
            points = value;
        }
    }

    /** Both halves have arrived, so the raid can be recorded. */
    boolean ready(long nowMs)
    {
        if (mode != null && nowMs - messageAtMs > PENDING_TIMEOUT_MS)
        {
            reset();
        }
        return mode != null && points != null;
    }

    String pendingMode()
    {
        return mode;
    }

    /**
     * The completion, once {@link #ready}. {@code counters} are the client's
     * per-mode completion counters; the message's count wins for its own
     * mode, in case the counter hasn't caught up yet.
     *
     * @param chestVarbits the team's vault chest varbit values
     */
    RaidRecord build(Map<String, Integer> counters, int raidLevel, int teamSize, int sarcophagus,
        int[] chestVarbits, Instant now)
    {
        Map<String, Integer> counts = new TreeMap<>();
        for (String m : new String[]{ENTRY, NORMAL, EXPERT})
        {
            Integer c = counters.get(m);
            counts.put(m, c == null || c < 0 ? 0 : c);
        }
        counts.put(mode, Math.max(counts.get(mode), modeKc));

        boolean purple = sarcophagus % 2 != 0;
        boolean someoneElse = false;
        for (int v : chestVarbits)
        {
            if (v == CHEST_HOLDS_PURPLE)
            {
                someoneElse = true;
            }
        }

        Map<String, Object> details = new HashMap<>();
        details.put("raid_level", raidLevel);
        RaidRecord record = new RaidRecord(RAID, mode, counts, teamSize >= 1 && teamSize <= 8 ? teamSize : null,
            Math.min(points, 64_000), purple, purple && !someoneElse, details, now.toString());
        reset();
        return record;
    }

    void reset()
    {
        mode = null;
        modeKc = 0;
        messageAtMs = 0;
        points = null;
    }
}
