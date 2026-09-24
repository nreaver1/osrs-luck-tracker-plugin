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

        Drop(int itemId, String sourceName)
        {
            this.itemId = itemId;
            this.sourceName = sourceName;
        }
    }
}

class BackfillBatchResponse
{
    int inserted;

    @SerializedName("already_recorded")
    int alreadyRecorded;
}
