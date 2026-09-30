package com.osrslucktracker;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** Body of POST /update-kc; mirrors update-kc/index.ts. */
class UpdateKcRequest
{
    @SerializedName("install_token")
    final String installToken;

    @SerializedName("account_hash")
    final String accountHash;

    final List<Count> counts;

    UpdateKcRequest(String installToken, String accountHash, List<Count> counts)
    {
        this.installToken = installToken;
        this.accountHash = accountHash;
        this.counts = counts;
    }

    static class Count
    {
        // The boss as the kill-count message prints it; the backend maps it to a page.
        @SerializedName("source_name")
        final String sourceName;

        final int kc;

        Count(String sourceName, int kc)
        {
            this.sourceName = sourceName;
            this.kc = kc;
        }
    }
}
