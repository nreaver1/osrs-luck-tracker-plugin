package com.osrslucktracker;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** Body of POST /sync-hunting; mirrors sync-hunting/index.ts. */
class SyncHuntingRequest
{
    @SerializedName("install_token")
    final String installToken;

    @SerializedName("account_hash")
    final String accountHash;

    final List<Item> hunting;

    final List<Pair> obtained;

    SyncHuntingRequest(String installToken, String accountHash, List<Item> hunting, List<Pair> obtained)
    {
        this.installToken = installToken;
        this.accountHash = accountHash;
        this.hunting = hunting;
        this.obtained = obtained;
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
}
