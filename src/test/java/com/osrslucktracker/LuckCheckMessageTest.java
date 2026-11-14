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
    void usesTheWebsitesTiersWithThePercentage()
    {
        // The Vorki case: the backend's coarse label says "spooned", the site says JACKPOT.
        assertEquals("Soiled page: first obtained at 49 KC - JACKPOT (5%).",
            Text.removeTags(format(result(49, 0.05, "spooned", true, false, false), false, -1)));
        assertEquals("Soiled page: first obtained at 49 KC - MEGA JACKPOT (0%).",
            Text.removeTags(format(result(49, 0.004, "spooned", true, false, false), false, -1)));
        assertEquals("Soiled page: first obtained at 49 KC - even money (50%).",
            Text.removeTags(format(result(49, 0.5, "average", true, false, false), false, -1)));
        assertEquals("Soiled page: first obtained at 49 KC - bone dry (97%).",
            Text.removeTags(format(result(49, 0.97, "dry", true, false, false), false, -1)));
        assertEquals("Soiled page: first obtained at 49 KC - desert (99.6%).",
            Text.removeTags(format(result(49, 0.996, "desert", true, false, false), false, -1)));
    }

    @Test
    void colorsTheKcAndTierByTone()
    {
        String desert = format(result(49, 0.996, "desert", true, false, false), false, -1);
        assertTrue(desert.contains("<col=b00000>49 KC"), desert);
        assertTrue(desert.contains("<col=b00000>desert"), desert);

        assertTrue(format(result(3, 0.05, "spooned", true, false, false), false, -1).contains("<col=006000>3 KC"));
        assertTrue(format(result(3, 0.3, "average", true, false, false), false, -1).contains("<col=006000>a little spooned"));
        assertTrue(format(result(20, 0.5, "average", true, false, false), false, -1).contains("<col=0000ff>even money"));
        assertTrue(format(result(20, 0.65, "average", true, false, false), false, -1).contains("<col=b00000>a little unlucky"));
    }

    @Test
    void usesLightColorsOnATransparentChatbox()
    {
        LuckCheckMessage.Palette light = LuckCheckMessage.Palette.TRANSPARENT;
        assertTrue(LuckCheckMessage.format(result(20, 0.5, "average", true, false, false), "Soiled page", false, -1, light)
            .contains("<col=7fb8ff>even money"));
        assertTrue(LuckCheckMessage.format(result(3, 0.05, "spooned", true, false, false), "Soiled page", false, -1, light)
            .contains("<col=4ce24c>3 KC"));
        assertTrue(LuckCheckMessage.format(result(49, 0.9, "dry", true, false, false), "Soiled page", false, -1, light)
            .contains("<col=ff5a5a>dry streak"));
    }

    @Test
    void startsWithThePluginIcon()
    {
        String line = format(result(49, 0.9, "dry", true, false, false), false, 7);
        assertTrue(line.startsWith("<img=7> Soiled page: "), line);
    }

    @Test
    void marksEstimatesAndNamesTheSource()
    {
        assertEquals("Soiled page: first obtained at 120 KC from Hueycoatl - dry streak (87%, estimated).",
            Text.removeTags(format(result(120, 0.87, "dry", true, true, false), true, -1)));
    }

    @Test
    void neverRatesUnsupportedOrBackfilledDrops()
    {
        String unsupported = format(result(49, 0.5, "average", false, false, false), false, -1);
        assertEquals("Soiled page: first obtained at 49 KC (no luck rating for this drop).", Text.removeTags(unsupported));
        assertFalse(unsupported.contains("<col="), unsupported);

        String noProbability = format(result(49, null, "average", true, false, false), false, -1);
        assertEquals("Soiled page: first obtained at 49 KC (no luck rating for this drop).", Text.removeTags(noProbability));

        String backfilled = format(result(null, null, "average", true, false, true), false, -1);
        assertEquals("Soiled page: logged before tracking started, KC unknown.", Text.removeTags(backfilled));
    }

    @Test
    void showsABackfilledDropsSnapshotAsAnEstimate()
    {
        PlayerLuckResponse.Result r = result(null, null, "average", false, false, true);
        r.snapshot = new PlayerLuckResponse.Snapshot();
        r.snapshot.kc = 1100;
        r.snapshot.quantity = 1;
        r.snapshot.probability = 0.864;
        r.snapshot.label = "dry";

        String line = format(r, false, -1);
        assertEquals("Soiled page: logged before tracking, 1 by 1,100 KC - dry streak (86%, estimated).",
            Text.removeTags(line));
        assertTrue(line.contains("<col=b00000>1 by 1,100 KC"), line);
    }

    /** On the opaque chatbox, the default look. */
    private static String format(PlayerLuckResponse.Result result, boolean includeSource, int iconIndex)
    {
        return LuckCheckMessage.format(result, "Soiled page", includeSource, iconIndex, LuckCheckMessage.Palette.OPAQUE);
    }

    private static PlayerLuckResponse.Result result(Integer kc, Double probability, String label, boolean supported,
        boolean estimated, boolean backfilled)
    {
        PlayerLuckResponse.Result r = new PlayerLuckResponse.Result();
        r.itemId = 30068;
        r.sourceName = "Hueycoatl";
        r.kcReceived = kc;
        r.probability = probability;
        r.label = label;
        r.supported = supported;
        r.estimated = estimated;
        r.backfilled = backfilled;
        return r;
    }
}
