package com.osrslucktracker;

import com.google.gson.annotations.SerializedName;

class RecordRaidRequest
{
    @SerializedName("install_token")
    final String installToken;

    @SerializedName("account_hash")
    final String accountHash;

    final RaidRecord raid;

    RecordRaidRequest(String installToken, String accountHash, RaidRecord raid)
    {
        this.installToken = installToken;
        this.accountHash = accountHash;
        this.raid = raid;
    }
}
