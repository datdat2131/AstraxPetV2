package com.astrax.elementer.listener;

import com.astrax.elementer.ElementerCore;
import com.astrax.elementer.config.ElementConfig;
import com.astrax.elementer.config.SkillDef;
import com.astrax.elementer.core.PlayerData;
import com.astrax.elementer.service.CombatService;
import com.astrax.elementer.service.ElementService;
import com.astrax.elementer.service.SkillService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

public final class PlayerListener implements Listener {
    private final ElementerCore plugin;
    private final ElementConfig cfg;
    private final ElementService elements;
    private final SkillService skills;
    private final CombatService combat;

    public PlayerListener(ElementerCore plugin, ElementConfig cfg, ElementService elements,
                          SkillService skills, CombatService combat) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.elements = elements;
        this.skills = skills;
        this.combat = combat;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        elements.load(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        elements.unload(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        // Chết: mất lá chắn và miễn nhiễm; hồi chiêu giữ nguyên để không thể "hồi chiêu bằng cách chết".
        combat.clearShield(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        // Attribute tạm thời bị mất khi hồi sinh: áp dụng lại ở tick sau
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) elements.applyPassives(p);
        }, 1L);
    }

    /** Phím F = tung kỹ năng đang chọn; Shift+F = đổi kỹ năng. Người chơi chưa thức tỉnh vẫn dùng F bình thường. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (!cfg.main().getBoolean("input.swap-key-cast", true)) return;
        Player p = e.getPlayer();
        PlayerData d = elements.get(p);
        if (d == null || d.getElement() == null) return;
        if (!p.hasPermission("elementer.command.cast")) return;
        e.setCancelled(true);
        if (p.isSneaking()) {
            int next = d.getSelectedSlot() % 3 + 1;
            d.setSelectedSlot(next);
            SkillDef s = cfg.skill(d.getElement(), next);
            if (s != null) cfg.messages().actionBar(p, "cast.selected", "slot", next, "name", s.name());
            return;
        }
        skills.castAndNotify(p, d.getSelectedSlot());
    }
}
