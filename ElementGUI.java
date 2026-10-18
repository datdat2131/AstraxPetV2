package com.astrax.elementer.gui;

import com.astrax.elementer.ElementerCore;
import com.astrax.elementer.config.ElementConfig;
import com.astrax.elementer.config.ElementDef;
import com.astrax.elementer.config.Messages;
import com.astrax.elementer.config.SkillDef;
import com.astrax.elementer.core.DamageCalculator;
import com.astrax.elementer.core.Element;
import com.astrax.elementer.core.PlayerData;
import com.astrax.elementer.core.TopEntry;
import com.astrax.elementer.service.ElementService;
import com.astrax.elementer.service.SkillService;
import com.astrax.elementer.util.Fx;
import com.astrax.elementer.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Toàn bộ GUI bằng Inventory: menu chính, thức tỉnh, kỹ năng (nâng cấp), chỉ số, tương khắc, bảng xếp hạng. */
public final class ElementGUI implements Listener {
    private enum Page { MAIN, AWAKEN, SKILLS, STATS, AFFINITY, TOP }

    private static final class Holder implements InventoryHolder {
        final Page page;
        Inventory inventory;

        Holder(Page page) { this.page = page; }

        @Override
        public Inventory getInventory() { return inventory; }
    }

    private record Pending(Element element, long expiresAt) {}

    private static final int[] ELEMENT_SLOTS = {10, 11, 12, 14, 15, 16};
    private static final int[] SKILL_SLOTS = {11, 13, 15};

    private final ElementerCore plugin;
    private final ElementConfig cfg;
    private final ElementService elements;
    private final SkillService skills;
    private final Map<UUID, Pending> pending = new HashMap<>();

    public ElementGUI(ElementerCore plugin, ElementConfig cfg, ElementService elements, SkillService skills) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.elements = elements;
        this.skills = skills;
    }

    // ------------------------------------------------------------------ Dựng item

    private ItemStack item(Material m, String name, List<String> lore, boolean glow) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.c(name));
        if (lore != null && !lore.isEmpty()) meta.lore(Text.lines(lore));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        if (glow) meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    private Inventory create(Page page, int size, String titleKey) {
        Holder h = new Holder(page);
        Inventory inv = Bukkit.createInventory(h, size, cfg.messages().get(titleKey));
        h.inventory = inv;
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", null, false);
        for (int i = 0; i < size; i++) inv.setItem(i, filler);
        return inv;
    }

    private String raw(String key) { return cfg.messages().raw(key); }

    // ------------------------------------------------------------------ Các trang

    public void openMain(Player p) {
        PlayerData d = elements.get(p);
        if (d == null) {
            cfg.messages().send(p, "data-loading");
            return;
        }
        if (d.getElement() == null) {
            openAwaken(p);
            return;
        }
        ElementDef def = cfg.element(d.getElement());
        if (def == null) return;
        Inventory inv = create(Page.MAIN, 27, "gui.title-main");
        inv.setItem(4, item(def.icon(), def.colored(), merge(def.description(), elements.infoLines(d)), true));
        inv.setItem(10, item(Material.BOOK, raw("gui.stats-button"), null, false));
        inv.setItem(12, item(Material.NETHER_STAR, raw("gui.skills-button"),
                List.of(Messages.format(raw("gui.points-button"), "points", d.getSkillPoints())), false));
        inv.setItem(14, item(Material.COMPASS, raw("gui.affinity-button"), null, false));
        inv.setItem(16, item(Material.GOLD_INGOT, raw("gui.top-button"), null, false));
        inv.setItem(22, item(Material.BARRIER, raw("gui.close"), null, false));
        p.openInventory(inv);
    }

    public void openAwaken(Player p) {
        PlayerData d = elements.get(p);
        if (d == null) {
            cfg.messages().send(p, "data-loading");
            return;
        }
        Inventory inv = create(Page.AWAKEN, 27, "gui.title-awaken");
        Element[] all = Element.values();
        for (int i = 0; i < all.length; i++) {
            ElementDef def = cfg.element(all[i]);
            if (def == null) continue;
            List<String> lore = new ArrayList<>(def.description());
            lore.add(" ");
            lore.add(raw("gui.lore.hint-awaken"));
            inv.setItem(ELEMENT_SLOTS[i], item(def.icon(), def.colored(), lore, d.getElement() == all[i]));
        }
        inv.setItem(22, item(Material.BARRIER, raw("gui.close"), null, false));
        p.openInventory(inv);
    }

    public void openSkills(Player p) {
        PlayerData d = elements.get(p);
        if (d == null || d.getElement() == null) {
            openMain(p);
            return;
        }
        ElementDef def = cfg.element(d.getElement());
        if (def == null) return;
        Inventory inv = create(Page.SKILLS, 27, "gui.title-skills");
        inv.setItem(4, item(Material.EXPERIENCE_BOTTLE,
                Messages.format(raw("gui.points-button"), "points", d.getSkillPoints()), null, false));
        for (SkillDef s : cfg.skillsOf(d.getElement())) {
            inv.setItem(SKILL_SLOTS[s.slot() - 1], skillItem(d, def, s));
        }
        inv.setItem(22, item(Material.ARROW, raw("gui.back"), null, false));
        p.openInventory(inv);
    }

    private ItemStack skillItem(PlayerData d, ElementDef def, SkillDef s) {
        int sl = skills.levelOf(d, s);
        boolean locked = d.getLevel() < s.unlockLevel();
        List<String> lore = new ArrayList<>(s.description());
        lore.add(" ");
        if (s.ultimate()) lore.add(raw("gui.lore.skill-ultimate"));
        if (s.damage() > 0) {
            double dmg = DamageCalculator.skillDamage(s.damage(), sl, s.perLevelBonus(), d.getLevel(), def.damagePerLevel(),
                    1.0, 1.0, cfg.main().getDouble("combat.max-skill-damage", 40.0));
            lore.add(Messages.format(raw("gui.lore.skill-damage"), "value", Text.num(dmg)));
        }
        if (s.power() > 0) {
            lore.add(Messages.format(raw("gui.lore.skill-power"), "value",
                    Text.num(s.power() * (1.0 + s.perLevelBonus() * (sl - 1)))));
        }
        lore.add(Messages.format(raw("gui.lore.skill-cooldown"), "value", Text.num(skills.cooldownMs(s, sl) / 1000.0)));
        if (s.range() > 0 && s.type() != com.astrax.elementer.core.SkillType.AOE_SELF
                && s.type() != com.astrax.elementer.core.SkillType.SHIELD
                && s.type() != com.astrax.elementer.core.SkillType.HEAL_AREA
                && s.type() != com.astrax.elementer.core.SkillType.DASH) {
            lore.add(Messages.format(raw("gui.lore.skill-range"), "value", Text.num(s.range())));
        }
        if (s.radius() > 0) lore.add(Messages.format(raw("gui.lore.skill-radius"), "value", Text.num(s.radius())));
        lore.add(Messages.format(raw("gui.lore.skill-level"), "level", sl, "max", s.maxLevel()));
        if (locked) {
            lore.add(Messages.format(raw("gui.lore.skill-unlock"), "unlock", s.unlockLevel()));
        } else {
            if (sl >= s.maxLevel()) lore.add(raw("gui.lore.skill-maxed"));
            else lore.add(Messages.format(raw("gui.lore.skill-upgrade"), "cost", skills.upgradeCost(d, s)));
            lore.add(" ");
            lore.add(raw("gui.lore.hint-upgrade"));
            lore.add(raw("gui.lore.hint-select"));
        }
        boolean selected = d.getSelectedSlot() == s.slot();
        if (selected) lore.add(raw("gui.lore.selected"));
        return item(locked ? Material.GRAY_DYE : s.icon(), def.color() + s.name(), lore, selected);
    }

    public void openStats(Player p) {
        PlayerData d = elements.get(p);
        if (d == null || d.getElement() == null) {
            openMain(p);
            return;
        }
        ElementDef def = cfg.element(d.getElement());
        if (def == null) return;
        Inventory inv = create(Page.STATS, 27, "gui.title-stats");
        List<String> lore = elements.statsLines(d);
        String title = lore.isEmpty() ? def.colored() : lore.remove(0);
        inv.setItem(13, item(def.icon(), title, lore, true));
        inv.setItem(22, item(Material.ARROW, raw("gui.back"), null, false));
        p.openInventory(inv);
    }

    public void openAffinity(Player p) {
        Inventory inv = create(Page.AFFINITY, 27, "gui.title-affinity");
        Element[] all = Element.values();
        for (int i = 0; i < all.length; i++) {
            ElementDef def = cfg.element(all[i]);
            if (def == null) continue;
            List<String> strong = new ArrayList<>();
            List<String> weak = new ArrayList<>();
            for (Element o : all) {
                ElementDef od = cfg.element(o);
                if (o == all[i] || od == null) continue;
                double m = cfg.affinity().multiplier(all[i], o);
                if (m > 1.0) strong.add(od.colored());
                else if (m < 1.0) weak.add(od.colored());
            }
            List<String> lore = new ArrayList<>();
            if (strong.isEmpty() && weak.isEmpty()) lore.add(raw("gui.lore.affinity-neutral"));
            if (!strong.isEmpty()) lore.add(Messages.format(raw("gui.lore.affinity-strong"),
                    "list", String.join("&7, ", strong), "mult", cfg.affinity().strong()));
            if (!weak.isEmpty()) lore.add(Messages.format(raw("gui.lore.affinity-weak"),
                    "list", String.join("&7, ", weak), "mult", cfg.affinity().weak()));
            inv.setItem(ELEMENT_SLOTS[i], item(def.icon(), def.colored(), lore, false));
        }
        inv.setItem(22, item(Material.ARROW, raw("gui.back"), null, false));
        p.openInventory(inv);
    }

    public void openTop(Player p) {
        elements.top(10).whenComplete((list, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            if (err != null) {
                plugin.getLogger().log(Level.WARNING, "Không tải được bảng xếp hạng", err);
                return;
            }
            Inventory inv = create(Page.TOP, 36, "gui.title-top");
            int rank = 1;
            for (TopEntry t : list) {
                Element e = Element.parse(t.element());
                ElementDef def = e == null ? null : cfg.element(e);
                String line = Messages.format(raw("top.line"), "rank", rank, "name", t.name() == null ? "?" : t.name(),
                        "element", def == null ? "?" : def.colored(), "level", t.level(), "exp", t.exp());
                inv.setItem(8 + rank, item(def == null ? Material.PAPER : def.icon(), line, null, rank == 1));
                rank++;
            }
            inv.setItem(31, item(Material.ARROW, raw("gui.back"), null, false));
            p.openInventory(inv);
        }));
    }

    private static List<String> merge(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.add(" ");
        out.addAll(b);
        return out;
    }

    // ------------------------------------------------------------------ Sự kiện click

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder h)) return;
        e.setCancelled(true); // không cho lấy/đặt/di chuyển item trong GUI (kể cả shift-click, phím số)
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(top)) return;
        int slot = e.getRawSlot();
        switch (h.page) {
            case MAIN -> clickMain(p, slot);
            case AWAKEN -> clickAwaken(p, slot);
            case SKILLS -> clickSkills(p, slot, e.isRightClick());
            case STATS, AFFINITY -> {
                if (slot == 22) openMain(p);
            }
            case TOP -> {
                if (slot == 31) openMain(p);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    private void clickMain(Player p, int slot) {
        switch (slot) {
            case 10 -> openStats(p);
            case 12 -> openSkills(p);
            case 14 -> openAffinity(p);
            case 16 -> openTop(p);
            case 22 -> p.closeInventory();
            default -> { }
        }
    }

    private void clickAwaken(Player p, int slot) {
        if (slot == 22) {
            p.closeInventory();
            return;
        }
        Element chosen = null;
        Element[] all = Element.values();
        for (int i = 0; i < ELEMENT_SLOTS.length; i++) if (ELEMENT_SLOTS[i] == slot) chosen = all[i];
        ElementDef def = chosen == null ? null : cfg.element(chosen);
        if (def == null) return;
        long now = System.currentTimeMillis();
        pending.values().removeIf(x -> x.expiresAt() < now);
        Pending pd = pending.get(p.getUniqueId());
        if (pd == null || pd.element() != chosen) {
            long ms = cfg.main().getLong("awaken.confirm-seconds", 8) * 1000L;
            pending.put(p.getUniqueId(), new Pending(chosen, now + ms));
            cfg.messages().send(p, "awaken.confirm", "element", def.colored());
            return;
        }
        pending.remove(p.getUniqueId());
        switch (elements.awaken(p, chosen)) {
            case OK -> {
                cfg.messages().send(p, "awaken.success", "element", def.colored());
                Fx.sound(p.getLocation(), "entity.player.levelup", 1f, 0.8f);
                Fx.ring(p.getLocation(), 1.5, "END_ROD", 24);
                openMain(p);
            }
            case ALREADY -> cfg.messages().send(p, "awaken.already");
            case DISABLED -> cfg.messages().send(p, "awaken.disabled");
            case NO_DATA -> cfg.messages().send(p, "data-loading");
        }
    }

    private void clickSkills(Player p, int slot, boolean right) {
        if (slot == 22) {
            openMain(p);
            return;
        }
        int idx = -1;
        for (int i = 0; i < SKILL_SLOTS.length; i++) if (SKILL_SLOTS[i] == slot) idx = i + 1;
        PlayerData d = elements.get(p);
        if (idx < 0 || d == null || d.getElement() == null) return;
        SkillDef s = cfg.skill(d.getElement(), idx);
        if (s == null) return;
        if (right) {
            if (d.getLevel() < s.unlockLevel()) {
                cfg.messages().send(p, "cast.locked", "unlock", s.unlockLevel());
                return;
            }
            d.setSelectedSlot(idx);
            cfg.messages().send(p, "cast.selected", "slot", idx, "name", s.name());
            openSkills(p);
            return;
        }
        switch (skills.upgrade(p, s.id())) {
            case OK -> {
                cfg.messages().send(p, "upgrade.success", "name", s.name(), "level", skills.levelOf(d, s));
                Fx.sound(p.getLocation(), "entity.experience_orb.pickup", 1f, 1.2f);
            }
            case LOCKED -> cfg.messages().send(p, "upgrade.locked");
            case MAX -> cfg.messages().send(p, "upgrade.max");
            case NO_POINTS -> cfg.messages().send(p, "upgrade.no-points", "cost", skills.upgradeCost(d, s),
                    "points", d.getSkillPoints());
            default -> { }
        }
        openSkills(p);
    }

}
