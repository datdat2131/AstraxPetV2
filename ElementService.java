package com.astrax.elementer.service;

import com.astrax.elementer.ElementerCore;
import com.astrax.elementer.config.AttrDef;
import com.astrax.elementer.config.ElementConfig;
import com.astrax.elementer.config.ElementDef;
import com.astrax.elementer.config.LevelReward;
import com.astrax.elementer.core.DataSanitizer;
import com.astrax.elementer.core.Element;
import com.astrax.elementer.core.PlayerData;
import com.astrax.elementer.core.TopEntry;
import com.astrax.elementer.config.SkillDef;
import com.astrax.elementer.storage.ElementStorage;
import com.astrax.elementer.config.Messages;
import com.astrax.elementer.util.Fx;
import com.astrax.elementer.util.Text;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** Quản lý dữ liệu người chơi đang online: tải/lưu bất đồng bộ, thức tỉnh, EXP, lên cấp, nội tại, phần thưởng mốc. */
public final class ElementService {
    public enum AwakenResult { OK, NO_DATA, DISABLED, ALREADY }

    private final ElementerCore plugin;
    private final ElementConfig cfg;
    private final ElementStorage storage;
    private final CombatService combat;
    private SkillService skills;

    private final Map<UUID, PlayerData> cache = new HashMap<>();
    private final Set<UUID> loading = new HashSet<>();
    private final Set<String> warnedAttributes = new HashSet<>();
    private BukkitTask saveTask;

    public ElementService(ElementerCore plugin, ElementConfig cfg, ElementStorage storage, CombatService combat) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.storage = storage;
        this.combat = combat;
    }

    public void bind(SkillService skills) {
        this.skills = skills;
    }

    public PlayerData get(Player p) { return cache.get(p.getUniqueId()); }
    public PlayerData get(UUID id) { return cache.get(id); }
    public boolean isLoading(Player p) { return loading.contains(p.getUniqueId()); }

    // ------------------------------------------------------------------ Tải / lưu

    public void start() {
        long period = Math.max(30, cfg.main().getLong("save-interval-seconds", 300)) * 20L;
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            saveDirty();
            long now = System.currentTimeMillis();
            combat.maintenance(now);
            skills.maintenance(now);
        }, period, period);
    }

    public void load(Player p) {
        UUID id = p.getUniqueId();
        if (!loading.add(id)) return;
        String name = p.getName();
        storage.load(id).whenComplete((snap, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            loading.remove(id);
            Player online = Bukkit.getPlayer(id);
            if (online == null) return; // đã thoát trước khi tải xong: không đưa vào bộ nhớ
            if (err != null) {
                // KHÔNG tạo dữ liệu mới để tránh ghi đè dữ liệu tốt đang có trong DB.
                plugin.getLogger().log(Level.SEVERE, "Không tải được dữ liệu của " + name, err);
                cfg.messages().send(online, "data-error");
                return;
            }
            PlayerData d = snap == null
                    ? DataSanitizer.fresh(id, name)
                    : DataSanitizer.sanitize(snap, name, cfg.curve(), this::maxSkillLevel, plugin.getLogger());
            cache.put(id, d);
            applyPassives(online);
            if (d.getElement() == null) {
                online.sendMessage(cfg.messages().prefixed("not-awakened"));
            }
        }));
    }

    private Integer maxSkillLevel(String skillId) {
        SkillDef s = cfg.skill(skillId);
        return s == null ? null : s.maxLevel();
    }

    public void unload(Player p) {
        UUID id = p.getUniqueId();
        loading.remove(id);
        PlayerData d = cache.remove(id);
        combat.clear(id);
        skills.clear(id);
        removePassives(p);
        if (d != null && d.isDirty()) {
            d.clearDirty();
            storage.save(d.snapshot()).exceptionally(ex -> {
                plugin.getLogger().log(Level.SEVERE, "Lưu dữ liệu khi thoát thất bại cho " + d.getName(), ex);
                return null;
            });
        }
    }

    public void saveDirty() {
        for (PlayerData d : cache.values()) {
            if (!d.isDirty()) continue;
            d.clearDirty();
            storage.save(d.snapshot()).exceptionally(ex -> {
                Bukkit.getScheduler().runTask(plugin, d::markDirty); // thử lại ở lần lưu sau
                plugin.getLogger().log(Level.SEVERE, "Lưu dữ liệu thất bại cho " + d.getName(), ex);
                return null;
            });
        }
    }

    /** Gọi khi tắt plugin: lưu đồng bộ (chờ xong) để không mất dữ liệu. */
    public void shutdown() {
        if (saveTask != null) saveTask.cancel();
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            removePassives(p);
        }
        for (PlayerData d : cache.values()) {
            if (d.isDirty()) {
                d.clearDirty();
                pending.add(storage.save(d.snapshot()));
            }
        }
        for (CompletableFuture<Void> f : pending) {
            try {
                f.join();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Lưu dữ liệu khi tắt thất bại", e);
            }
        }
        cache.clear();
    }

    public CompletableFuture<List<TopEntry>> top(int limit) {
        return storage.top(limit);
    }

    // ------------------------------------------------------------------ Thức tỉnh

    public AwakenResult awaken(Player p, Element e) {
        PlayerData d = get(p);
        if (d == null) return AwakenResult.NO_DATA;
        if (cfg.element(e) == null) return AwakenResult.DISABLED;
        if (d.getElement() != null && (!cfg.main().getBoolean("awaken.allow-change", false) || d.getElement() == e)) {
            return AwakenResult.ALREADY;
        }
        boolean changing = d.getElement() != null;
        d.setElement(e);
        d.setAwakenedAt(System.currentTimeMillis());
        if (changing) {
            d.clearSkillLevels();
            d.setSelectedSlot(1);
        }
        applyPassives(p);
        return AwakenResult.OK;
    }

    // ------------------------------------------------------------------ EXP

    /**
     * Cộng EXP nguyên tố.
     * @param bypassCap true cho thao tác quản trị/nhiệm vụ không tính vào giới hạn ngày
     * @return lượng EXP thực nhận
     */
    public long addExp(Player p, long amount, boolean bypassCap) {
        PlayerData d = get(p);
        if (d == null || d.getElement() == null || amount <= 0) return 0;
        if (d.getLevel() >= cfg.curve().maxLevel()) return 0;
        long granted = Math.round(amount * cfg.main().getDouble("exp.multiplier", 1.0));
        if (granted <= 0) return 0;
        long today = System.currentTimeMillis() / 86_400_000L;
        if (d.getDailyDay() != today) {
            d.setDailyDay(today);
            d.setDailyExp(0);
        }
        if (!bypassCap) {
            long cap = cfg.main().getLong("exp.daily-cap", 0);
            if (cap > 0) {
                long room = cap - d.getDailyExp();
                if (room <= 0) return 0;
                granted = Math.min(granted, room);
                d.setDailyExp(d.getDailyExp() + granted);
            }
        }
        int before = d.getLevel();
        int gained = cfg.curve().addExp(d, granted);
        if (gained > 0) onLevelUp(p, d, before);
        return granted;
    }

    private void onLevelUp(Player p, PlayerData d, int before) {
        ElementDef def = cfg.element(d.getElement());
        String colored = def == null ? d.getElement().name() : def.colored();
        applyPassives(p);
        Fx.sound(p.getLocation(), "entity.player.levelup", 1f, 1f);
        p.sendMessage(cfg.messages().prefixed("level-up", "element", colored, "level", d.getLevel(),
                "points", d.getSkillPoints()));
        p.showTitle(Title.title(Component.empty(),
                cfg.messages().get("level-up", "element", colored, "level", d.getLevel(), "points", d.getSkillPoints()),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(400))));
        for (int lvl = before + 1; lvl <= d.getLevel(); lvl++) {
            LevelReward r = cfg.levelRewards().get(lvl);
            if (r != null) claimLevelReward(p, r);
        }
    }

    /** Mỗi mốc cấp chỉ trao một lần cho mỗi người chơi: DB ghi nhận trước, chỉ trao khi ghi nhận thành công. */
    private void claimLevelReward(Player p, LevelReward r) {
        UUID id = p.getUniqueId();
        String key = "level:" + r.level();
        storage.claimReward(id, key).whenComplete((first, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                plugin.getLogger().log(Level.SEVERE, "Không kiểm tra được phần thưởng " + key + " của " + p.getName(), err);
                return;
            }
            if (!first) return; // đã nhận trước đó
            Player online = Bukkit.getPlayer(id);
            if (online == null) {
                storage.unclaimReward(id, key); // chưa trao được, hoàn tác để lần sau còn nhận
                return;
            }
            for (String cmd : r.commands()) {
                String line = cmd.replace("%player%", online.getName()).replace("%level%", String.valueOf(r.level()));
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
            }
            if (!r.message().isBlank()) online.sendMessage(cfg.messages().prefixed("reward-given", "level", r.level()));
        }));
    }

    // ------------------------------------------------------------------ Thao tác quản trị

    public void setLevel(Player p, int level) {
        PlayerData d = get(p);
        if (d == null) return;
        d.setLevel(Math.max(1, Math.min(cfg.curve().maxLevel(), level)));
        d.setExp(0);
        applyPassives(p);
    }

    public void setExp(Player p, long exp) {
        PlayerData d = get(p);
        if (d == null) return;
        long req = cfg.curve().required(d.getLevel());
        d.setExp(req == 0 ? 0 : Math.min(Math.max(0, exp), req - 1));
    }

    public void setElement(Player p, Element e) {
        PlayerData d = get(p);
        if (d == null) return;
        d.setElement(e);
        if (e != null && d.getAwakenedAt() == 0) d.setAwakenedAt(System.currentTimeMillis());
        d.clearSkillLevels();
        d.setSelectedSlot(1);
        applyPassives(p);
    }

    public void reset(Player p) {
        PlayerData d = get(p);
        if (d == null) return;
        d.reset();
        combat.clear(p.getUniqueId());
        skills.clear(p.getUniqueId());
        removePassives(p);
    }

    // ------------------------------------------------------------------ Nội tại (attribute)

    private String namespace() {
        return plugin.getName().toLowerCase(Locale.ROOT);
    }

    public void applyPassives(Player p) {
        removePassives(p);
        PlayerData d = get(p);
        if (d == null || d.getElement() == null) return;
        ElementDef def = cfg.element(d.getElement());
        if (def == null) return;
        for (AttrDef a : def.attributes()) {
            Attribute attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(a.attribute()));
            if (attr == null) {
                if (warnedAttributes.add(a.attribute())) {
                    plugin.getLogger().warning("Attribute không tồn tại trong elements.yml: " + a.attribute());
                }
                continue;
            }
            AttributeInstance inst = p.getAttribute(attr);
            double amount = a.amount(d.getLevel());
            if (inst == null || amount == 0) continue;
            inst.addTransientModifier(new AttributeModifier(new NamespacedKey(plugin, "passive_" + a.attribute()),
                    amount, a.operation()));
        }
    }

    public void removePassives(Player p) {
        String ns = namespace();
        for (Attribute attr : Registry.ATTRIBUTE) {
            AttributeInstance inst = p.getAttribute(attr);
            if (inst == null) continue;
            for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
                if (m.getKey().getNamespace().equals(ns)) inst.removeModifier(m);
            }
        }
        AttributeInstance mh = p.getAttribute(Attribute.MAX_HEALTH);
        if (mh != null && p.getHealth() > mh.getValue()) p.setHealth(mh.getValue());
    }

    public void reapplyAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (get(p) != null) applyPassives(p);
        }
    }

    // ------------------------------------------------------------------ Hiển thị

    public List<String> infoLines(PlayerData d) {
        ElementDef def = cfg.element(d.getElement());
        long req = cfg.curve().required(d.getLevel());
        long cap = cfg.main().getLong("exp.daily-cap", 0);
        return cfg.messages().formatted("info", "element", def == null ? "?" : def.colored(),
                "level", d.getLevel(), "max", cfg.curve().maxLevel(), "exp", d.getExp(),
                "required", req == 0 ? "MAX" : String.valueOf(req), "points", d.getSkillPoints(),
                "daily", d.getDailyExp(), "cap", cap <= 0 ? "∞" : String.valueOf(cap));
    }

    public List<String> statsLines(PlayerData d) {
        List<String> out = new ArrayList<>();
        ElementDef def = cfg.element(d.getElement());
        if (def == null) return out;
        var m = cfg.messages();
        out.add(Messages.format(m.raw("stats-header"), "element", def.colored()));
        out.add(Messages.format(m.raw("stats-damage"), "percent",
                Text.num(def.damagePerLevel() * Math.max(0, d.getLevel() - 1) * 100)));
        for (AttrDef a : def.attributes()) {
            out.add(Messages.format(m.raw("stats-attribute"), "attribute", a.attribute(),
                    "value", String.format(Locale.ROOT, "%.3f", a.amount(d.getLevel()))));
        }
        for (Map.Entry<DamageCause, Double> e : def.damageReduction().entrySet()) {
            out.add(Messages.format(m.raw("stats-reduction"), "cause", e.getKey().name(),
                    "percent", Text.num(e.getValue() * 100)));
        }
        return out;
    }
}
