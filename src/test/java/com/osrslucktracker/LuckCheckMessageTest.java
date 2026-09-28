package com.osrslucktracker;

import net.runelite.client.util.Text;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuckCheckMessageTest
{
    @Test
    void parsesCheckedItemName()
    {
        assertEquals("Soiled page", LuckCheckMessage.parseCheckedItemName("You have received 4x Soiled page."));
        assertEquals("Tanzanite fang", LuckCheckMessage.parseCheckedItemName("You have received 1x <col=ff0000>Tanzanite fang</col>."));
        assertEquals("Dragon pickaxe", LuckCheckMessage.parseCheckedItemName("You have received 1,234x Dragon pickaxe."));
    }

    @Test
    void ignoresOtherMessages()
    {
        assertNull(LuckCheckMessage.parseCheckedItemName("New item added to your collection log: Soiled page"));
        assertNull(LuckCheckMessage.parseCheckedItemName("Your Hueycoatl kill count is: 49."));
    }

    @Test
    void colorsTheKcAndLuckLabel()
    {
        String line = format(result(49, "desert", true, false, false), false, -1);
        assertEquals("Soiled page: first obtained at 49 KC - bone dry.", Text.removeTags(line));
        assertTrue(line.contains("<col=e01e1e>49 KC"), line);
        assertTrue(line.contains("<col=e01e1e>bone dry"), line);

        assertTrue(format(result(3, "spooned", true, false, false), false, -1).contains("<col=00a000>3 KC"));
        assertTrue(format(result(20, "average", true, false, false), false, -1).contains("<col=e67e00>average"));
    }

    @Test
    void startsWithThePluginIcon()
    {
        String line = format(result(49, "dry", true, false, false), false, 7);
        assertTrue(line.startsWith("<img=7> Soiled page: "), line);
    }

    @Test
    void marksEstimatesAndNamesTheSource()
    {
        assertEquals("Soiled page: first obtained at 120 KC from Hueycoatl - dry (estimated).",
            Text.removeTags(format(result(120, "dry", true, true, false), true, -1)));
    }

    @Test
    void neverRatesUnsupportedOrBackfilledDrops()
    {
        String unsupported = format(result(49, "average", false, false, false), false, -1);
        assertEquals("Soiled page: first obtained at 49 KC (no luck rating for this drop).", Text.removeTags(unsupported));
        assertFalse(unsupported.contains("<col="), unsupported);

        String backfilled = format(result(null, "average", true, false, true), false, -1);
        assertEquals("Soiled page: logged before tracking started, KC unknown.", Text.removeTags(backfilled));
    }

    private static String format(PlayerLuckResponse.Result result, boolean includeSource, int iconIndex)
    {
        return LuckCheckMessage.format(result, "Soiled page", includeSource, iconIndex);
    }

    private static PlayerLuckResponse.Result result(Integer kc, String label, boolean supported, boolean estimated,
        boolean backfilled)
    {
        PlayerLuckResponse.Result r = new PlayerLuckResponse.Result();
        r.itemId = 30068;
        r.sourceName = "Hueycoatl";
        r.kcReceived = kc;
        r.label = label;
        r.supported = supported;
        r.estimated = estimated;
        r.backfilled = backfilled;
        return r;
    }
}
