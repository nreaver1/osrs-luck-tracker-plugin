package com.osrslucktracker;

import com.google.gson.annotations.SerializedName;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * One raid completion, as /record-raid takes it (and ingest-drop, attached
 * to a drop from that raid). Mirrors RaidRecordInput in the backend's
 * _shared/raids.ts.
 */
class RaidRecord
{
    final String raid;

    final String mode;

    /** Every mode's completion count right after this raid. */
    @SerializedName("mode_counts")
    final Map<String, Integer> modeCounts;

    @SerializedName("team_size")
    final Integer teamSize;

    /** The player's own points, without the 5,000 every raid starts with. */
    @SerializedName("personal_points")
    final Integer personalPoints;

    final Boolean purple;

    @SerializedName("purple_mine")
    final Boolean purpleMine;

    final Map<String, Object> details;

    @SerializedName("completed_at")
    final String completedAt;

    RaidRecord(String raid, String mode, Map<String, Integer> modeCounts, Integer teamSize, Integer personalPoints,
        Boolean purple, Boolean purpleMine, Map<String, Object> details, String completedAt)
    {
        this.raid = raid;
        this.mode = mode;
        this.modeCounts = Collections.unmodifiableMap(new TreeMap<>(modeCounts));
        this.teamSize = teamSize;
        this.personalPoints = personalPoints;
        this.purple = purple;
        this.purpleMine = purpleMine;
        this.details = Collections.unmodifiableMap(new TreeMap<>(details));
        this.completedAt = completedAt;
    }

    /** Every mode's completions added up: the raid's log page count, and a raid drop's KC. */
    int totalCount()
    {
        return modeCounts.values().stream().mapToInt(Integer::intValue).sum();
    }
}
