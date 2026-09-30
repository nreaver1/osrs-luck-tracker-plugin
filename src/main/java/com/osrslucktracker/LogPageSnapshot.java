package com.osrslucktracker;

import net.runelite.client.util.Text;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    // "Barrows Chests: <col=ff0000>200</col>" once tags are stripped. The
    // label can't contain a colon, so "Personal Best: 0:02" isn't read as
    // a count of 2.
    private static final Pattern KC_LINE = Pattern.compile("^([^:]+):\\s*([0-9][0-9,]*)$");
    // The header's "Obtained: 12/24" line never matches KC_LINE (the slash),
    // but a future "Obtained: 12" shouldn't be read as a kill count either.
    private static final String OBTAINED_LABEL = "obtained";
    // "Obtained: <col=ffff00>3/12</col>" once tags are stripped.
    private static final Pattern OBTAINED_LINE = Pattern.compile("^Obtained:\\s*([0-9]+)\\s*/\\s*[0-9]+$",
        Pattern.CASE_INSENSITIVE);

    final int kc;
    /** Item id -> quantity shown, for obtained non-stackable items only. */
    final Map<Integer, Integer> quantities;
    /**
     * The header's own count of obtained slots, or null if it had none.
     * "Still hunting" rows are only sent when the slots read as obtained
     * add up to this, so a page drawn with slots still faded can't list
     * items the player has as missing.
     */
    final Integer obtainedShown;

    LogPageSnapshot(int kc, Map<Integer, Integer> quantities)
    {
        this(kc, quantities, null);
    }

    LogPageSnapshot(int kc, Map<Integer, Integer> quantities, Integer obtainedShown)
    {
        this.kc = kc;
        this.quantities = Collections.unmodifiableMap(new TreeMap<>(quantities));
        this.obtainedShown = obtainedShown;
    }

    /** The x in the header's "Obtained: x/y", or null if there's no such line. */
    static Integer parseObtainedCount(List<String> headerLines)
    {
        for (String line : headerLines)
        {
            if (line == null)
            {
                continue;
            }
            Matcher m = OBTAINED_LINE.matcher(Text.removeTags(line).replace('\u00a0', ' ').trim());
            if (m.matches())
            {
                try
                {
                    return Integer.parseInt(m.group(1));
                }
                catch (NumberFormatException e)
                {
                    return null;
                }
            }
        }
        return null;
    }

    // Pages whose catalog rates roll per something other than a kill, keyed
    // by page title, with the header counter that counts those rolls. Only
    // for pages where one roll per count holds: Tempoross's reward pool is
    // rated per reward permit. Wintertodt isn't here: a claimed reward cart
    // gives a points-dependent number of rolls, so neither counter fits.
    private static final Map<String, String> ROLL_COUNTERS = Collections.singletonMap(
        "Tempoross", "Reward permits claimed");

    /**
     * The page's roll count from its header lines (title excluded): the
     * counter {@link #ROLL_COUNTERS} names for the page, or else its only
     * count. Null when that counter is missing, or when a page not listed
     * there has several counters (Dagannoth Kings, The Gauntlet's normal
     * and corrupted) or none, since then it can't say which count an item
     * came from.
     */
    static Integer parseKillCount(String page, List<String> headerLines)
    {
        Map<String, Integer> counts = new HashMap<>();
        for (String line : headerLines)
        {
            if (line == null)
            {
                continue;
            }
            Matcher m = KC_LINE.matcher(Text.removeTags(line).replace('\u00a0', ' ').trim());
            String label = m.matches() ? m.group(1).trim().toLowerCase(Locale.ROOT) : null;
            if (label == null || label.equals(OBTAINED_LABEL))
            {
                continue;
            }
            try
            {
                if (counts.put(label, Integer.parseInt(m.group(2).replace(",", ""))) != null)
                {
                    return null; // the same label twice: not a layout we know
                }
            }
            catch (NumberFormatException e)
            {
                return null;
            }
        }

        String rollCounter = ROLL_COUNTERS.get(page);
        if (rollCounter != null)
        {
            return counts.get(rollCounter.toLowerCase(Locale.ROOT));
        }
        return counts.size() == 1 ? counts.values().iterator().next() : null;
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
        Integer shown = obtainedShown == null ? other.obtainedShown
            : other.obtainedShown == null ? obtainedShown
            : Integer.valueOf(Math.max(obtainedShown, other.obtainedShown));
        return new LogPageSnapshot(Math.max(kc, other.kc), merged, shown);
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
        return kc == that.kc && quantities.equals(that.quantities)
            && java.util.Objects.equals(obtainedShown, that.obtainedShown);
    }

    @Override
    public int hashCode()
    {
        return java.util.Objects.hash(kc, quantities, obtainedShown);
    }
}
