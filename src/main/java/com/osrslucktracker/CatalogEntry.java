package com.osrslucktracker;

import com.google.gson.annotations.SerializedName;

import java.util.List;

class CatalogEntry
{
    @SerializedName("item_id")
    int itemId;

    @SerializedName("source_name")
    String sourceName;
}

class CatalogResponse
{
    List<CatalogEntry> entries;
}
