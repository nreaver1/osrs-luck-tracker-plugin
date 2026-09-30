package com.osrslucktracker;

import net.runelite.client.util.Text;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A collection log page's kill count and item quantities as the player
 * last saw them, for the KC snapshot sent with an import. The backend
 * rates "quantity copies after kc kills" for flat-rate items (migration
 * 0007), which only works when both numbers come from the log itself, so
 * anything ambiguous leaves the snapshot out rather than guessing.
 */
final class LogPageSnapshot
{
    // "Barrows Chests: <col=ff0000>200</col>" once tags are stripped.
    private static final Pattern KC_LINE = Pattern.compile("^(.+?):\\s*([0-9][0-9,]*)$");
    // The header's "Obtained: 12/24" line never matches KC_LINE (the slash),
    // but a future "Obtained: 12" shouldn't be read as a kill count either.
    private static final String OBTAINED_LABEL = "obtained";

    final int kc;
    /** Item id -> quantity shown, for obtained non-stackable items only. */
    final Map<Integer, Integer> quantities;

    LogPageSnapshot(int kc, Map<Integer, Integer> quantities)
    {
        this.kc = kc;
        this.quantities = Collections.unmodifiableMap(new TreeMap<>(quantities));
    }

    /**
     * The page's kill count from its header lines (title excluded), or
     * null unless exactly one line is a count. Pages with several counters
     * (Dagannoth Kings, The Gauntlet's normal and corrupted) or none can't
     * say which kills an item came from.
     */
    static Integer parseKillCount(List<String> headerLines)
    {
        Integer found = null;
        for (String line : headerLines)
        {
            if (line == null)
            {
                continue;
            }
            Matcher m = KC_LINE.matcher(Text.removeTags(line).replace(' ', ' ').trim());
            if (!m.matches() || m.group(1).trim().equalsIgnoreCase(OBTAINED_LABEL))
            {
                continue;
            }
            if (found != null)
            {
                return null;
            }
            try
            {
                found = Integer.parseInt(m.group(2).replace(",", ""));
            }
            catch (NumberFormatException e)
            {
                return null;
            }
        }
        return found;
    }

    /**
     * Combines two reads of the same page. The log only ever grows, so the
     * larger of each number is the newer one; a read that caught a slot
     * still faded (see readCollectionLogPage) doesn't lower it.
     */
    LogPageSnapshot merge(LogPageSnapshot other)
    {
        if (other == null)
        {
            return this;
        }
        Map<Integer, Integer> merged = new HashMap<>(quantities);
        other.quantities.forEach((id, q) -> merged.merge(id, q, Math::max));
        return new LogPageSnapshot(Math.max(kc, other.kc), merged);
    }

    /** Quantity for the item, or null if it wasn't captured (unobtained or stackable). */
    Integer quantityOf(int itemId)
    {
        return quantities.get(itemId);
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (!(o instanceof LogPageSnapshot)) return false;
        LogPageSnapshot that = (LogPageSnapshot) o;
        return kc == that.kc && quantities.equals(that.quantities);
    }

    @Override
    public int hashCode()
    {
        return 31 * kc + quantities.hashCode();
    }
}
