package com.astrax.elementer.config;

import com.astrax.elementer.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** messages.yml: placeholder dạng {ten}. Khóa thiếu hiển thị rõ ràng thay vì gây lỗi. */
public final class Messages {
    private volatile YamlConfiguration yml = new YamlConfiguration();

    public void load(File file) {
        this.yml = YamlConfiguration.loadConfiguration(file);
    }

    public String raw(String key) {
        String v = yml.getString(key);
        return v == null ? "&c[thiếu tin nhắn: " + key + "]" : v;
    }

    public List<String> rawList(String key) {
        List<String> l = yml.getStringList(key);
        return l.isEmpty() ? List.of("&c[thiếu tin nhắn: " + key + "]") : l;
    }

    public static String format(String s, Object... kv) {
        String out = s;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out = out.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        }
        return out;
    }

    public Component get(String key, Object... kv) {
        return Text.c(format(raw(key), kv));
    }

    public Component prefixed(String key, Object... kv) {
        return Text.c(raw("prefix") + format(raw(key), kv));
    }

    public void send(CommandSender to, String key, Object... kv) {
        to.sendMessage(prefixed(key, kv));
    }

    public void sendList(CommandSender to, String key, Object... kv) {
        for (String line : rawList(key)) to.sendMessage(Text.c(format(line, kv)));
    }

    public void actionBar(Player p, String key, Object... kv) {
        p.sendActionBar(get(key, kv));
    }

    public List<String> formatted(String key, Object... kv) {
        List<String> out = new ArrayList<>();
        for (String l : rawList(key)) out.add(format(l, kv));
        return out;
    }
}
