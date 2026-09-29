package com.osrslucktracker;

import com.google.gson.annotations.SerializedName;

/** Body of /update-settings; mirrors supabase/functions/update-settings. */
class UpdateSettingsRequest
{
    @SerializedName("install_token")
    final String installToken;

    @SerializedName("account_hash")
    final String accountHash;

    @SerializedName("profile_public")
    final boolean profilePublic;

    @SerializedName("leaderboard_opt_in")
    final boolean leaderboardOptIn;

    UpdateSettingsRequest(String installToken, String accountHash, boolean profilePublic, boolean leaderboardOptIn)
    {
        this.installToken = installToken;
        this.accountHash = accountHash;
        this.profilePublic = profilePublic;
        this.leaderboardOptIn = leaderboardOptIn;
    }
}
