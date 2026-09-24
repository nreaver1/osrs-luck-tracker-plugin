package com.osrslucktracker;

import com.google.gson.annotations.SerializedName;

class BackfillDropRequest
{
    @SerializedName("install_token")
    final String installToken;

    @SerializedName("account_hash")
    final String accountHash;

    @SerializedName("item_id")
    final int itemId;

    @SerializedName("source_name")
    final String sourceName;

    BackfillDropRequest(String installToken, String accountHash, int itemId, String sourceName)
    {
        this.installToken = installToken;
        this.accountHash = accountHash;
        this.itemId = itemId;
        this.sourceName = sourceName;
    }
}
