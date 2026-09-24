package com.osrslucktracker;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** The slice of /get-player-luck the plugin needs: which drops are already recorded. */
class PlayerLuckResponse
{
    List<Result> results;

    static class Result
    {
        @SerializedName("item_id")
        int itemId;

        @SerializedName("source_name")
        String sourceName;
    }
}
