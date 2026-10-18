package com.astrax.elementer.listener;

import com.astrax.elementer.config.ElementConfig;
import com.astrax.elementer.config.ElementDef;
import com.astrax.elementer.config.ProcDef;
import com.astrax.elementer.core.PlayerData;
import com.astrax.elementer.service.CombatService;
import com.astrax.elementer.service.ElementService;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.concurrent.ThreadLocalRandom;

public final class DamageListener implements Listener {
    private final ElementConfig cfg;
    private final ElementService elements;
    private final CombatService combat;

    public DamageListener(ElementConfig cfg, ElementService elements, CombatService combat) {
        this.cfg = cfg;
        this.elements = elements;
        this.combat = combat;
    }

    /** Giảm sát thương nội tại của nguyên tố, sau đó trừ vào lá chắn. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        double dmg = e.getDamage();
        PlayerData d = elements.get(p);
        if (d != null && d.getElement() != null) {
            ElementDef def = cfg.element(d.getElement());
            Double red = def == null ? null : def.damageReduction().get(e.getCause());
            if (red != null && red > 0) dmg *= (1.0 - red);
        }
        dmg = combat.absorb(p, dmg);
        if (dmg <= 0.0001) {
            e.setCancelled(true);
            return;
        }
        e.setDamage(dmg);
    }

    /** Ghi nhận sát thương kỹ năng không bị hủy; proc nội tại cho đòn đánh thường. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (combat.isInternalDamage()) {
            combat.markApplied();
            return; // đòn của kỹ năng không kích hoạt proc đánh thường
        }
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity t)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        PlayerData d = elements.get(p);
        if (d == null || d.getElement() == null) return;
        ElementDef def = cfg.element(d.getElement());
        ProcDef proc = def == null ? null : def.proc();
        if (proc == null || !combat.isValidTarget(p, t)) return;
        if (ThreadLocalRandom.current().nextDouble() >= proc.chance(d.getLevel())) return;
        if (proc.type().equals("IGNITE")) combat.ignite(t, proc.seconds());
        else combat.slow(t, proc.seconds(), 0);
    }
}
