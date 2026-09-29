package com.osrslucktracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lucktracker")
// Backend settings are hidden: players never need to change them.
public interface LuckTrackerConfig extends Config
{
    String SHOW_PROFILE_KEY = "showProfile";
    String SHOW_ON_LEADERBOARD_KEY = "showOnLeaderboard";

    @ConfigItem(
        keyName = SHOW_PROFILE_KEY,
        name = "Show my log on the website",
        description = "When off, nobody can look up your collection log luck on the website, and you're left off the "
            + "leaderboard. Drops are still recorded, so turning it back on shows everything.",
        position = 1
    )
    default boolean showProfile()
    {
        return true;
    }

    @ConfigItem(
        keyName = SHOW_ON_LEADERBOARD_KEY,
        name = "Show me on the leaderboard",
        description = "List you on the website's luckiest and driest player leaderboard. "
            + "Needs 'Show my log on the website' on.",
        position = 2
    )
    default boolean showOnLeaderboard()
    {
        return false;
    }

    @ConfigItem(
        keyName = "apiBaseUrl",
        name = "API base URL",
        description = "Luck Tracker backend URL. Leave as-is unless you run your own backend.",
        hidden = true
    )
    default String apiBaseUrl()
    {
        return "https://hecceszsdjpglpcumwec.supabase.co/functions/v1";
    }

    @ConfigItem(
        keyName = "publishableKey",
        name = "Publishable API key",
        description = "Supabase publishable key for the backend (sb_publishable_...). Never enter a secret key here.",
        hidden = true
    )
    default String publishableKey()
    {
        return "sb_publishable_75fG7nzkqSMRUdI6WX_Yfw_WYurXaQ5";
    }
}
