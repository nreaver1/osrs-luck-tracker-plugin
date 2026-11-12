package com.osrslucktracker;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds Chambers of Xeric and Theatre of Blood completions out of what the
 * client knows at the end of a raid. (Tombs of Amascut has its own
 * {@link ToaRaidTracker}, since its points arrive separately.) Kept free of
 * client calls so it can be unit tested: the plugin feeds it the
 * completion-count message, waits a few ticks, then hands it what it reads.
 *
 * <ul>
 * <li>The completion message ("Your completed Theatre of Blood: Hard Mode
 * count is: 4.") names the raid, the mode and its new count.</li>
 * <li>The other modes' counts come from the client's own counters
 * (TOTAL_COMPLETED_XERICCHAMBERS and friends).</li>
 * <li>CoX: the player's and the team's points (RAIDS_PLAYERSCORE and
 * RAIDS_CLIENT_PARTYSCORE) and the party size, while still in the raid.</li>
 * <li>ToB: the team size from the party slot varbits. Its points never
 * reach the client.</li>
 * </ul>
 *
 * Purples aren't detected yet: the raw CoX reward indicator and ToB chest
 * varbits are sent in {@code details} so that can be worked out from real
 * raids, and {@code purple} stays null.
 */
final class RaidCompletionTracker
{
    static final String COX = "Chambers of Xeric";
    static final String TOB = "Theatre of Blood";

    // Completion-count message source -> {raid page, mode}.
    private static final Map<String, String[]> MODES;

    static
    {
        Map<String, String[]> m = new LinkedHashMap<>();
        m.put("Chambers of Xeric", new String[]{COX, "normal"});
        m.put("Chambers of Xeric Challenge Mode", new String[]{COX, "challenge"});
        m.put("Theatre of Blood: Entry Mode", new String[]{TOB, "entry"});
        m.put("Theatre of Blood", new String[]{TOB, "normal"});
        m.put("Theatre of Blood: Hard Mode", new String[]{TOB, "hard"});
        MODES = Collections.unmodifiableMap(m);
    }

    // A completion nobody read within this long (a logout) is dropped.
    private static final long PENDING_TIMEOUT_MS = 2 * 60_000;

    private String raid;
    private String mode;
    private int modeKc;
    private long messageAtMs;

    /** {raid page, mode} for a completion-count source, or null if it isn't CoX or ToB. */
    static String[] raidAndMode(String killCountSource)
    {
        return killCountSource == null ? null : MODES.get(killCountSource);
    }

    void onCompletionMessage(String killCountSource, int kc, long nowMs)
    {
        String[] rm = raidAndMode(killCountSource);
        if (rm == null)
        {
            return;
        }
        raid = rm[0];
        mode = rm[1];
        modeKc = kc;
        messageAtMs = nowMs;
    }

    /** A completion is waiting to be read off the client. */
    boolean pending(long nowMs)
    {
        if (raid != null && nowMs - messageAtMs > PENDING_TIMEOUT_MS)
        {
            reset();
        }
        return raid != null;
    }

    String pendingRaid()
    {
        return raid;
    }

    String pendingMode()
    {
        return mode;
    }

    /**
     * The pending CoX completion. Zero points or party size (the varbits
     * already cleared) are sent as unknown.
     */
    RaidRecord buildCox(Map<String, Integer> counters, int partySize, int personalPoints, int teamPoints,
        int rewardIndicator, Instant now)
    {
        Map<String, Object> details = new HashMap<>();
        Integer personal = personalPoints > 0 ? Math.min(personalPoints, 5_000_000) : null;
        if (teamPoints > 0 && (personal == null || teamPoints >= personal))
        {
            details.put("total_points", Math.min(teamPoints, 20_000_000));
        }
        details.put("reward_indicator", Math.max(0, Math.min(rewardIndicator, 1023)));
        return build(COX, counters, partySize, personal, details, now);
    }

    /** The pending ToB completion. {@code partySlots} are the TOB_CLIENT_P0..P4 values. */
    RaidRecord buildTob(Map<String, Integer> counters, int[] partySlots, int[] chests, Instant now)
    {
        int teamSize = 0;
        for (int v : partySlots)
        {
            teamSize += v != 0 ? 1 : 0;
        }
        Map<String, Object> details = new HashMap<>();
        int[] clamped = new int[Math.min(chests.length, 5)];
        for (int i = 0; i < clamped.length; i++)
        {
            clamped[i] = Math.max(0, Math.min(chests[i], 1023));
        }
        details.put("chests", clamped);
        return build(TOB, counters, teamSize, null, details, now);
    }

    private RaidRecord build(String expectedRaid, Map<String, Integer> counters, int teamSize, Integer personal,
        Map<String, Object> details, Instant now)
    {
        if (!expectedRaid.equals(raid))
        {
            throw new IllegalStateException("No pending " + expectedRaid + " completion");
        }
        Map<String, Integer> counts = new TreeMap<>();
        for (String[] rm : MODES.values())
        {
            if (rm[0].equals(raid))
            {
                Integer c = counters.get(rm[1]);
                counts.put(rm[1], c == null || c < 0 ? 0 : c);
            }
        }
        // The message's count wins for its own mode, in case the counter hasn't caught up.
        counts.put(mode, Math.max(counts.get(mode), modeKc));

        RaidRecord record = new RaidRecord(raid, mode, counts, teamSize >= 1 && teamSize <= 8 ? teamSize : null,
            personal, null, null, details, now.toString());
        reset();
        return record;
    }

    void reset()
    {
        raid = null;
        mode = null;
        modeKc = 0;
        messageAtMs = 0;
    }
}
