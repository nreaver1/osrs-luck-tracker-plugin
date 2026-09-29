package com.osrslucktracker;

import com.google.gson.annotations.SerializedName;

/** Body of POST /get-player-luck: an account reading its own drops, hidden profile or not. */
class OwnLuckRequest
{
    @SerializedName("install_token")
    final String installToken;

    @SerializedName("account_hash")
    final String accountHash;

    OwnLuckRequest(String installToken, String accountHash)
    {
        this.installToken = installToken;
        this.accountHash = accountHash;
    }
}
