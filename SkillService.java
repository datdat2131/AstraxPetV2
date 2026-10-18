package com.astrax.elementer.service;

import com.astrax.elementer.ElementerCore;
import com.astrax.elementer.config.ElementConfig;
import com.astrax.elementer.config.ElementDef;
import com.astrax.elementer.config.SkillDef;
import com.astrax.elementer.core.CooldownTracker;
import com.astrax.elementer.core.PlayerData;
import com.astrax.elementer.util.Fx;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntConsumer;

/** Thực thi kỹ năng thật (sát thương, khống chế, hồi máu, khiên, lướt) và quản lý hồi chiêu/nâng cấp. Chỉ dùng trên main thread. */
public final class SkillService {
    public enum CastResult {
        OK, NO_DATA, NO_ELEMENT, NO_SKILL, LOCKED, COOLDOWN, GLOBAL_COOLDOWN, NO_TARGET, WORLD_DISABLED, INVALID_STATE
    }

    public enum UpgradeResult { OK, NO_DATA, NO_SKILL, LOCKED, MAX, NO_POINTS }

    private final ElementerCore plugin;
    private final ElementConfig cfg;
    private final ElementService elements;
    private final CombatService combat;
    private final CooldownTracker cooldowns = new CooldownTracker();

    private record Aim(Location point, LivingEntity entity) {}

    public SkillService(ElementerCore plugin, ElementConfig cfg, ElementService elements, CombatService combat) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.elements = elements;
        this.combat = combat;
    }

    public void clear(UUID id) { cooldowns.clear(id); }

    public void maintenance(long nowMs) { cooldowns.purgeExpired(nowMs); }

    public long remainingMs(Player p, SkillDef s) {
        return cooldowns.remaining(p.getUniqueId(), s.id(), System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ Tung kỹ năng

    public CastResult cast(Player p, int slot) {
        if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return CastResult.INVALID_STATE;
        if (cfg.isWorldDisabled(p.getWorld().getName())) return CastResult.WORLD_DISABLED;
        PlayerData d = elements.get(p);
        if (d == null) return CastResult.NO_DATA;
        if (d.getElement() == null) return CastResult.NO_ELEMENT;
        SkillDef s = cfg.skill(d.getElement(), slot);
        if (s == null) return CastResult.NO_SKILL;
        if (d.getLevel() < s.unlockLevel()) return CastResult.LOCKED;
        long now = System.currentTimeMillis();
        UUID id = p.getUniqueId();
        if (cooldowns.globalRemaining(id, now) > 0) return CastResult.GLOBAL_COOLDOWN;
        if (cooldowns.remaining(id, s.id(), now) > 0) return CastResult.COOLDOWN;
        int sl = levelOf(d, s);
        if (!execute(p, d, s, sl)) return CastResult.NO_TARGET;
        cooldowns.start(id, s.id(), cooldownMs(s, sl), now);
        cooldowns.startGlobal(id, cfg.main().getLong("combat.global-cooldown-ms", 700), now);
        return CastResult.OK;
    }

    /** Tung kỹ năng và báo kết quả bằng action bar. */
    public void castAndNotify(Player p, int slot) {
        CastResult r = cast(p, slot);
        var m = cfg.messages();
        PlayerData d = elements.get(p);
        SkillDef s = d == null || d.getElement() == null ? null : cfg.skill(d.getElement(), slot);
        switch (r) {
            case OK -> { }
            case NO_DATA -> m.actionBar(p, "data-loading");
            case NO_ELEMENT -> m.actionBar(p, "not-awakened");
            case NO_SKILL -> m.actionBar(p, "cast.no-skill");
            case LOCKED -> m.actionBar(p, "cast.locked", "unlock", s == null ? "?" : s.unlockLevel());
            case COOLDOWN -> m.actionBar(p, "cast.cooldown", "name", s == null ? "" : s.name(),
                    "seconds", String.format(java.util.Locale.ROOT, "%.1f", s == null ? 0 : remainingMs(p, s) / 1000.0));
            case GLOBAL_COOLDOWN -> m.actionBar(p, "cast.global-cooldown");
            case NO_TARGET -> m.actionBar(p, "cast.no-target");
            case WORLD_DISABLED -> m.actionBar(p, "cast.world-disabled");
            case INVALID_STATE -> m.actionBar(p, "cast.invalid-state");
        }
    }

    public int levelOf(PlayerData d, SkillDef s) {
        return Math.max(1, Math.min(s.maxLevel(), d.skillLevel(s.id())));
    }

    public long cooldownMs(SkillDef s, int skillLevel) {
        double red = Math.min(cfg.main().getDouble("combat.max-cooldown-reduction", 0.3),
                cfg.main().getDouble("combat.cooldown-reduction-per-level", 0.03) * Math.max(0, skillLevel - 1));
        double sec = Math.max(cfg.main().getDouble("combat.min-cooldown-seconds", 1.0), s.cooldown() * (1.0 - red));
        return (long) (sec * 1000);
    }

    // ------------------------------------------------------------------ Nâng cấp

    public int upgradeCost(PlayerData d, SkillDef s) {
        return s.upgradeCost() * levelOf(d, s);
    }

    public UpgradeResult upgrade(Player p, String skillId) {
        PlayerData d = elements.get(p);
        if (d == null || d.getElement() == null) return UpgradeResult.NO_DATA;
        SkillDef s = cfg.skill(skillId);
        if (s == null || s.element() != d.getElement()) return UpgradeResult.NO_SKILL;
        if (d.getLevel() < s.unlockLevel()) return UpgradeResult.LOCKED;
        int cur = levelOf(d, s);
        if (cur >= s.maxLevel()) return UpgradeResult.MAX;
        int cost = upgradeCost(d, s);
        if (d.getSkillPoints() < cost) return UpgradeResult.NO_POINTS;
        d.setSkillPoints(d.getSkillPoints() - cost);
        d.setSkillLevel(s.id(), cur + 1);
        return UpgradeResult.OK;
    }

    // ------------------------------------------------------------------ Thực thi

    private boolean execute(Player p, PlayerData d, SkillDef s, int sl) {
        Fx.sound(p.getLocation(), s.sound(), 1f, 1f);
        return switch (s.type()) {
            case PROJECTILE -> castProjectile(p, d, s, sl);
            case AOE_SELF -> castAoeSelf(p, d, s, sl);
            case AOE_TARGET -> castAoeTarget(p, d, s, sl);
            case STRIKE_TARGET -> castStrike(p, d, s, sl);
            case DASH -> castDash(p, d, s, sl);
            case SHIELD -> castShield(p, d, s, sl);
            case HEAL_AREA -> castHeal(p, d, s, sl);
        };
    }

    private Aim aim(Player p, double range) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        World w = p.getWorld();
        RayTraceResult blk = w.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        double maxDist = blk == null ? range : blk.getHitPosition().distance(eye.toVector());
        RayTraceResult ent = w.rayTraceEntities(eye, dir, maxDist, 0.6,
                e -> e instanceof LivingEntity le && combat.isValidTarget(p, le));
        if (ent != null && ent.getHitEntity() instanceof LivingEntity le) {
            return new Aim(ent.getHitPosition().toLocation(w), le);
        }
        Vector end = blk != null ? blk.getHitPosition() : eye.toVector().add(dir.clone().multiply(range));
        return new Aim(end.toLocation(w), null);
    }

    /** Gây sát thương + hiệu ứng lên một mục tiêu. @return true nếu đòn trúng (không bị hủy) */
    private boolean hit(Player p, PlayerData d, SkillDef s, int sl, LivingEntity t, Location center, double ratio) {
        if (!combat.isValidTarget(p, t)) return false;
        double dmg = combat.computeDamage(d, s, sl, t) * ratio;
        if (dmg > 0 && !combat.damage(p, t, dmg)) return false;
        if (t.isDead()) return true;
        applyEffects(t, s, center, ratio);
        return true;
    }

    private void applyEffects(LivingEntity t, SkillDef s, Location center, double ratio) {
        double burn = s.fx("burn-seconds", 0);
        if (burn > 0) combat.ignite(t, burn);
        double slow = s.fx("slow-seconds", 0);
        if (slow > 0) combat.slow(t, slow, (int) s.fx("slow-amplifier", 1));
        double fr = s.fx("freeze-ticks", 0);
        if (fr > 0) combat.freeze(t, (int) fr);
        double root = s.fx("root-seconds", 0);
        if (root > 0) combat.root(t, root);

        Vector v = new Vector(0, 0, 0);
        double kb = s.fx("knockback", 0) * ratio;
        if (kb > 0) {
            Vector away = t.getLocation().toVector().subtract(center.toVector());
            away.setY(0);
            if (away.lengthSquared() > 1e-4) v.add(away.normalize().multiply(kb));
        }
        double lift = s.fx("lift", 0);
        if (lift > 0) v.setY(lift);
        double pull = s.fx("pull", 0);
        if (pull > 0) {
            Vector toCenter = center.toVector().subtract(t.getLocation().toVector());
            toCenter.setY(0);
            double len = toCenter.length();
            if (len > 1e-4) v.add(toCenter.normalize().multiply(Math.min(pull, len * 0.5)));
        }
        if (v.lengthSquared() > 0) combat.push(t, v);
    }

    private List<LivingEntity> nearby(Player p, Location center, double radius, boolean hostileOnly) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : center.getWorld().getNearbyLivingEntities(center, radius)) {
            if (e.getLocation().distance(center) > radius) continue;
            if (!combat.isValidTarget(p, e)) continue;
            if (hostileOnly && !(e instanceof Enemy)) continue;
            out.add(e);
        }
        out.sort(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(center)));
        int max = cfg.main().getInt("combat.max-targets-per-cast", 8);
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    private boolean stillValid(Player p, World w) {
        return p.isOnline() && !p.isDead() && p.getWorld().equals(w);
    }

    private void repeat(Player p, int times, int interval, IntConsumer tick) {
        tick.accept(0);
        if (times <= 1) return;
        World w = p.getWorld();
        new BukkitRunnable() {
            int n = 1;

            @Override
            public void run() {
                if (n >= times || !stillValid(p, w)) {
                    cancel();
                    return;
                }
                tick.accept(n);
                n++;
            }
        }.runTaskTimer(plugin, Math.max(1, interval), Math.max(1, interval));
    }

    // ---- PROJECTILE

    private boolean castProjectile(Player p, PlayerData d, SkillDef s, int sl) {
        int pierce = (int) s.fx("pierce", 0);
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World w = p.getWorld();
        RayTraceResult blk = w.rayTraceBlocks(eye, dir, s.range(), FluidCollisionMode.NEVER, true);
        double maxDist = blk == null ? s.range() : blk.getHitPosition().distance(eye.toVector());
        Set<UUID> seen = new HashSet<>();
        List<LivingEntity> victims = new ArrayList<>();
        Location impact = eye.clone().add(dir.clone().multiply(maxDist));
        for (int i = 0; i <= pierce; i++) {
            RayTraceResult r = w.rayTraceEntities(eye, dir, maxDist, 0.5,
                    e -> e instanceof LivingEntity le && !seen.contains(e.getUniqueId()) && combat.isValidTarget(p, le));
            if (r == null || !(r.getHitEntity() instanceof LivingEntity le)) break;
            seen.add(le.getUniqueId());
            victims.add(le);
            if (pierce == 0) impact = r.getHitPosition().toLocation(w);
        }
        double drawDist = pierce == 0 ? Math.min(maxDist, impact.distance(eye)) : maxDist;
        for (double t = 1; t <= drawDist; t += 0.6) {
            Fx.particle(eye.clone().add(dir.clone().multiply(t)), s.particle(), 1, 0.03, 0.03, 0.03, 0);
        }
        for (LivingEntity v : victims) {
            hit(p, d, s, sl, v, p.getLocation(), 1.0);
        }
        boolean exploded = pierce == 0 && (!victims.isEmpty() || blk != null);
        if (exploded && s.radius() > 0) {
            double ratio = s.fx("splash-damage-ratio", 0.5);
            for (LivingEntity e : nearby(p, impact, s.radius(), false)) {
                if (seen.contains(e.getUniqueId())) continue;
                hit(p, d, s, sl, e, impact, ratio);
            }
            Fx.ring(impact, s.radius(), s.particle(), 24);
            Fx.particle(impact, "EXPLOSION", 1, 0, 0, 0, 0);
            Fx.sound(impact, "entity.generic.explode", 0.8f, 1.2f);
        }
        return true;
    }

    // ---- AOE

    private void aoeTick(Player p, PlayerData d, SkillDef s, int sl, Location center) {
        boolean hostileOnly = s.fx("hostile-only", 0) > 0;
        boolean bolts = s.fx("lightning-visual", 0) > 0;
        List<LivingEntity> targets = nearby(p, center, s.radius(), hostileOnly);
        boolean struck = false;
        for (LivingEntity t : targets) {
            if (hit(p, d, s, sl, t, center, 1.0) && bolts) {
                center.getWorld().strikeLightningEffect(t.getLocation());
                struck = true;
            }
        }
        if (bolts && !struck) {
            double a = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
            double r = ThreadLocalRandom.current().nextDouble(0, Math.max(0.5, s.radius()));
            center.getWorld().strikeLightningEffect(center.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r));
        }
        double heal = s.fx("heal-allies", 0);
        if (heal > 0) healPlayers(p, center, s.radius(), heal * scale(s, sl), true);
        Fx.ring(center, s.radius(), s.particle(), 32);
        Fx.particle(center.clone().add(0, 1, 0), s.particle(), 20, s.radius() / 3, 0.5, s.radius() / 3, 0.02);
        if (s.ultimate()) Fx.particle(center, "EXPLOSION_EMITTER", 1, 0, 0, 0, 0);
    }

    private double scale(SkillDef s, int sl) {
        return 1.0 + s.perLevelBonus() * Math.max(0, sl - 1);
    }

    private boolean castAoeSelf(Player p, PlayerData d, SkillDef s, int sl) {
        Location center = p.getLocation().clone();
        repeat(p, (int) s.fx("repeat", 1), (int) s.fx("interval-ticks", 10), i -> aoeTick(p, d, s, sl, center));
        return true;
    }

    private boolean castAoeTarget(Player p, PlayerData d, SkillDef s, int sl) {
        Location center = aim(p, s.range()).point();
        int delay = (int) s.fx("delay-ticks", 0);
        Fx.ring(center, s.radius(), s.particle(), 32); // báo hiệu vùng sắp bị đánh
        Fx.sound(center, s.sound(), 0.6f, 0.8f);
        World w = p.getWorld();
        Runnable go = () -> {
            if (!stillValid(p, w)) return;
            repeat(p, (int) s.fx("repeat", 1), (int) s.fx("interval-ticks", 10), i -> aoeTick(p, d, s, sl, center));
        };
        if (delay <= 0) go.run();
        else Bukkit.getScheduler().runTaskLater(plugin, go, delay);
        return true;
    }

    // ---- STRIKE

    private boolean castStrike(Player p, PlayerData d, SkillDef s, int sl) {
        Aim a = aim(p, s.range());
        if (a.entity() == null) return false;
        LivingEntity t = a.entity();
        if (!hit(p, d, s, sl, t, p.getLocation(), 1.0)) return false;
        if (s.fx("lightning-visual", 0) > 0) t.getWorld().strikeLightningEffect(t.getLocation());
        Fx.particle(t.getLocation().add(0, 1, 0), s.particle(), 30, 0.4, 0.8, 0.4, 0.1);
        return true;
    }

    // ---- DASH

    private boolean castDash(Player p, PlayerData d, SkillDef s, int sl) {
        Vector dir = p.getLocation().getDirection();
        dir.setY(Math.max(-0.2, Math.min(0.35, dir.getY())));
        if (dir.lengthSquared() < 1e-6) return false;
        double power = Math.min(cfg.main().getDouble("combat.max-dash-power", 2.2), s.power() * scale(s, sl));
        Fx.particle(p.getLocation().add(0, 1, 0), s.particle(), 25, 0.4, 0.6, 0.4, 0.05);
        p.setFallDistance(0);
        p.setVelocity(dir.normalize().multiply(power));
        double sf = s.fx("slow-falling-seconds", 0);
        if (sf > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, (int) (sf * 20), 0, false, false, true));
        if (s.damage() > 0 && s.radius() > 0) {
            World w = p.getWorld();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (stillValid(p, w)) aoeTick(p, d, s, sl, p.getLocation().clone());
            }, 5L);
        }
        return true;
    }

    // ---- SHIELD

    private boolean castShield(Player p, PlayerData d, SkillDef s, int sl) {
        ElementDef def = cfg.element(d.getElement());
        double amount = s.power() * scale(s, sl) * (1.0 + (def == null ? 0 : def.damagePerLevel()) * Math.max(0, d.getLevel() - 1));
        combat.addShield(p, amount, s.duration());
        double res = s.fx("resistance-seconds", 0);
        if (res > 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, (int) (res * 20),
                    (int) s.fx("resistance-amplifier", 0), false, true, true));
        }
        double selfSlow = s.fx("self-slow-amplifier", -1);
        if (selfSlow >= 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) (s.duration() * 20),
                    (int) selfSlow, false, false, true));
        }
        Fx.ring(p.getLocation(), 1.2, s.particle(), 20);
        UUID id = p.getUniqueId();
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || combat.shieldRemaining(id) <= 0) {
                    cancel();
                    return;
                }
                Fx.particle(p.getLocation().add(0, 1, 0), s.particle(), 4, 0.5, 0.8, 0.5, 0.01);
            }
        }.runTaskTimer(plugin, 10L, 10L);
        return true;
    }

    // ---- HEAL

    private boolean castHeal(Player p, PlayerData d, SkillDef s, int sl) {
        ElementDef def = cfg.element(d.getElement());
        double amount = s.power() * scale(s, sl) * (1.0 + (def == null ? 0 : def.damagePerLevel()) * Math.max(0, d.getLevel() - 1));
        amount = Math.min(cfg.main().getDouble("combat.max-heal", 30.0), amount);
        healOne(p, amount, s.particle());
        if (s.fx("heal-others", 0) > 0) healPlayers(p, p.getLocation(), s.radius(), amount, false);
        Fx.ring(p.getLocation(), s.radius(), s.particle(), 32);
        return true;
    }

    /** Hồi máu cho người chơi khác trong bán kính (không có hệ thống party: hồi cho mọi người chơi gần đó). */
    private void healPlayers(Player caster, Location center, double radius, double amount, boolean includeCaster) {
        double capped = Math.min(cfg.main().getDouble("combat.max-heal", 30.0), amount);
        for (Player o : center.getWorld().getPlayers()) {
            if (o.isDead() || o.getGameMode() == GameMode.SPECTATOR) continue;
            if (o.equals(caster) && !includeCaster) continue;
            if (o.getLocation().distance(center) > radius) continue;
            healOne(o, capped, "HEART");
        }
    }

    private void healOne(Player t, double amount, String particle) {
        if (amount <= 0 || t.getAttribute(Attribute.MAX_HEALTH) == null) return;
        t.heal(amount, EntityRegainHealthEvent.RegainReason.MAGIC);
        Fx.particle(t.getLocation().add(0, 1.5, 0), particle, 6, 0.4, 0.4, 0.4, 0);
    }
}
