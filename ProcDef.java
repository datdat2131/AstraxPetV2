package com.astrax.elementer.config;

/** Hiệu ứng ngẫu nhiên khi đánh thường: IGNITE (đốt) hoặc SLOW (làm chậm). */
public record ProcDef(String type, double chance, double chancePerLevel, double seconds) {
    public double chance(int level) {
        return Math.min(1.0, chance + chancePerLevel * Math.max(0, level - 1));
    }
}
