package com.osrslucktracker;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The slice of /get-player-luck the plugin needs: which drops are already
 * recorded, and each one's luck for the collection log check message.
 * Mirrors LuckResult in _shared/types.ts.
 */
class PlayerLuckResponse
{
    List<Result> results;

    // Items still being hunted (migration 0008); null from an older backend.
    // Hides pooled and recorded pairs, so it's for display.
    List<Hunting> hunting;

    // Every stored hunting row, unfiltered; only in the token-checked
    // response, null from an older backend. What the plugin diffs against.
    @SerializedName("hunting_rows")
    List<Hunting> huntingRows;

    // The page reads the backend holds (migration 0009); only in the
    // token-checked POST response, null from an older backend.
    @SerializedName("log_pages")
    List<LogPage> logPages;

    static class LogPage
    {
        @SerializedName("source_name")
        String sourceName;

        int kc;

        List<Integer> obtained;

        Map<String, Integer> quantities;
    }

    static PlayerLuckResponse empty()
    {
        PlayerLuckResponse r = new PlayerLuckResponse();
        r.results = new ArrayList<>();
        r.hunting = new ArrayList<>();
        r.logPages = new ArrayList<>();
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

        // Backfilled rows only, in the token-checked response: whether a
        // snapshot is stored, even when none is shown (pooled pages hide
        // theirs). Null from a backend older than this field.
        @SerializedName("snapshot_stored")
        Boolean snapshotStored;

        /** True when the backend holds no KC snapshot for this backfilled row. */
        boolean needsSnapshot()
        {
            return backfilled && (snapshotStored != null ? !snapshotStored : snapshot == null);
        }
    }

    static class Snapshot
    {
        int kc;

        int quantity;

        double probability;

        String label;
    }
}
