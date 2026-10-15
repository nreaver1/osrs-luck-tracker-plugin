package com.osrslucktracker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The website's player summary, for the panel: overall luck as a
 * percentile and the luck tier wording. Mirrors lib/overall.ts and
 * lib/luck-tier.ts in the website.
 */
final class LuckSummary
{
    // Mirrors MIN_RATED_DROPS in lib/overall.ts and _shared/leaderboard.ts.
    static final int MIN_RATED_DROPS = 3;

    private LuckSummary()
    {
    }

    enum Tone
    {
        JACKPOT, LUCKY, EVEN, UNLUCKY, DRY
    }

    static final class Tier
    {
        final String text;
        final Tone tone;
        final double max;
        final boolean inclusive;

        Tier(String text, Tone tone, double max, boolean inclusive)
        {
            this.text = text;
            this.tone = tone;
            this.max = max;
            this.inclusive = inclusive;
        }
    }

    // In order, as LUCK_TIERS in lib/luck-tier.ts.
    private static final Tier[] TIERS = {
        new Tier("MEGA JACKPOT", Tone.JACKPOT, 0.01, false),
        new Tier("JACKPOT", Tone.JACKPOT, 0.1, false),
        new Tier("pretty spooned", Tone.LUCKY, 0.25, false),
        new Tier("a little spooned", Tone.LUCKY, 0.42, false),
        new Tier("even money", Tone.EVEN, 0.58, true),
        new Tier("a little unlucky", Tone.UNLUCKY, 0.7, true),
        new Tier("pretty unlucky", Tone.UNLUCKY, 0.8, true),
        new Tier("dry streak", Tone.DRY, 0.95, true),
        new Tier("bone dry", Tone.DRY, 0.99, true),
        new Tier("desert", Tone.DRY, Double.POSITIVE_INFINITY, true),
    };

    static Tier tier(double probability)
    {
        for (Tier t : TIERS)
        {
            if (probability < t.max || (t.inclusive && probability == t.max))
            {
                return t;
            }
        }
        return TIERS[TIERS.length - 1];
    }

    // Not having a drop yet is never lucky, so the spooned tiers read as
    // "still early" for hunted items (STILL_EARLY in HuntingGroups.tsx).
    private static final Tier STILL_EARLY = new Tier("still early", Tone.EVEN, 0.42, false);

    static Tier huntingTier(double probability)
    {
        Tier t = tier(probability);
        return t.tone == Tone.JACKPOT || t.tone == Tone.LUCKY ? STILL_EARLY : t;
    }

    static final class SourceGroup<T>
    {
        final String sourceName;
        final List<T> items = new ArrayList<>();
        // Hunting groups only: the page's kill count and when it last rose.
        int kc;
        String updatedAt = "";

        SourceGroup(String sourceName)
        {
            this.sourceName = sourceName;
        }
    }

    /**
     * Hunted items by source, as groupHunting in HuntingGroups.tsx: the
     * most recently raised kill count first, then the most kills; within
     * one, the most overdue item first.
     */
    static List<SourceGroup<PlayerLuckResponse.Hunting>> groupHunting(List<PlayerLuckResponse.Hunting> hunting)
    {
        Map<String, SourceGroup<PlayerLuckResponse.Hunting>> bySource = new HashMap<>();
        for (PlayerLuckResponse.Hunting h : hunting)
        {
            SourceGroup<PlayerLuckResponse.Hunting> g = bySource.computeIfAbsent(h.sourceName, SourceGroup::new);
            g.items.add(h);
            g.kc = Math.max(g.kc, h.kc);
            String updated = h.updatedAt != null ? h.updatedAt : "";
            if (updated.compareTo(g.updatedAt) > 0)
            {
                g.updatedAt = updated;
            }
        }
        List<SourceGroup<PlayerLuckResponse.Hunting>> groups = new ArrayList<>(bySource.values());
        for (SourceGroup<PlayerLuckResponse.Hunting> g : groups)
        {
            g.items.sort(Comparator.comparingDouble(
                (PlayerLuckResponse.Hunting h) -> h.probability != null ? -h.probability : 1));
        }
        groups.sort(Comparator.comparing((SourceGroup<PlayerLuckResponse.Hunting> g) -> g.updatedAt).reversed()
            .thenComparing(Comparator.comparingInt((SourceGroup<PlayerLuckResponse.Hunting> g) -> g.kc).reversed())
            .thenComparing(g -> g.sourceName));
        return groups;
    }

    static boolean isRated(PlayerLuckResponse.Result r)
    {
        return r.supported && !r.backfilled && r.probability != null && Double.isFinite(r.probability);
    }

    static final class Overall
    {
        final int ratedDrops;
        // Backfilled or not yet supported drops, left out.
        final int unrated;
        final double average;
        // Chance a fair-luck account with this many rated drops averages
        // this low or lower. Low is lucky, like a single drop's P.
        final double percentile;

        Overall(int ratedDrops, int unrated, double average, double percentile)
        {
            this.ratedDrops = ratedDrops;
            this.unrated = unrated;
            this.average = average;
            this.percentile = percentile;
        }
    }

    /** Null when there are fewer than MIN_RATED_DROPS rated drops. */
    static Overall overall(List<PlayerLuckResponse.Result> results)
    {
        int rated = 0;
        double sum = 0;
        for (PlayerLuckResponse.Result r : results)
        {
            if (isRated(r))
            {
                rated++;
                sum += r.probability;
            }
        }
        if (rated < MIN_RATED_DROPS)
        {
            return null;
        }
        return new Overall(rated, results.size() - rated, sum / rated, uniformSumCdf(sum, rated));
    }

    /** Rounds a fraction as a percentage without letting 99.97% claim 100%. */
    static String formatPct(double fraction)
    {
        double pct = fraction * 100;
        if (pct >= 99.95)
        {
            return "99.9%+";
        }
        if (pct >= 99)
        {
            return String.format(Locale.ROOT, "%.1f%%", pct);
        }
        return Math.round(pct) + "%";
    }

    /** "Luckier than 97%" / "Drier than 88%", the percentile's headline. */
    static String describePercentile(double percentile)
    {
        return percentile <= 0.5
            ? "Luckier than " + formatPct(1 - percentile)
            : "Drier than " + formatPct(percentile);
    }

    // Mirrors uniformSumCdf in lib/overall.ts: with fair luck each drop's P
    // is (roughly) uniform on [0, 1], so the sum of n of them follows the
    // Irwin–Hall distribution. Past EXACT_MAX_N the exact alternating sum
    // loses precision and the normal approximation is already very close.
    private static final int EXACT_MAX_N = 20;

    static double uniformSumCdf(double s, int n)
    {
        if (s <= 0)
        {
            return 0;
        }
        if (s >= n)
        {
            return 1;
        }
        if (n > EXACT_MAX_N)
        {
            return normalCdf((s - n / 2.0) / Math.sqrt(n / 12.0));
        }
        double total = 0;
        double binom = 1; // C(n, k)
        double factorial = 1; // n!
        for (int i = 2; i <= n; i++)
        {
            factorial *= i;
        }
        for (int k = 0; k <= Math.floor(s); k++)
        {
            total += (k % 2 == 0 ? 1 : -1) * binom * Math.pow(s - k, n);
            binom = binom * (n - k) / (k + 1);
        }
        return Math.min(1, Math.max(0, total / factorial));
    }

    // Abramowitz & Stegun 7.1.26 (error under 1.5e-7).
    private static double normalCdf(double z)
    {
        double x = Math.abs(z) / Math.sqrt(2);
        double t = 1 / (1 + 0.3275911 * x);
        double poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))));
        double erf = 1 - poly * Math.exp(-x * x);
        return z >= 0 ? (1 + erf) / 2 : (1 - erf) / 2;
    }
}
