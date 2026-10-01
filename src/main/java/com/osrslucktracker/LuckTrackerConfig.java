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
        description = "Let anyone look up your collection log luck on the Clog Casino website. When off, your log "
            + "is private and you're left off the leaderboard. Drops are still recorded, so turning it on shows "
            + "everything.",
        warning = "This changes whether anyone can look up your collection log luck on the Clog Casino website, "
            + "a 3rd-party site not controlled or verified by the RuneLite developers.",
        position = 1
    )
    default boolean showProfile()
    {
        return false;
    }

    @ConfigItem(
        keyName = SHOW_ON_LEADERBOARD_KEY,
        name = "Show me on the leaderboard",
        description = "List you on the Clog Casino website's luckiest and driest player leaderboard. "
            + "Needs 'Show my log on the website' on.",
        warning = "This changes whether you're listed on the Clog Casino leaderboard, "
            + "a 3rd-party site not controlled or verified by the RuneLite developers.",
        position = 2
    )
    default boolean showOnLeaderboard()
    {
        return false;
    }

    @ConfigItem(
        keyName = "apiBaseUrl",
        name = "API base URL",
        description = "Clog Casino backend URL. Leave as-is unless you run your own backend.",
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
