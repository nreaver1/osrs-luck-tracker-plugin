package com.osrslucktracker;

import net.runelite.api.ChatMessageType;
import net.runelite.client.util.Text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The chat message the game prints when an item is added to the
 * collection log, e.g. "New item added to your collection log: Mole skin".
 */
final class CollectionLogMessage
{
    private static final Pattern PATTERN =
        Pattern.compile("New item added to your collection log: (.+)");

    private CollectionLogMessage()
    {
    }

    /**
     * The notification can arrive as a filterable (SPAM) game message as
     * well as a plain one. Player chat types are excluded so nobody can
     * fake a drop by typing the message.
     */
    static boolean isGameMessageType(ChatMessageType type)
    {
        return type == ChatMessageType.GAMEMESSAGE || type == ChatMessageType.SPAM;
    }

    /**
     * The item name, or null if the message isn't a collection log
     * notification. Colour tags and non-breaking spaces, which the game
     * uses in some messages, are normalised away first.
     */
    static String parseItemName(String message)
    {
        String plain = Text.removeTags(message).replace(' ', ' ');
        Matcher matcher = PATTERN.matcher(plain);
        if (!matcher.find())
        {
            return null;
        }
        String item = matcher.group(1).trim();
        return item.isEmpty() ? null : item;
    }

    /**
     * The item name from the collection log popup, whose title is
     * "Collection log" and whose body is "New item:<br><col=...>Item</col>",
     * or null if the popup is some other notification. Players who turn
     * the chat notification off only get this popup.
     */
    static String parsePopupItemName(String title, String body)
    {
        if (title == null || body == null || !Text.removeTags(title).trim().equalsIgnoreCase("Collection log"))
        {
            return null;
        }
        String plain = Text.removeTags(body).replace(' ', ' ').trim();
        String prefix = "New item:";
        if (!plain.startsWith(prefix))
        {
            return null;
        }
        String item = plain.substring(prefix.length()).trim();
        return item.isEmpty() ? null : item;
    }
}
