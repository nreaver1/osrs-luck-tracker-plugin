package com.osrslucktracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lucktracker")
public interface LuckTrackerConfig extends Config
{
    @ConfigItem(
        keyName = "apiBaseUrl",
        name = "API base URL",
        description = "Luck Tracker backend URL. Leave as-is unless you run your own backend."
    )
    default String apiBaseUrl()
    {
        return "https://hecceszsdjpglpcumwec.supabase.co/functions/v1";
    }

    @ConfigItem(
        keyName = "publishableKey",
        name = "Publishable API key",
        description = "Supabase publishable key for the backend (sb_publishable_...). Never enter a secret key here."
    )
    default String publishableKey()
    {
        return "sb_publishable_75fG7nzkqSMRUdI6WX_Yfw_WYurXaQ5";
    }
}
