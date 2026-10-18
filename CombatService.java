package com.astrax.elementer.service;

import com.astrax.elementer.config.ElementConfig;
import com.astrax.elementer.config.ElementDef;
import com.astrax.elementer.config.SkillDef;
import com.astrax.elementer.core.DamageCalculator;
import com.astrax.elementer.core.Element;
import com.astrax.elementer.core.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Tameable;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Logic chiến đấu: kiểm tra mục tiêu hợp lệ, tính sát thương (tương khắc, PvP, trần), lá chắn,
 * khống chế (có giới hạn thời gian + miễn nhiễm), đẩy lùi có trần. Chỉ dùng trên main thread.
 */
public final class CombatService {
    private record Shield(double amount, long expiresAt) {}

    private final ElementConfig cfg;
    private ElementService elements;

    private final Map<UUID, Shield> shields = new HashMap<>();
    private final Map<UUID, Long> rootImmunity = new HashMap<>();
    private boolean internalDamage;
    private boolean damageApplied;

    public CombatService(ElementConfig cfg) {
        this.cfg = cfg;
    }

    public void bind(ElementService elements) {
        this.elements = elements;
    }

    // ------------------------------------------------------------------ Mục tiêu

    /** Kiểm tra mục tiêu có thể bị kỹ năng của {@code caster} tác động hay không. */
    public boolean isValidTarget(Player caster, LivingEntity t) {
        if (t == null || t.equals(caster) || t.isDead() || !t.isValid()) return false;
        if (!t.getWorld().equals(caster.getWorld())) return false;
        if (t.isInvulnerable() || cfg.isProtected(t.getType())) return false;
        if (t instanceof Player p) {
            if (!cfg.main().getBoolean("combat.pvp-enabled", true)) return false;
            if (!t.getWorld().getPVP()) return false;
            GameMode gm = p.getGameMode();
            if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return false;
        }
        if (t instanceof Tameable tm && tm.isTamed() && caster.equals(tm.getOwner())) return false;
        return true;
    }

    public Element targetElement(LivingEntity t) {
        if (t instanceof Player p) {
            PlayerData d = elements.get(p);
            return d == null ? null : d.getElement();
        }
        return cfg.mobElement(t.getType());
    }

    // ------------------------------------------------------------------ Sát thương

    public double computeDamage(PlayerData caster, SkillDef s, int skillLevel, LivingEntity target) {
        if (caster.getElement() == null || s.damage() <= 0) return 0;
        ElementDef def = cfg.element(caster.getElement());
        if (def == null) return 0;
        double cap = cfg.main().getDouble("combat.max-skill-damage", 40.0);
        double aff = DamageCalculator.clamp(cfg.affinity().multiplier(caster.getElement(), targetElement(target)),
                cfg.main().getDouble("combat.affinity-min", 0.5), cfg.main().getDouble("combat.affinity-max", 1.5));
        double extra = 1.0;
        if (target instanceof Player) extra *= cfg.main().getDouble("combat.pvp-damage-multiplier", 0.6);
        if (cfg.isUndead(target.getType())) {
            extra *= 1.0 + def.undeadBonus();
            extra *= s.fx("undead-multiplier", 1.0);
        }
        return DamageCalculator.skillDamage(s.damage(), skillLevel, s.perLevelBonus(), caster.getLevel(),
                def.damagePerLevel(), aff, extra, cap);
    }

    /**
     * Gây sát thương qua hệ thống sự kiện chuẩn của server (để plugin bảo vệ vùng có thể hủy).
     * @return true nếu sự kiện sát thương đã diễn ra và không bị hủy
     */
    public boolean damage(Player caster, LivingEntity target, double amount) {
        if (amount <= 0) return false;
        damageApplied = false;
        internalDamage = true;
        try {
            target.damage(amount, caster);
        } finally {
            internalDamage = false;
        }
        return damageApplied;
    }

    /** True trong lúc kỹ năng đang gây sát thương (listener dùng để bỏ qua proc đánh thường). */
    public boolean isInternalDamage() { return internalDamage; }

    /** Listener gọi khi sự kiện sát thương do kỹ năng KHÔNG bị hủy. */
    public void markApplied() { damageApplied = true; }

    // ------------------------------------------------------------------ Lá chắn

    public void addShield(Player p, double amount, double seconds) {
        double maxAmount = cfg.main().getDouble("combat.max-shield", 40.0);
        double maxSec = cfg.main().getDouble("combat.max-shield-seconds", 15.0);
        double a = Math.min(maxAmount, Math.max(0, amount));
        double sec = Math.min(maxSec, Math.max(0, seconds));
        if (a <= 0 || sec <= 0) return;
        shields.put(p.getUniqueId(), new Shield(a, System.currentTimeMillis() + (long) (sec * 1000)));
    }

    public double shieldRemaining(UUID id) {
        Shield s = shields.get(id);
        if (s == null) return 0;
        if (s.expiresAt() <= System.currentTimeMillis()) {
            shields.remove(id);
            return 0;
        }
        return s.amount();
    }

    /** Hấp thụ sát thương bằng lá chắn. @return sát thương còn lại sau khi hấp thụ */
    public double absorb(Player p, double damage) {
        UUID id = p.getUniqueId();
        double have = shieldRemaining(id);
        if (have <= 0 || damage <= 0) return damage;
        double absorbed = Math.min(have, damage);
        double left = have - absorbed;
        if (left <= 0.01) shields.remove(id);
        else shields.put(id, new Shield(left, shields.get(id).expiresAt()));
        return damage - absorbed;
    }

    public void clearShield(UUID id) { shields.remove(id); }

    // ------------------------------------------------------------------ Khống chế / hiệu ứng

    private double maxControl() { return cfg.main().getDouble("combat.max-control-seconds", 4.0); }

    /** Trói chặt (gần như bất động). Có trần thời gian và miễn nhiễm sau khi bị trói. */
    public boolean root(LivingEntity t, double seconds) {
        double sec = Math.min(maxControl(), seconds);
        if (sec <= 0) return false;
        long now = System.currentTimeMillis();
        Long imm = rootImmunity.get(t.getUniqueId());
        if (imm != null && imm > now) return false;
        int ticks = (int) Math.round(sec * 20);
        t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 6, false, true, true));
        t.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, ticks, 128, false, false, false));
        long immunity = (long) ((sec + cfg.main().getDouble("combat.control-immunity-seconds", 6.0)) * 1000);
        rootImmunity.put(t.getUniqueId(), now + immunity);
        return true;
    }

    public void slow(LivingEntity t, double seconds, int amplifier) {
        double sec = Math.min(maxControl(), seconds);
        if (sec <= 0) return;
        int amp = Math.max(0, Math.min(4, amplifier));
        t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) Math.round(sec * 20), amp, false, true, true));
    }

    public void freeze(LivingEntity t, int ticks) {
        int capped = Math.min(Math.max(0, ticks), (int) (maxControl() * 20 * 2));
        if (capped > 0) t.setFreezeTicks(Math.max(t.getFreezeTicks(), capped));
    }

    public void ignite(LivingEntity t, double seconds) {
        double sec = Math.min(cfg.main().getDouble("combat.max-burn-seconds", 10.0), seconds);
        if (sec > 0) t.setFireTicks(Math.max(t.getFireTicks(), (int) Math.round(sec * 20)));
    }

    /** Đặt vận tốc với độ lớn bị chặn bởi combat.max-knockback. */
    public void push(LivingEntity t, Vector v) {
        if (Double.isNaN(v.getX()) || Double.isNaN(v.getY()) || Double.isNaN(v.getZ())) return;
        double max = cfg.main().getDouble("combat.max-knockback", 1.6);
        if (v.length() > max) v = v.normalize().multiply(max);
        t.setVelocity(v);
    }

    public void maintenance(long nowMs) {
        shields.values().removeIf(s -> s.expiresAt() <= nowMs);
        rootImmunity.values().removeIf(e -> e <= nowMs);
    }

    public void clear(UUID id) {
        shields.remove(id);
        rootImmunity.remove(id);
    }
}
