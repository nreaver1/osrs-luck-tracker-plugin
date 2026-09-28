package com.osrslucktracker;

import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.util.Text;

import java.awt.Color;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The game message printed when a player checks an item in their
 * collection log ("You have received 4x Soiled page."), and the line the
 * plugin adds under it with that drop's recorded KC and luck, e.g.
 * "(icon) Soiled page: first obtained at 49 KC - dry." with the KC and
 * label in the luck color.
 */
final class LuckCheckMessage
{
    private static final Pattern CHECK_PATTERN = Pattern.compile("^You have received (?:[\\d,]+ ?x )?(.+?)\\.?$");

    // Readable on both the opaque (parchment) and transparent (dark) chatbox.
    static final Color SPOONED_COLOR = new Color(0x00A000);
    static final Color AVERAGE_COLOR = new Color(0xE67E00);
    static final Color DRY_COLOR = new Color(0xE01E1E);

    private LuckCheckMessage()
    {
    }

    /** The checked item's name, or null if this isn't a collection log check message. */
    static String parseCheckedItemName(String message)
    {
        String plain = Text.removeTags(message).replace(' ', ' ').trim();
        Matcher matcher = CHECK_PATTERN.matcher(plain);
        if (!matcher.find())
        {
            return null;
        }
        String item = matcher.group(1).trim();
        return item.isEmpty() ? null : item;
    }

    /**
     * The chat line for one recorded drop, or null if there's nothing to
     * say. Backfilled drops have no KC, and drops without a supported
     * distribution have no luck label; neither is ever given one.
     */
    static String format(PlayerLuckResponse.Result result, String itemName, boolean includeSource, int iconIndex)
    {
        ChatMessageBuilder message = new ChatMessageBuilder();
        if (iconIndex >= 0)
        {
            message.img(iconIndex).append(" ");
        }
        message.append(itemName + ": ");
        String from = includeSource ? " from " + result.sourceName : "";

        if (result.backfilled)
        {
            return message.append("logged before tracking started" + from + ", KC unknown.").build();
        }
        if (result.kcReceived == null)
        {
            return null;
        }

        String kc = result.kcReceived + " KC";
        String labelText = result.supported ? labelText(result.label) : null;
        if (labelText == null)
        {
            return message.append("first obtained at " + kc + from + " (no luck rating for this drop).").build();
        }
        Color color = labelColor(result.label);
        message.append("first obtained at ").append(color, kc).append(from + " - ").append(color, labelText);
        if (result.estimated)
        {
            message.append(" (estimated)");
        }
        return message.append(".").build();
    }

    private static String labelText(String label)
    {
        if (label == null)
        {
            return null;
        }
        switch (label)
        {
            case "spooned":
                return "spooned";
            case "average":
                return "average";
            case "dry":
                return "dry";
            case "desert":
                return "bone dry";
            default:
                return null;
        }
    }

    private static Color labelColor(String label)
    {
        switch (label)
        {
            case "spooned":
                return SPOONED_COLOR;
            case "average":
                return AVERAGE_COLOR;
            default:
                return DRY_COLOR;
        }
    }
}
