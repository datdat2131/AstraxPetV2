package com.astrax.elementer.core;

/** Công thức EXP: yêu cầu(level) = round(base * growth^(level-1)). Ở cấp tối đa không nhận thêm EXP. */
public final class ExpCurve {
    private final long base;
    private final double growth;
    private final int maxLevel;
    private final int pointsPerLevel;

    public ExpCurve(long base, double growth, int maxLevel, int pointsPerLevel) {
        this.base = Math.max(1, base);
        this.growth = Math.max(1.0, growth);
        this.maxLevel = Math.max(1, maxLevel);
        this.pointsPerLevel = Math.max(0, pointsPerLevel);
    }

    public int maxLevel() { return maxLevel; }

    /** EXP cần để lên cấp kế tiếp; 0 nếu đã ở cấp tối đa. */
    public long required(int level) {
        if (level >= maxLevel) return 0;
        double v = base * Math.pow(growth, Math.max(0, level - 1));
        if (Double.isNaN(v) || v > 1.0e15) return (long) 1.0e15;
        return Math.max(1, Math.round(v));
    }

    /**
     * Cộng EXP, xử lý nhiều lần lên cấp liên tiếp và cộng điểm kỹ năng.
     * @return số cấp đã tăng
     */
    public int addExp(PlayerData d, long amount) {
        if (amount <= 0 || d.getElement() == null) return 0;
        if (d.getLevel() >= maxLevel) {
            if (d.getExp() != 0) d.setExp(0);
            return 0;
        }
        long exp = d.getExp() + Math.min(amount, (long) 1.0e15);
        int lvl = d.getLevel();
        int gained = 0;
        while (lvl < maxLevel && exp >= required(lvl)) {
            exp -= required(lvl);
            lvl++;
            gained++;
        }
        if (lvl >= maxLevel) exp = 0;
        d.setLevel(lvl);
        d.setExp(exp);
        if (gained > 0) d.addSkillPoints(gained * pointsPerLevel);
        return gained;
    }
}
