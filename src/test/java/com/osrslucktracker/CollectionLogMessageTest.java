package com.osrslucktracker;

import net.runelite.api.ChatMessageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionLogMessageTest
{
    @Test
    void parsesItemNameWithColourTags()
    {
        assertEquals("Mole skin",
            CollectionLogMessage.parseItemName("New item added to your collection log: <col=ef1020>Mole skin</col>"));
    }

    @Test
    void normalisesNonBreakingSpaces()
    {
        assertEquals("Mole claw",
            CollectionLogMessage.parseItemName("New item added to your collection log: Mole claw"));
    }

    @Test
    void ignoresOtherMessages()
    {
        assertNull(CollectionLogMessage.parseItemName("Your Giant Mole kill count is: <col=ff0000>5</col>."));
        assertNull(CollectionLogMessage.parseItemName("New item added to your collection log: "));
    }

    @Test
    void parsesPopupItemName()
    {
        assertEquals("Hueycoatl hide",
            CollectionLogMessage.parsePopupItemName("Collection log", "New item:<br><col=ffffff>Hueycoatl hide</col>"));
    }

    @Test
    void ignoresOtherPopups()
    {
        assertNull(CollectionLogMessage.parsePopupItemName("Combat Task Completed!", "Task Completed: <col=ffffff>Hueycoatl Adept</col>"));
        assertNull(CollectionLogMessage.parsePopupItemName("Collection log", ""));
        assertNull(CollectionLogMessage.parsePopupItemName(null, null));
    }

    @Test
    void acceptsGameAndFilteredGameMessagesOnly()
    {
        assertTrue(CollectionLogMessage.isGameMessageType(ChatMessageType.GAMEMESSAGE));
        assertTrue(CollectionLogMessage.isGameMessageType(ChatMessageType.SPAM));
        assertFalse(CollectionLogMessage.isGameMessageType(ChatMessageType.PUBLICCHAT));
        assertFalse(CollectionLogMessage.isGameMessageType(ChatMessageType.CLAN_CHAT));
    }
}
