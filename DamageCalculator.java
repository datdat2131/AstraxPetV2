package com.astrax.elementer.core;

/** Công thức sát thương kỹ năng (thuần toán học, không phụ thuộc Bukkit). */
public final class DamageCalculator {
    private DamageCalculator() {}

    public static double clamp(double v, double min, double max) {
        if (Double.isNaN(v)) return min;
        return Math.max(min, Math.min(max, v));
    }

    /**
     * sát thương = base × (1 + bonusKỹNăng×(lvKỹNăng-1)) × (1 + bonusNguyênTố×(lvNguyênTố-1)) × tươngKhắc × hệSốThêm,
     * giới hạn trong [0, cap].
     */
    public static double skillDamage(double base, int skillLevel, double perSkillLevelBonus,
                                     int elementLevel, double perElementLevelBonus,
                                     double affinity, double extraMultiplier, double cap) {
        double skillMult = 1.0 + perSkillLevelBonus * Math.max(0, skillLevel - 1);
        double elementMult = 1.0 + perElementLevelBonus * Math.max(0, elementLevel - 1);
        double d = base * skillMult * elementMult * affinity * extraMultiplier;
        if (Double.isNaN(d) || d < 0) return 0;
        return Math.min(Math.max(0, cap), d);
    }
}
