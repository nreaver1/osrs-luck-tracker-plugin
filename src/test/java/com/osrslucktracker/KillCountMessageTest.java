package com.osrslucktracker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Messages copied from RuneLite's ChatCommandsPluginTest, which records
// them as the game sends them.
class KillCountMessageTest
{
    @Test
    void parsesBossKillCountWithColourTags()
    {
        KillCountMessage kc = KillCountMessage.parse("Your Kree'arra kill count is: <col=ff0000>4</col>.");
        assertEquals("Kree'arra", kc.source);
        assertEquals(4, kc.kc);
        assertFalse(kc.isRaid());
    }

    @Test
    void parsesCommasAndMissingFullStop()
    {
        KillCountMessage kc = KillCountMessage.parse("Your Nightmare kill count is: <col=ff0000>1,130</col>");
        assertEquals("Nightmare", kc.source);
        assertEquals(1130, kc.kc);
    }

    @Test
    void parsesRaidCompletionCounts()
    {
        KillCountMessage cox = KillCountMessage.parse("Your completed Chambers of Xeric count is: <col=ff0000>51</col>.");
        assertEquals("Chambers of Xeric", cox.source);
        assertEquals(51, cox.kc);
        assertTrue(cox.isRaid());

        KillCountMessage cm = KillCountMessage.parse(
            "Your completed Chambers of Xeric Challenge Mode count is: <col=ff0000>13</col>.");
        assertEquals("Chambers of Xeric Challenge Mode", cm.source);

        KillCountMessage tob = KillCountMessage.parse(
            "Your completed Theatre of Blood: Entry Mode count is: <col=ff0000>73</col>.");
        assertEquals("Theatre of Blood: Entry Mode", tob.source);
        assertTrue(tob.isRaid());
    }

    @Test
    void parsesCompletionAndEchoVariants()
    {
        assertEquals("Gauntlet",
            KillCountMessage.parse("Your Gauntlet completion count is: <col=ff0000>123</col>.").source);
        KillCountMessage echo = KillCountMessage.parse(
            "Your <col=6800bf>Kalphite Queen (Echo)</col> kill count is:<col=e00a19>1</col>");
        assertEquals("Kalphite Queen (Echo)", echo.source);
        assertEquals(1, echo.kc);
    }

    @Test
    void ignoresOtherMessages()
    {
        assertNull(KillCountMessage.parse("New item added to your collection log: Twisted bow"));
        assertNull(KillCountMessage.parse("Fight duration: <col=ff0000>1:02</col>. Personal best: 0:58"));
    }
}
