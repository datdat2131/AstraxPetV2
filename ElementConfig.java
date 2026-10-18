package com.astrax.elementer.config;

import com.astrax.elementer.core.AffinityTable;
import com.astrax.elementer.core.Element;
import com.astrax.elementer.core.ExpCurve;
import com.astrax.elementer.core.SkillType;
import org.bukkit.Material;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

/** Đọc và kiểm tra config.yml, elements.yml, skills.yml, messages.yml. Giá trị sai bị cảnh báo và bỏ qua, không làm sập plugin. */
public final class ElementConfig {
    private final JavaPlugin plugin;
    private final Logger log;
    private final Messages messages = new Messages();

    private volatile FileConfiguration main;
    private volatile Map<Element, ElementDef> elements = Map.of();
    private volatile Map<String, SkillDef> skills = Map.of();
    private volatile Map<Element, List<SkillDef>> skillsByElement = Map.of();
    private volatile AffinityTable affinity = new AffinityTable(1.25, 0.85, Map.of());
    private volatile ExpCurve curve = new ExpCurve(100, 1.12, 50, 1);
    private volatile Map<EntityType, Element> mobElements = Map.of();
    private volatile Set<EntityType> undead = Set.of();
    private volatile Set<EntityType> protectedTypes = Set.of();
    private volatile Map<Material, Integer> miningExp = Map.of();
    private volatile Map<EntityType, Integer> mobExp = Map.of();
    private volatile Map<Integer, LevelReward> levelRewards = Map.of();
    private volatile Set<String> disabledWorlds = Set.of();

    public ElementConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public void load() {
        for (String f : List.of("config.yml", "elements.yml", "skills.yml", "messages.yml")) {
            if (!new File(plugin.getDataFolder(), f).exists()) plugin.saveResource(f, false);
        }
        plugin.reloadConfig();
        this.main = plugin.getConfig();
        messages.load(new File(plugin.getDataFolder(), "messages.yml"));

        YamlConfiguration el = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "elements.yml"));
        YamlConfiguration sk = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "skills.yml"));

        this.elements = parseElements(el);
        this.affinity = parseAffinity(el);
        this.mobElements = parseMobElements(el);
        this.undead = parseEntityTypes(el.getStringList("undead"), "undead");
        this.protectedTypes = parseEntityTypes(main.getStringList("combat.protected-types"), "combat.protected-types");
        this.skills = parseSkills(sk, this.elements);
        this.skillsByElement = groupSkills(this.skills);
        this.curve = new ExpCurve(main.getLong("exp.base", 100), main.getDouble("exp.growth", 1.12),
                main.getInt("exp.max-level", 50), main.getInt("exp.skill-points-per-level", 1));
        this.miningExp = parseMining();
        this.mobExp = parseMobExp();
        this.levelRewards = parseRewards();
        this.disabledWorlds = new HashSet<>(main.getStringList("disabled-worlds"));
        com.astrax.elementer.util.Fx.setEnabled(main.getBoolean("effects.particles", true));

        for (Element e : Element.values()) {
            List<SkillDef> l = skillsByElement.get(e);
            if (elements.containsKey(e) && (l == null || l.isEmpty())) {
                log.warning("Nguyên tố " + e + " không có kỹ năng hợp lệ nào trong skills.yml.");
            }
        }
        log.info("Đã tải " + elements.size() + " nguyên tố, " + skills.size() + " kỹ năng.");
    }

    // ------------------------------------------------------------------ Parse

    private Map<Element, ElementDef> parseElements(YamlConfiguration y) {
        Map<Element, ElementDef> out = new EnumMap<>(Element.class);
        ConfigurationSection root = y.getConfigurationSection("elements");
        for (Element e : Element.values()) {
            ConfigurationSection s = root == null ? null : root.getConfigurationSection(e.name());
            if (s == null) {
                log.warning("elements.yml thiếu nguyên tố " + e + " - nguyên tố này bị tắt.");
                continue;
            }
            Material icon = Material.matchMaterial(s.getString("icon", "PAPER"));
            if (icon == null) {
                log.warning("Icon không hợp lệ cho " + e + ", dùng PAPER.");
                icon = Material.PAPER;
            }
            List<AttrDef> attrs = new ArrayList<>();
            for (Map<?, ?> m : s.getMapList("attributes")) {
                Object a = m.get("attribute");
                if (a == null) continue;
                String opName = m.get("operation") == null ? "ADD_NUMBER" : String.valueOf(m.get("operation"));
                AttributeModifier.Operation op;
                try {
                    op = AttributeModifier.Operation.valueOf(opName.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    log.warning("Operation không hợp lệ '" + opName + "' ở " + e + ".");
                    continue;
                }
                attrs.add(new AttrDef(String.valueOf(a).toLowerCase(Locale.ROOT), op, num(m.get("base")), num(m.get("per-level"))));
            }
            Map<DamageCause, Double> red = new EnumMap<>(DamageCause.class);
            ConfigurationSection rs = s.getConfigurationSection("damage-reduction");
            if (rs != null) {
                for (String k : rs.getKeys(false)) {
                    try {
                        red.put(DamageCause.valueOf(k.toUpperCase(Locale.ROOT)), Math.max(0, Math.min(1, rs.getDouble(k))));
                    } catch (IllegalArgumentException ex) {
                        log.warning("DamageCause không hợp lệ '" + k + "' ở " + e + ".");
                    }
                }
            }
            ProcDef proc = null;
            ConfigurationSection ps = s.getConfigurationSection("proc");
            if (ps != null) {
                String type = ps.getString("type", "").toUpperCase(Locale.ROOT);
                if (type.equals("IGNITE") || type.equals("SLOW")) {
                    proc = new ProcDef(type, ps.getDouble("chance"), ps.getDouble("chance-per-level"), ps.getDouble("seconds", 2));
                } else {
                    log.warning("proc.type không hợp lệ ở " + e + " (dùng IGNITE hoặc SLOW).");
                }
            }
            out.put(e, new ElementDef(e, s.getString("name", e.name()), s.getString("symbol", ""),
                    s.getString("color", "&f"), icon, s.getStringList("description"),
                    Math.max(0, s.getDouble("damage-per-level", 0.02)), Math.max(0, s.getDouble("undead-bonus", 0)),
                    List.copyOf(attrs), red, proc));
        }
        return out;
    }

    private AffinityTable parseAffinity(YamlConfiguration y) {
        double strong = y.getDouble("affinity.strong-multiplier", 1.25);
        double weak = y.getDouble("affinity.weak-multiplier", 0.85);
        Map<Element, Set<Element>> adv = new EnumMap<>(Element.class);
        ConfigurationSection s = y.getConfigurationSection("affinity.advantages");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                Element a = Element.parse(k);
                if (a == null) {
                    log.warning("affinity: nguyên tố không hợp lệ '" + k + "'.");
                    continue;
                }
                Set<Element> set = EnumSet.noneOf(Element.class);
                for (String v : s.getStringList(k)) {
                    Element b = Element.parse(v);
                    if (b == null || b == a) log.warning("affinity: mục không hợp lệ '" + v + "' trong " + k + ".");
                    else set.add(b);
                }
                adv.put(a, set);
            }
        }
        return new AffinityTable(strong, weak, adv);
    }

    private Map<EntityType, Element> parseMobElements(YamlConfiguration y) {
        Map<EntityType, Element> out = new HashMap<>();
        ConfigurationSection s = y.getConfigurationSection("mob-elements");
        if (s == null) return out;
        for (String k : s.getKeys(false)) {
            Element e = Element.parse(s.getString(k));
            EntityType t = entityType(k);
            if (e == null || t == null) log.warning("mob-elements: mục không hợp lệ '" + k + "'.");
            else out.put(t, e);
        }
        return out;
    }

    private Set<EntityType> parseEntityTypes(List<String> names, String where) {
        Set<EntityType> out = new HashSet<>();
        for (String n : names) {
            EntityType t = entityType(n);
            if (t == null) log.warning(where + ": loại thực thể không hợp lệ '" + n + "'.");
            else out.add(t);
        }
        return out;
    }

    private static EntityType entityType(String n) {
        try {
            return EntityType.valueOf(n.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Map<String, SkillDef> parseSkills(YamlConfiguration y, Map<Element, ElementDef> defs) {
        Map<String, SkillDef> out = new LinkedHashMap<>();
        ConfigurationSection root = y.getConfigurationSection("skills");
        if (root == null) {
            log.warning("skills.yml không có mục 'skills'.");
            return out;
        }
        Set<String> usedSlots = new HashSet<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Element el = Element.parse(s.getString("element"));
            if (el == null || !defs.containsKey(el)) {
                log.warning("Kỹ năng '" + id + "': nguyên tố không hợp lệ hoặc đang tắt - bỏ qua.");
                continue;
            }
            SkillType type;
            try {
                type = SkillType.valueOf(s.getString("type", "").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                log.warning("Kỹ năng '" + id + "': type không hợp lệ - bỏ qua.");
                continue;
            }
            int slot = s.getInt("slot", 0);
            if (slot < 1 || slot > 3) {
                log.warning("Kỹ năng '" + id + "': slot phải là 1-3 - bỏ qua.");
                continue;
            }
            if (!usedSlots.add(el + ":" + slot)) {
                log.warning("Kỹ năng '" + id + "': trùng slot " + slot + " của " + el + " - bỏ qua.");
                continue;
            }
            Material icon = Material.matchMaterial(s.getString("icon", "PAPER"));
            if (icon == null) icon = Material.PAPER;
            Map<String, Double> fx = new HashMap<>();
            ConfigurationSection fs = s.getConfigurationSection("effects");
            if (fs != null) {
                for (String k : fs.getKeys(false)) {
                    if (fs.isInt(k) || fs.isDouble(k) || fs.isLong(k)) fx.put(k, fs.getDouble(k));
                    else log.warning("Kỹ năng '" + id + "': effects." + k + " phải là số.");
                }
            }
            out.put(id, new SkillDef(id, el, slot, s.getString("name", id), icon, s.getStringList("description"), type,
                    s.getBoolean("ultimate", false), Math.max(1, s.getInt("unlock-level", 1)),
                    Math.max(0, s.getDouble("cooldown", 10)), Math.max(0, s.getDouble("damage", 0)),
                    Math.max(0, s.getDouble("range", 20)), Math.max(0, s.getDouble("radius", 0)),
                    Math.max(0, s.getDouble("power", 0)), Math.max(0, s.getDouble("duration", 0)),
                    Math.max(1, s.getInt("max-level", 5)), Math.max(0, s.getDouble("per-level-bonus", 0.1)),
                    Math.max(1, s.getInt("upgrade-cost", 1)), s.getString("particle", "CRIT"),
                    s.getString("sound", ""), Map.copyOf(fx)));
        }
        return out;
    }

    private Map<Element, List<SkillDef>> groupSkills(Map<String, SkillDef> all) {
        Map<Element, List<SkillDef>> out = new EnumMap<>(Element.class);
        for (SkillDef d : all.values()) out.computeIfAbsent(d.element(), k -> new ArrayList<>()).add(d);
        for (List<SkillDef> l : out.values()) l.sort(Comparator.comparingInt(SkillDef::slot));
        return out;
    }

    private Map<Material, Integer> parseMining() {
        Map<Material, Integer> out = new HashMap<>();
        ConfigurationSection s = main.getConfigurationSection("exp.sources.mining.blocks");
        if (s == null) return out;
        for (String k : s.getKeys(false)) {
            Material m = Material.matchMaterial(k);
            if (m == null || !m.isBlock()) log.warning("exp.sources.mining.blocks: khối không hợp lệ '" + k + "'.");
            else out.put(m, Math.max(0, s.getInt(k)));
        }
        return out;
    }

    private Map<EntityType, Integer> parseMobExp() {
        Map<EntityType, Integer> out = new HashMap<>();
        ConfigurationSection s = main.getConfigurationSection("exp.sources.mob-kill.mobs");
        if (s == null) return out;
        for (String k : s.getKeys(false)) {
            EntityType t = entityType(k);
            if (t == null) log.warning("exp.sources.mob-kill.mobs: loại không hợp lệ '" + k + "'.");
            else out.put(t, Math.max(0, s.getInt(k)));
        }
        return out;
    }

    private Map<Integer, LevelReward> parseRewards() {
        Map<Integer, LevelReward> out = new TreeMap<>();
        ConfigurationSection s = main.getConfigurationSection("level-rewards");
        if (s == null) return out;
        for (String k : s.getKeys(false)) {
            int lvl;
            try {
                lvl = Integer.parseInt(k);
            } catch (NumberFormatException e) {
                log.warning("level-rewards: '" + k + "' không phải số cấp.");
                continue;
            }
            ConfigurationSection r = s.getConfigurationSection(k);
            if (r == null) continue;
            out.put(lvl, new LevelReward(lvl, r.getString("message", ""), List.copyOf(r.getStringList("commands"))));
        }
        return Collections.unmodifiableMap(out);
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    // ------------------------------------------------------------------ Truy cập

    public FileConfiguration main() { return main; }
    public Messages messages() { return messages; }
    public ExpCurve curve() { return curve; }
    public AffinityTable affinity() { return affinity; }

    public ElementDef element(Element e) { return e == null ? null : elements.get(e); }
    public Set<Element> enabledElements() { return elements.keySet(); }

    public SkillDef skill(String id) { return skills.get(id); }
    public Map<String, SkillDef> allSkills() { return skills; }
    public List<SkillDef> skillsOf(Element e) { return skillsByElement.getOrDefault(e, List.of()); }

    public SkillDef skill(Element e, int slot) {
        for (SkillDef d : skillsOf(e)) if (d.slot() == slot) return d;
        return null;
    }

    public Element mobElement(EntityType t) { return mobElements.get(t); }
    public boolean isUndead(EntityType t) { return undead.contains(t); }
    public boolean isProtected(EntityType t) { return protectedTypes.contains(t); }
    public Integer miningExp(Material m) { return miningExp.get(m); }
    public Integer mobExp(EntityType t) { return mobExp.get(t); }
    public Map<Integer, LevelReward> levelRewards() { return levelRewards; }
    public boolean isWorldDisabled(String world) { return disabledWorlds.contains(world); }
}
