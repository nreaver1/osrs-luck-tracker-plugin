package com.osrslucktracker;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * The slice of /get-player-luck the plugin needs: which drops are already
 * recorded, and each one's luck for the collection log check message.
 * Mirrors LuckResult in _shared/types.ts.
 */
class PlayerLuckResponse
{
    List<Result> results;

    // Items still being hunted (migration 0008); null from an older backend.
    List<Hunting> hunting;

    static PlayerLuckResponse empty()
    {
        PlayerLuckResponse r = new PlayerLuckResponse();
        r.results = new ArrayList<>();
        r.hunting = new ArrayList<>();
        return r;
    }

    static class Hunting
    {
        @SerializedName("item_id")
        int itemId;

        @SerializedName("source_name")
        String sourceName;

        int kc;
    }

    static class Result
    {
        @SerializedName("item_id")
        int itemId;

        @SerializedName("source_name")
        String sourceName;

        // Null for backfilled drops.
        @SerializedName("kc_received")
        Integer kcReceived;

        // "spooned", "average", "dry" or "desert"; only meaningful when
        // supported and not backfilled.
        String label;

        boolean estimated;

        boolean supported;

        boolean backfilled;

        // Backfilled flat-rate items with a KC snapshot only (SnapshotLuck).
        Snapshot snapshot;
    }

    static class Snapshot
    {
        int kc;

        int quantity;

        double probability;

        String label;
    }
}
