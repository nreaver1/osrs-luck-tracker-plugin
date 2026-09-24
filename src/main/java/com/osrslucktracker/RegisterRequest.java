package com.osrslucktracker;

import com.google.gson.annotations.SerializedName;

class RegisterRequest
{
    @SerializedName("account_hash")
    final String accountHash;

    final String ign;

    RegisterRequest(String accountHash, String ign)
    {
        this.accountHash = accountHash;
        this.ign = ign;
    }
}
