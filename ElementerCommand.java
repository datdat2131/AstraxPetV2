package com.astrax.elementer.command;

import com.astrax.elementer.config.ElementConfig;
import com.astrax.elementer.config.ElementDef;
import com.astrax.elementer.config.Messages;
import com.astrax.elementer.config.SkillDef;
import com.astrax.elementer.core.Element;
import com.astrax.elementer.core.PlayerData;
import com.astrax.elementer.core.TopEntry;
import com.astrax.elementer.gui.ElementGUI;
import com.astrax.elementer.service.ElementService;
import com.astrax.elementer.service.SkillService;
import com.astrax.elementer.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ElementerCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBS = List.of("help", "info", "skills", "stats", "top", "cast", "select", "awaken");

    private final ElementConfig cfg;
    private final ElementService elements;
    private final SkillService skills;
    private final ElementGUI gui;

    public ElementerCommand(ElementConfig cfg, ElementService elements, SkillService skills, ElementGUI gui) {
        this.cfg = cfg;
        this.elements = elements;
        this.skills = skills;
        this.gui = gui;
    }

    private boolean allowed(CommandSender s, String perm) {
        if (s.hasPermission(perm)) return true;
        cfg.messages().send(s, "no-permission");
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        Messages m = cfg.messages();

        if (sub.equals("top")) { // bảng xếp hạng dùng được cả từ console
            if (!allowed(sender, "elementer.command.top")) return true;
            sendTop(sender);
            return true;
        }
        if (sub.equals("help")) {
            m.sendList(sender, "help");
            return true;
        }
        if (!(sender instanceof Player p)) {
            m.send(sender, "player-only");
            return true;
        }

        switch (sub) {
            case "menu" -> {
                if (allowed(p, "elementer.command.main")) gui.openMain(p);
            }
            case "info" -> {
                if (!allowed(p, "elementer.command.info")) return true;
                PlayerData d = data(p);
                if (d != null) for (String l : elements.infoLines(d)) p.sendMessage(Text.c(l));
            }
            case "stats" -> {
                if (!allowed(p, "elementer.command.stats")) return true;
                PlayerData d = data(p);
                if (d != null) for (String l : elements.statsLines(d)) p.sendMessage(Text.c(l));
            }
            case "skills" -> {
                if (!allowed(p, "elementer.command.skills")) return true;
                PlayerData d = data(p);
                if (d != null) sendSkills(p, d);
            }
            case "cast" -> {
                if (!allowed(p, "elementer.command.cast")) return true;
                Integer slot = slotArg(p, args);
                if (slot != null) skills.castAndNotify(p, slot);
            }
            case "select" -> {
                if (!allowed(p, "elementer.command.select")) return true;
                PlayerData d = data(p);
                Integer slot = slotArg(p, args);
                if (d == null || slot == null) return true;
                SkillDef s = cfg.skill(d.getElement(), slot);
                if (s == null) {
                    m.send(p, "cast.no-skill");
                } else if (d.getLevel() < s.unlockLevel()) {
                    m.send(p, "cast.locked", "unlock", s.unlockLevel());
                } else {
                    d.setSelectedSlot(slot);
                    m.send(p, "cast.selected", "slot", slot, "name", s.name());
                }
            }
            case "awaken" -> {
                if (!allowed(p, "elementer.command.awaken")) return true;
                Element e = args.length < 2 ? null : Element.parse(args[1]);
                if (e == null) {
                    m.send(p, "awaken.invalid");
                    return true;
                }
                ElementDef def = cfg.element(e);
                switch (elements.awaken(p, e)) {
                    case OK -> m.send(p, "awaken.success", "element", def == null ? e.name() : def.colored());
                    case ALREADY -> m.send(p, "awaken.already");
                    case DISABLED -> m.send(p, "awaken.disabled");
                    case NO_DATA -> m.send(p, "data-loading");
                }
            }
            default -> m.send(p, "unknown-command");
        }
        return true;
    }

    /** Dữ liệu người chơi đã thức tỉnh, hoặc null (đã báo lỗi cho người chơi). */
    private PlayerData data(Player p) {
        PlayerData d = elements.get(p);
        if (d == null) {
            cfg.messages().send(p, "data-loading");
            return null;
        }
        if (d.getElement() == null) {
            cfg.messages().send(p, "not-awakened");
            return null;
        }
        return d;
    }

    private Integer slotArg(Player p, String[] args) {
        if (args.length >= 2) {
            try {
                int v = Integer.parseInt(args[1]);
                if (v >= 1 && v <= 3) return v;
            } catch (NumberFormatException ignored) {
                // rơi xuống dùng kỹ năng đang chọn / báo lỗi
            }
            cfg.messages().send(p, "cast.no-skill");
            return null;
        }
        PlayerData d = elements.get(p);
        return d == null ? 1 : d.getSelectedSlot();
    }

    private void sendSkills(Player p, PlayerData d) {
        ElementDef def = cfg.element(d.getElement());
        Messages m = cfg.messages();
        p.sendMessage(Text.c(Messages.format(m.raw("skills-header"), "element", def == null ? "?" : def.colored())));
        for (SkillDef s : cfg.skillsOf(d.getElement())) {
            String status = d.getLevel() < s.unlockLevel()
                    ? Messages.format(m.raw("skill-status-locked"), "unlock", s.unlockLevel())
                    : (d.getSelectedSlot() == s.slot() ? m.raw("skill-status-selected") : m.raw("skill-status-ready"));
            p.sendMessage(Text.c(Messages.format(m.raw("skills-line"), "slot", s.slot(), "name", s.name(),
                    "level", skills.levelOf(d, s), "max", s.maxLevel(), "status", status)));
        }
    }

    private void sendTop(CommandSender to) {
        Plugin pl = Bukkit.getPluginManager().getPlugin("AstraxElementer");
        elements.top(10).whenComplete((list, err) -> {
            if (pl == null || !pl.isEnabled()) return;
            Bukkit.getScheduler().runTask(pl, () -> {
                Messages m = cfg.messages();
                if (err != null) return;
                to.sendMessage(Text.c(m.raw("top.header")));
                if (list.isEmpty()) to.sendMessage(Text.c(m.raw("top.empty")));
                int rank = 1;
                for (TopEntry t : list) {
                    Element e = Element.parse(t.element());
                    ElementDef def = e == null ? null : cfg.element(e);
                    to.sendMessage(Text.c(Messages.format(m.raw("top.line"), "rank", rank++, "name",
                            t.name() == null ? "?" : t.name(), "element", def == null ? "?" : def.colored(),
                            "level", t.level(), "exp", t.exp())));
                }
            });
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("cast") || sub.equals("select")) out.addAll(List.of("1", "2", "3"));
            if (sub.equals("awaken")) out.addAll(List.of("hoa", "bang", "loi", "tho", "phong", "quang"));
        }
        return out;
    }
}
