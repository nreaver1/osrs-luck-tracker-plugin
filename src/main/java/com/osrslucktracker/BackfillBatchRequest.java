package com.osrslucktracker;

import com.google.gson.annotations.SerializedName;

import java.util.List;

class BackfillBatchRequest
{
    @SerializedName("install_token")
    final String installToken;

    @SerializedName("account_hash")
    final String accountHash;

    final List<Drop> drops;

    BackfillBatchRequest(String installToken, String accountHash, List<Drop> drops)
    {
        this.installToken = installToken;
        this.accountHash = accountHash;
        this.drops = drops;
    }

    static class Drop
    {
        @SerializedName("item_id")
        final int itemId;

        @SerializedName("source_name")
        final String sourceName;

        // The log page's KC and the item's quantity at read time; both or
        // neither, and left out of the JSON when null.
        @SerializedName("snapshot_kc")
        final Integer snapshotKc;

        @SerializedName("snapshot_quantity")
        final Integer snapshotQuantity;

        Drop(int itemId, String sourceName, Integer snapshotKc, Integer snapshotQuantity)
        {
            this.itemId = itemId;
            this.sourceName = sourceName;
            this.snapshotKc = snapshotKc;
            this.snapshotQuantity = snapshotQuantity;
        }
    }
}

class BackfillBatchResponse
{
    int inserted;

    @SerializedName("already_recorded")
    int alreadyRecorded;

    @SerializedName("snapshots_added")
    int snapshotsAdded;
}
