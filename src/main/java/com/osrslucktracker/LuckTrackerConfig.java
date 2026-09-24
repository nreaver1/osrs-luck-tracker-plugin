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
        description = "Your deployed backend's base URL, e.g. https://<ref>.supabase.co/functions/v1"
    )
    default String apiBaseUrl()
    {
        return "";
    }

    @ConfigItem(
        keyName = "publishableKey",
        name = "Publishable API key",
        description = "Your Supabase publishable key (sb_publishable_...). Never enter a secret key here."
    )
    default String publishableKey()
    {
        return "";
    }
}
