package com.osrslucktracker;

import com.google.gson.annotations.SerializedName;

class IngestDropRequest
{
    @SerializedName("install_token")
    final String installToken;

    @SerializedName("account_hash")
    final String accountHash;

    @SerializedName("item_id")
    final int itemId;

    @SerializedName("source_name")
    final String sourceName;

    @SerializedName("kc_received")
    final int kcReceived;

    @SerializedName("current_kc")
    final int currentKc;

    /** A raid drop's completion, or null. The backend then stores the drop at the raid's total count. */
    final RaidRecord raid;

    IngestDropRequest(String installToken, String accountHash, int itemId, String sourceName, int kcReceived, int currentKc,
        RaidRecord raid)
    {
        this.installToken = installToken;
        this.accountHash = accountHash;
        this.itemId = itemId;
        this.sourceName = sourceName;
        this.kcReceived = kcReceived;
        this.currentKc = currentKc;
        this.raid = raid;
    }
}
