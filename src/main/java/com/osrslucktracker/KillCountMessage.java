package com.osrslucktracker;

import net.runelite.client.util.Text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed kill-count game message, e.g. "Your Zulrah kill count is: 127."
 * or "Your completed Tombs of Amascut: Expert Mode count is: 12.".
 */
final class KillCountMessage
{
    // Same shapes RuneLite's Chat Commands plugin accepts, matched after
    // stripping the <col> tags the game wraps around the number.
    private static final Pattern PATTERN = Pattern.compile(
        "^Your (?:completion count for |subdued |completed )?(.+?) "
            + "(?:(?:kill|harvest|lap|completion|success) )?(?:count )?is: ?([0-9,]+)");

    // Raid loot is claimed from a chest after the completion message, so a
    // raid's context has to outlive the few seconds a boss kill gets.
    private static final String[] RAID_PREFIXES = {
        "Chambers of Xeric", "Theatre of Blood", "Tombs of Amascut",
    };

    final String source;
    final int kc;

    private KillCountMessage(String source, int kc)
    {
        this.source = source;
        this.kc = kc;
    }

    /** Null if the message isn't a kill-count message. */
    static KillCountMessage parse(String message)
    {
        Matcher matcher = PATTERN.matcher(Text.removeTags(message));
        if (!matcher.find())
        {
            return null;
        }
        return new KillCountMessage(matcher.group(1), Integer.parseInt(matcher.group(2).replace(",", "")));
    }

    boolean isRaid()
    {
        for (String prefix : RAID_PREFIXES)
        {
            if (source.startsWith(prefix))
            {
                return true;
            }
        }
        return false;
    }
}
