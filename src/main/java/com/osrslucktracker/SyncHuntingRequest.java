package com.osrslucktracker;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Map;

/** Body of POST /sync-hunting; mirrors sync-hunting/index.ts. */
class SyncHuntingRequest
{
    @SerializedName("install_token")
    final String installToken;

    @SerializedName("account_hash")
    final String accountHash;

    final List<Item> hunting;

    final List<Pair> obtained;

    final List<Page> pages;

    SyncHuntingRequest(String installToken, String accountHash, List<Item> hunting, List<Pair> obtained,
        List<Page> pages)
    {
        this.installToken = installToken;
        this.accountHash = accountHash;
        this.hunting = hunting;
        this.obtained = obtained;
        this.pages = pages;
    }

    /** One whole log page read (migration 0009). */
    static class Page
    {
        @SerializedName("source_name")
        final String sourceName;

        final int kc;

        final List<Integer> obtained;

        // Item id -> quantity, obtained non-stackable items only.
        final Map<String, Integer> quantities;

        Page(String sourceName, int kc, List<Integer> obtained, Map<String, Integer> quantities)
        {
            this.sourceName = sourceName;
            this.kc = kc;
            this.obtained = obtained;
            this.quantities = quantities;
        }
    }

    static class Pair
    {
        @SerializedName("item_id")
        final int itemId;

        @SerializedName("source_name")
        final String sourceName;

        Pair(int itemId, String sourceName)
        {
            this.itemId = itemId;
            this.sourceName = sourceName;
        }
    }

    static class Item extends Pair
    {
        final int kc;

        Item(int itemId, String sourceName, int kc)
        {
            super(itemId, sourceName);
            this.kc = kc;
        }
    }
}

class SyncHuntingResponse
{
    int upserted;

    int removed;

    int pages;
}
