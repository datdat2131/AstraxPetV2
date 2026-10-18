package com.astrax.elementer.listener;

import com.astrax.elementer.ElementerCore;
import com.astrax.elementer.config.ElementConfig;
import com.astrax.elementer.config.ElementDef;
import com.astrax.elementer.core.PlayerData;
import com.astrax.elementer.service.ElementService;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** EXP từ khai khoáng hợp lệ, quái và PvP hợp lệ, kèm các biện pháp chống farm/lạm dụng. */
public final class ProgressListener implements Listener {
    private static final int MAX_TRACKED_BLOCKS = 50_000;
    private static final Set<CreatureSpawnEvent.SpawnReason> ARTIFICIAL = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.SPAWNER, CreatureSpawnEvent.SpawnReason.SPAWNER_EGG,
            CreatureSpawnEvent.SpawnReason.DISPENSE_EGG, CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM,
            CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN, CreatureSpawnEvent.SpawnReason.BUILD_WITHER,
            CreatureSpawnEvent.SpawnReason.EGG, CreatureSpawnEvent.SpawnReason.BREEDING);

    private final ElementConfig cfg;
    private final ElementService elements;
    private final NamespacedKey artificialKey;

    /** Khối khoáng sản do người chơi đặt (giới hạn kích thước, loại bỏ cũ nhất trước). */
    private final Set<String> placed = Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_TRACKED_BLOCKS;
        }
    });
    private final Map<String, Long> pvpCooldown = new HashMap<>();

    public ProgressListener(ElementerCore plugin, ElementConfig cfg, ElementService elements) {
        this.cfg = cfg;
        this.elements = elements;
        this.artificialKey = new NamespacedKey(plugin, "artificial_spawn");
    }

    private static String key(Block b) {
        return b.getWorld().getUID() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    private void gain(Player p, long amount) {
        if (cfg.isWorldDisabled(p.getWorld().getName())) return;
        PlayerData d = elements.get(p);
        if (d == null || d.getElement() == null) return;
        long got = elements.addExp(p, amount, false);
        if (got > 0 && cfg.main().getBoolean("exp.show-gain-actionbar", true)) {
            ElementDef def = cfg.element(d.getElement());
            cfg.messages().actionBar(p, "exp-gain", "exp", got, "element", def == null ? "" : def.colored());
        }
    }

    // ------------------------------------------------------------------ Khai khoáng

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (cfg.miningExp(e.getBlockPlaced().getType()) != null) placed.add(key(e.getBlockPlaced()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL) return;
        if (!cfg.main().getBoolean("exp.sources.mining.enabled", true)) return;
        Block b = e.getBlock();
        Integer exp = cfg.miningExp(b.getType());
        if (exp == null) return;
        boolean wasPlaced = placed.remove(key(b));
        if (exp <= 0) return;
        if (wasPlaced && cfg.main().getBoolean("exp.sources.mining.ignore-placed-blocks", true)) return;
        gain(p, exp);
    }

    // ------------------------------------------------------------------ Quái và PvP

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (ARTIFICIAL.contains(e.getSpawnReason())) {
            e.getEntity().getPersistentDataContainer().set(artificialKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        if (victim instanceof Player vp) pvpKill(killer, vp);
        else mobKill(killer, victim);
    }

    private void mobKill(Player killer, LivingEntity mob) {
        if (!cfg.main().getBoolean("exp.sources.mob-kill.enabled", true)) return;
        if (cfg.main().getBoolean("exp.sources.mob-kill.ignore-spawner-mobs", true)
                && mob.getPersistentDataContainer().has(artificialKey, PersistentDataType.BYTE)) return;
        Integer listed = cfg.mobExp(mob.getType());
        int exp;
        if (listed != null) exp = listed;
        else if (mob instanceof Enemy) exp = cfg.main().getInt("exp.sources.mob-kill.default", 4);
        else return;
        if (exp > 0) gain(killer, exp);
    }

    private void pvpKill(Player killer, Player victim) {
        if (!cfg.main().getBoolean("exp.sources.pvp-kill.enabled", true)) return;
        PlayerData kd = elements.get(killer);
        PlayerData vd = elements.get(victim);
        if (kd == null || vd == null || kd.getElement() == null || vd.getElement() == null) return;
        if (cfg.main().getBoolean("exp.sources.pvp-kill.ignore-same-ip", true)
                && killer.getAddress() != null && victim.getAddress() != null
                && Objects.equals(killer.getAddress().getAddress(), victim.getAddress().getAddress())) return;
        int maxDiff = cfg.main().getInt("exp.sources.pvp-kill.max-level-difference", 15);
        if (kd.getLevel() - vd.getLevel() > maxDiff) return;
        long now = System.currentTimeMillis();
        long cdMs = cfg.main().getLong("exp.sources.pvp-kill.same-victim-cooldown-seconds", 600) * 1000L;
        pvpCooldown.values().removeIf(t -> now - t > cdMs);
        String k = killer.getUniqueId() + ":" + victim.getUniqueId();
        Long last = pvpCooldown.get(k);
        if (last != null && now - last < cdMs) return;
        pvpCooldown.put(k, now);
        gain(killer, cfg.main().getInt("exp.sources.pvp-kill.exp", 30));
    }
}
