package com.osrslucktracker;

import com.google.gson.annotations.SerializedName;

class RegisterRequest
{
    @SerializedName("account_hash")
    final String accountHash;

    final String ign;

    // Null for a first registration; Gson leaves it out of the body.
    @SerializedName("install_token")
    final String installToken;

    RegisterRequest(String accountHash, String ign, String installToken)
    {
        this.accountHash = accountHash;
        this.ign = ign;
        this.installToken = installToken;
    }
}
