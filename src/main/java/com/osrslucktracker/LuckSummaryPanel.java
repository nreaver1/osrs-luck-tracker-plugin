package com.osrslucktracker;

import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.LinkBrowser;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.function.BiFunction;

/**
 * A short version of the player's page on the website: overall luck, the
 * jackpot drop and the dry streak, then a link to the full profile.
 * Mirrors OverallCard and SummaryCard. EDT only.
 */
class LuckSummaryPanel extends JPanel
{
    // The website's theme colors (tailwind.config.ts).
    static final Color PARCHMENT = new Color(0xEDE9DD);
    static final Color PARCHMENT_DIM = new Color(0x9CA598);
    static final Color BRASS_BRIGHT = new Color(0xF0D078);
    private static final Color FLAME2 = new Color(0xFF8A00);
    private static final Color FLAME3 = new Color(0xFFD23F);
    private static final Color DRY = new Color(0xB8123A);
    private static final Color DRY_BRIGHT = new Color(0xE8607F);
    // dry-bright at 75% over the panel background, as text-dry-bright/75.
    private static final Color DRY_SOFT = new Color(0xB5506A);

    // Wrapping width for HTML labels inside a card.
    static final int TEXT_WIDTH = 150;

    private final ItemManager itemManager;
    private final String websiteUrl;

    LuckSummaryPanel(ItemManager itemManager, String websiteUrl)
    {
        this.itemManager = itemManager;
        this.websiteUrl = websiteUrl;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(Component.CENTER_ALIGNMENT);
        setVisible(false);
    }

    /** Hides the summary (logged out, or nothing loaded yet). */
    void clear()
    {
        removeAll();
        setVisible(false);
        revalidate();
        repaint();
    }

    /**
     * Shows the summary for one account's results. itemName resolves an
     * (item id, source) pair to a name.
     */
    void show(PlayerLuckResponse response, String ign, boolean profileShown,
        BiFunction<Integer, String, String> itemName)
    {
        removeAll();

        if (response.results.isEmpty())
        {
            add(centered("<html><div style='text-align:center;width:" + (TEXT_WIDTH + 30) + "px'>No drops recorded "
                + "yet. Your luck shows up here after your first boss collection log drop.</div></html>",
                PARCHMENT_DIM));
        }
        else
        {
            add(overallCard(LuckSummary.overall(response.results)));
            add(Box.createVerticalStrut(6));
            add(dropCard("Jackpot", FLAME3, FLAME2, response.mostSpooned, itemName));
            add(Box.createVerticalStrut(6));
            add(dropCard("Dry streak", DRY_BRIGHT, DRY, response.driest, itemName));
        }

        add(Box.createVerticalStrut(6));
        add(websiteNote(ign, profileShown));

        setVisible(true);
        revalidate();
        repaint();
    }

    private JPanel overallCard(LuckSummary.Overall overall)
    {
        LuckSummary.Tier tier = overall != null ? LuckSummary.tier(overall.percentile) : null;
        boolean hot = tier != null && tier.tone == LuckSummary.Tone.JACKPOT;
        JPanel card = card(hot ? FLAME2 : ColorScheme.MEDIUM_GRAY_COLOR);
        card.add(title("Overall luck", hot ? FLAME3 : PARCHMENT_DIM, hot));

        if (overall == null)
        {
            card.add(text("Needs at least " + LuckSummary.MIN_RATED_DROPS + " rated drops to call it.", PARCHMENT_DIM));
            return card;
        }

        JLabel headline = new JLabel(LuckSummary.describePercentile(overall.percentile));
        headline.setFont(FontManager.getRunescapeBoldFont());
        headline.setForeground(headlineColor(tier.tone));
        card.add(headline);
        card.add(text("of accounts with " + overall.ratedDrops + " rated drops.", PARCHMENT_DIM));
        card.add(Box.createVerticalStrut(2));
        card.add(tierLabel(tier, "Average drop " + Math.round(overall.average * 100) + "%"));
        if (overall.unrated > 0)
        {
            card.add(text(overall.unrated + " backlogged or unrated " + (overall.unrated == 1 ? "drop" : "drops")
                + " not counted", PARCHMENT_DIM));
        }
        return card;
    }

    private JPanel dropCard(String titleText, Color titleColor, Color accent, PlayerLuckResponse.Result result,
        BiFunction<Integer, String, String> itemName)
    {
        JPanel card = card(accent);
        card.add(title(titleText, titleColor, true));

        if (result == null || result.probability == null)
        {
            card.add(text("Not enough data yet.", PARCHMENT_DIM));
            return card;
        }

        JLabel item = new JLabel(itemName.apply(result.itemId, result.sourceName));
        item.setForeground(PARCHMENT);
        item.setIconTextGap(6);
        itemManager.getImage(result.itemId).addTo(item);
        card.add(item);

        StringBuilder detail = new StringBuilder();
        if (result.kcReceived != null)
        {
            detail.append(String.format("%,d", result.kcReceived)).append(" KC &middot; ");
        }
        detail.append(escape(result.sourceName));
        if (result.estimated)
        {
            detail.append(" &middot; estimated");
        }
        card.add(text(detail.toString(), PARCHMENT_DIM));
        card.add(Box.createVerticalStrut(2));

        LuckSummary.Tier tier = LuckSummary.tier(result.probability);
        JLabel luck = tierLabel(tier, Math.round(result.probability * 100) + "%");
        luck.setToolTipText(Math.round(result.probability * 100) + "% likely to have taken this long or less");
        card.add(luck);
        return card;
    }

    private JPanel websiteNote(String ign, boolean profileShown)
    {
        JPanel note = new JPanel();
        note.setLayout(new BoxLayout(note, BoxLayout.Y_AXIS));
        note.setAlignmentX(Component.CENTER_ALIGNMENT);

        String body = "This is the short version. Every logged drop, your log pages and what you're still "
            + "hunting are on the website";
        note.add(centered("<html><div style='text-align:center;width:" + (TEXT_WIDTH + 30) + "px'>"
            + body + (profileShown ? "." : " once your log is public.") + "</div></html>", PARCHMENT_DIM));

        if (profileShown && ign != null)
        {
            String url = websiteUrl + "/player/" + encode(ign);
            JLabel link = centered("<html><u>See your full profile</u></html>", ColorScheme.BRAND_ORANGE);
            link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            link.setToolTipText(url);
            link.addMouseListener(new MouseAdapter()
            {
                @Override
                public void mouseClicked(MouseEvent e)
                {
                    LinkBrowser.browse(url);
                }
            });
            note.add(Box.createVerticalStrut(2));
            note.add(link);
        }
        return note;
    }

    /** A card with a colored stripe on its left edge, like the site's card borders. */
    private static JPanel card(Color accent)
    {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 3, 0, 0, accent),
            BorderFactory.createEmptyBorder(6, 8, 6, 6)
        ));
        card.setAlignmentX(Component.CENTER_ALIGNMENT);
        // Full width, as tall as its content.
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return card;
    }

    static JLabel title(String text, Color color, boolean bold)
    {
        JLabel label = new JLabel(text.toUpperCase());
        label.setFont(bold ? FontManager.getRunescapeSmallFont().deriveFont(java.awt.Font.BOLD)
            : FontManager.getRunescapeSmallFont());
        label.setForeground(color);
        return label;
    }

    static JLabel text(String html, Color color)
    {
        JLabel label = new JLabel("<html><div style='width:" + TEXT_WIDTH + "px'>" + html + "</div></html>");
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(color);
        return label;
    }

    /** The tier's text in its color, then a dim detail, as LuckBadge beside a number. */
    static JLabel tierLabel(LuckSummary.Tier tier, String detail)
    {
        String color = String.format("#%06X", badgeColor(tier.tone).getRGB() & 0xFFFFFF);
        String tierText = tier.tone == LuckSummary.Tone.JACKPOT ? "<b>" + tier.text + "</b>" : tier.text;
        JLabel label = new JLabel("<html><span style='color:" + color + "'>" + tierText + "</span> &middot; "
            + detail + "</html>");
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(PARCHMENT_DIM);
        return label;
    }

    private static JLabel centered(String html, Color color)
    {
        JLabel label = new JLabel(html);
        label.setForeground(color);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        return label;
    }

    // HEADLINE_CLASS in OverallCard.tsx.
    private static Color headlineColor(LuckSummary.Tone tone)
    {
        switch (tone)
        {
            case JACKPOT:
                return FLAME3;
            case LUCKY:
                return BRASS_BRIGHT;
            case UNLUCKY:
                return DRY_SOFT;
            case DRY:
                return DRY_BRIGHT;
            default:
                return PARCHMENT;
        }
    }

    // TONE_CLASS in LuckBadge.tsx.
    static Color badgeColor(LuckSummary.Tone tone)
    {
        return tone == LuckSummary.Tone.EVEN ? PARCHMENT_DIM : headlineColor(tone);
    }

    private static String encode(String ign)
    {
        try
        {
            // URLEncoder writes spaces as "+", which a path segment keeps literally.
            return URLEncoder.encode(ign, "UTF-8").replace("+", "%20");
        }
        catch (UnsupportedEncodingException e)
        {
            return ign;
        }
    }

    static String escape(String s)
    {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
