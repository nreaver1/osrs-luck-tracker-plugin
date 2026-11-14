package com.osrslucktracker;

import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.util.Text;

import java.awt.Color;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The game message printed when a player checks an item in their
 * collection log ("You have received 4x Soiled page."), and the line the
 * plugin adds under it with that drop's recorded KC and luck, e.g.
 * "(icon) Soiled page: first obtained at 49 KC - dry streak (87%)." with
 * the KC and tier in the luck color. The colors follow the chatbox: dark ones on
 * the opaque parchment box, light ones on the transparent box.
 */
final class LuckCheckMessage
{
    private static final Pattern CHECK_PATTERN = Pattern.compile("^You have received (?:[\\d,]+ ?x )?(.+?)\\.?$");

    /** Luck colors for one chatbox style. */
    enum Palette
    {
        // The game's own highlight shades on parchment: dark green, the
        // blue of quest and system messages, dark red.
        OPAQUE(new Color(0x006000), new Color(0x0000FF), new Color(0xB00000)),
        // Light shades that hold up on the dark, see-through box.
        TRANSPARENT(new Color(0x4CE24C), new Color(0x7FB8FF), new Color(0xFF5A5A));

        final Color spooned;
        final Color average;
        final Color dry;

        Palette(Color spooned, Color average, Color dry)
        {
            this.spooned = spooned;
            this.average = average;
            this.dry = dry;
        }

        static Palette forChatbox(boolean transparent)
        {
            return transparent ? TRANSPARENT : OPAQUE;
        }
    }

    private LuckCheckMessage()
    {
    }

    /** The checked item's name, or null if this isn't a collection log check message. */
    static String parseCheckedItemName(String message)
    {
        String plain = Text.removeTags(message).replace(' ', ' ').trim();
        Matcher matcher = CHECK_PATTERN.matcher(plain);
        if (!matcher.find())
        {
            return null;
        }
        String item = matcher.group(1).trim();
        return item.isEmpty() ? null : item;
    }

    /**
     * The chat line for one recorded drop, or null if there's nothing to
     * say. Backfilled drops have no KC, and drops without a supported
     * distribution have no luck rating; neither is ever given one. A
     * backfilled drop with a KC snapshot shows that instead, as an estimate.
     * The rating is the website's tier for the probability (LuckSummary.tier,
     * as lib/luck-tier.ts), followed by the probability as a percentage.
     */
    static String format(PlayerLuckResponse.Result result, String itemName, boolean includeSource, int iconIndex,
        Palette palette)
    {
        ChatMessageBuilder message = new ChatMessageBuilder();
        if (iconIndex >= 0)
        {
            message.img(iconIndex).append(" ");
        }
        message.append(itemName + ": ");
        String from = includeSource ? " from " + result.sourceName : "";

        PlayerLuckResponse.Snapshot snap = result.snapshot;
        if (result.backfilled && snap != null && snap.label != null && Double.isFinite(snap.probability))
        {
            // Rates the count on the log page at import, not a drop.
            LuckSummary.Tier tier = LuckSummary.tier(snap.probability);
            Color color = toneColor(tier.tone, palette);
            return message.append("logged before tracking" + from + ", ")
                .append(color, String.format(Locale.ROOT, "%,d by %,d KC", snap.quantity, snap.kc))
                .append(" - ").append(color, tier.text)
                .append(" (" + chatPct(snap.probability) + ", estimated).").build();
        }
        if (result.backfilled)
        {
            return message.append("logged before tracking started" + from + ", KC unknown.").build();
        }
        if (result.kcReceived == null)
        {
            return null;
        }

        String kc = result.kcReceived + " KC";
        if (!result.supported || result.probability == null || !Double.isFinite(result.probability))
        {
            return message.append("first obtained at " + kc + from + " (no luck rating for this drop).").build();
        }
        LuckSummary.Tier tier = LuckSummary.tier(result.probability);
        Color color = toneColor(tier.tone, palette);
        message.append("first obtained at ").append(color, kc).append(from + " - ").append(color, tier.text);
        String pct = chatPct(result.probability);
        return message.append(result.estimated ? " (" + pct + ", estimated)." : " (" + pct + ").").build();
    }

    /**
     * The probability as chat shows it: "<1%" for the whole MEGA JACKPOT
     * tier rather than a rounded "0%", otherwise as the panel rounds it.
     */
    static String chatPct(double probability)
    {
        return probability < 0.01 ? "<1%" : LuckSummary.formatPct(probability);
    }

    /** The chat's three colors over the tiers' five tones: lucky green, even blue, unlucky and dry red. */
    private static Color toneColor(LuckSummary.Tone tone, Palette palette)
    {
        switch (tone)
        {
            case JACKPOT:
            case LUCKY:
                return palette.spooned;
            case EVEN:
                return palette.average;
            default:
                return palette.dry;
        }
    }
}
