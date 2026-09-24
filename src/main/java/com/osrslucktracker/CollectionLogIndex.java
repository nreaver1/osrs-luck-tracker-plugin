package com.osrslucktracker;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.StructComposition;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which collection log pages every item appears on, read from the game
 * cache (the same structs/enums the game's own collection log scripts
 * draw from).
 *
 * This is what makes the import safe for shared items. The collection
 * log fills a slot on EVERY page that lists the item, no matter which
 * source actually dropped it — a Godsword shard from Kree'arra also
 * shows as obtained on the Graardor, K'ril and Zilyana pages. So an
 * obtained slot only proves the source when the item appears on exactly
 * one page, and we need the full page list (not just the pages the
 * player happened to open) to know that.
 *
 * The IDs below are not exposed by RuneLite; they match what the
 * Collection Log plugin-hub plugin (evansloan / osrsclog) uses. If a
 * game update moves them, {@link #build} returns null and the import
 * refuses to run rather than guessing.
 */
@Slf4j
final class CollectionLogIndex
{
    // Bosses, Raids, Clues, Minigames, Other
    private static final int[] TAB_STRUCT_IDS = {471, 472, 473, 474, 475};
    private static final int TAB_PAGES_ENUM_PARAM = 683;
    private static final int PAGE_NAME_PARAM = 689;
    private static final int PAGE_ITEMS_ENUM_PARAM = 690;

    private final Map<Integer, Set<String>> pagesByItem;
    private final Map<String, Set<Integer>> itemsByPage;

    CollectionLogIndex(Map<String, Set<Integer>> itemsByPage)
    {
        Map<Integer, Set<String>> byItem = new HashMap<>();
        Map<String, Set<Integer>> byPage = new HashMap<>();
        for (Map.Entry<String, Set<Integer>> page : itemsByPage.entrySet())
        {
            byPage.put(page.getKey(), Collections.unmodifiableSet(new HashSet<>(page.getValue())));
            for (int itemId : page.getValue())
            {
                byItem.computeIfAbsent(itemId, k -> new HashSet<>()).add(page.getKey());
            }
        }
        this.pagesByItem = byItem;
        this.itemsByPage = byPage;
    }

    /**
     * Must run on the client thread. Returns null if the cache layout
     * doesn't look like a collection log any more.
     */
    static CollectionLogIndex build(Client client)
    {
        Map<String, Set<Integer>> itemsByPage = new HashMap<>();
        try
        {
            for (int tabStructId : TAB_STRUCT_IDS)
            {
                StructComposition tab = client.getStructComposition(tabStructId);
                EnumComposition pages = tab == null ? null : client.getEnum(tab.getIntValue(TAB_PAGES_ENUM_PARAM));
                if (pages == null)
                {
                    log.warn("Collection log tab struct {} not found in cache", tabStructId);
                    return null;
                }

                for (int pageStructId : pages.getIntVals())
                {
                    StructComposition page = client.getStructComposition(pageStructId);
                    if (page == null)
                    {
                        return null;
                    }
                    String name = page.getStringValue(PAGE_NAME_PARAM);
                    EnumComposition items = client.getEnum(page.getIntValue(PAGE_ITEMS_ENUM_PARAM));
                    if (name == null || name.isEmpty() || items == null)
                    {
                        return null;
                    }

                    Set<Integer> ids = itemsByPage.computeIfAbsent(name, k -> new HashSet<>());
                    for (int itemId : items.getIntVals())
                    {
                        ids.add(itemId);
                    }
                }
            }
        }
        catch (RuntimeException e)
        {
            log.warn("Failed to read collection log layout from cache", e);
            return null;
        }

        // A real log has well over 100 pages; a handful means the IDs now
        // point at something else.
        if (itemsByPage.size() < 50)
        {
            log.warn("Collection log layout looks wrong ({} pages) — import disabled", itemsByPage.size());
            return null;
        }
        return new CollectionLogIndex(itemsByPage);
    }

    boolean hasPage(String pageName)
    {
        return itemsByPage.containsKey(pageName);
    }

    /** Every page the item appears on; empty if the item isn't in the log. */
    Set<String> pagesFor(int itemId)
    {
        return pagesByItem.getOrDefault(itemId, Collections.emptySet());
    }

    Set<String> pageNames()
    {
        return itemsByPage.keySet();
    }

    Set<Integer> itemsOn(String pageName)
    {
        return itemsByPage.getOrDefault(pageName, Collections.emptySet());
    }
}
