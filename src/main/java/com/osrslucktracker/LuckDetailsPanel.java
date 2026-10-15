package com.osrslucktracker;

import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Every item still being hunted, one folding section per boss: the
 * website's still-hunting cards cut down to the side panel. A section's
 * folded rows (and their item icons) are only built when it's opened, so
 * a full log costs little until then. EDT only.
 */
class LuckDetailsPanel extends JPanel
{
    private static final int ICON_WIDTH = 36;

    private final ItemManager itemManager;
    // Open sections, kept across rebuilds so a new drop doesn't fold them.
    private final Set<String> expanded = new HashSet<>();

    LuckDetailsPanel(ItemManager itemManager)
    {
        this.itemManager = itemManager;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(Component.CENTER_ALIGNMENT);
    }

    /** Empties the panel (logged out, or nothing loaded yet). */
    void clear()
    {
        removeAll();
        revalidate();
        repaint();
    }

    void show(PlayerLuckResponse response, BiFunction<Integer, String, String> itemName)
    {
        removeAll();

        List<LuckSummary.SourceGroup<PlayerLuckResponse.Hunting>> hunting = LuckSummary.groupHunting(response.hunting);
        if (!hunting.isEmpty())
        {
            add(heading("Still hunting", response.hunting.size()));
            for (LuckSummary.SourceGroup<PlayerLuckResponse.Hunting> group : hunting)
            {
                // The most overdue item stays in view, as on the website's cards.
                PlayerLuckResponse.Hunting lead = group.items.get(0);
                List<PlayerLuckResponse.Hunting> rest = group.items.subList(1, group.items.size());
                String detail = String.format("%,d KC &middot; %d missing", group.kc, group.items.size());
                add(section("hunting:" + group.sourceName, group.sourceName, detail, huntingRow(lead, itemName),
                    rest.isEmpty() ? null : body -> rest.forEach(h -> body.add(huntingRow(h, itemName)))));
            }
        }

        revalidate();
        repaint();
    }

    private static JLabel heading(String text, int count)
    {
        JLabel label = new JLabel(text.toUpperCase() + " (" + count + ")");
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(LuckSummaryPanel.PARCHMENT_DIM);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        return label;
    }

    /**
     * A boss's section: a header that folds the body open and shut, an
     * optional row always in view, and a body built on first open. A null
     * fill means there's nothing to fold.
     */
    private JPanel section(String key, String title, String detail, JPanel alwaysShown, Consumer<JPanel> fill)
    {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        section.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
        section.setAlignmentX(Component.CENTER_ALIGNMENT);
        section.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JLabel header = new JLabel();
        header.setForeground(LuckSummaryPanel.PARCHMENT);
        section.add(header);

        if (alwaysShown != null)
        {
            section.add(alwaysShown);
        }

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(body);

        Runnable render = () ->
        {
            boolean open = expanded.contains(key);
            String arrow = fill == null ? "" : (open ? "&#9662; " : "&#9656; ");
            header.setText("<html>" + arrow + LuckSummaryPanel.escape(title)
                + "<br><span style='color:#9CA598'>" + detail + "</span></html>");
            if (open && body.getComponentCount() == 0 && fill != null)
            {
                fill.accept(body);
            }
            body.setVisible(open);
        };
        render.run();

        if (fill != null)
        {
            header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            header.addMouseListener(new MouseAdapter()
            {
                @Override
                public void mouseClicked(MouseEvent e)
                {
                    if (!expanded.remove(key))
                    {
                        expanded.add(key);
                    }
                    render.run();
                    section.revalidate();
                    section.repaint();
                }
            });
        }

        JPanel spaced = new JPanel(new BorderLayout());
        spaced.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        spaced.setAlignmentX(Component.CENTER_ALIGNMENT);
        spaced.add(section, BorderLayout.CENTER);
        spaced.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return spaced;
    }

    /** A missing item: never shown as spooned, as the website's HuntingBadge. */
    private JPanel huntingRow(PlayerLuckResponse.Hunting h, BiFunction<Integer, String, String> itemName)
    {
        String detail = h.probability == null ? "" : tierHtml(LuckSummary.huntingTier(h.probability)) + " &middot; "
            + Math.round(h.probability * 100) + "% would have it";
        return row(h.itemId, itemName.apply(h.itemId, h.sourceName), detail);
    }

    private JPanel row(int itemId, String name, String detailHtml)
    {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel icon = new JLabel();
        icon.setPreferredSize(new Dimension(ICON_WIDTH, 32));
        icon.setHorizontalAlignment(JLabel.CENTER);
        itemManager.getImage(itemId).addTo(icon);
        row.add(icon, BorderLayout.WEST);

        JLabel text = new JLabel("<html><div style='width:" + (LuckSummaryPanel.TEXT_WIDTH - ICON_WIDTH) + "px'>"
            + LuckSummaryPanel.escape(name) + "<br><span style='color:#9CA598'>" + detailHtml + "</span></div></html>");
        text.setFont(FontManager.getRunescapeSmallFont());
        text.setForeground(LuckSummaryPanel.PARCHMENT);
        row.add(text, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    private static String tierHtml(LuckSummary.Tier tier)
    {
        String color = String.format("#%06X", LuckSummaryPanel.badgeColor(tier.tone).getRGB() & 0xFFFFFF);
        String text = tier.tone == LuckSummary.Tone.JACKPOT ? "<b>" + tier.text + "</b>" : tier.text;
        return "<span style='color:" + color + "'>" + text + "</span>";
    }
}
