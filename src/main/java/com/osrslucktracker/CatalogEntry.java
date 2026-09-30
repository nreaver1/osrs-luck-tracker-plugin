package com.osrslucktracker;

import com.google.gson.annotations.SerializedName;

import java.util.List;

class CatalogEntry
{
    @SerializedName("item_id")
    int itemId;

    @SerializedName("source_name")
    String sourceName;

    // "flat_geometric", "points_based", ...; null from a backend older than KC snapshots.
    @SerializedName("distribution_type")
    String distributionType;
}

class CatalogResponse
{
    List<CatalogEntry> entries;
}
