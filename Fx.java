package com.astrax.elementer.util;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Hiệu ứng hình ảnh/âm thanh đọc từ cấu hình; tên sai bị bỏ qua êm thay vì gây lỗi. */
public final class Fx {
    private static final Map<String, Particle> PARTICLES = new ConcurrentHashMap<>();
    private static final Map<String, Sound> SOUNDS = new ConcurrentHashMap<>();
    private static final Particle NONE_P = Particle.CRIT;
    private static volatile boolean enabled = true;

    private Fx() {}

    public static void setEnabled(boolean e) { enabled = e; }

    private static Particle particle(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.trim().toUpperCase(Locale.ROOT);
        Particle p = PARTICLES.get(key);
        if (p == null) {
            try {
                Particle v = Particle.valueOf(key);
                // chỉ dùng loại particle không cần dữ liệu đi kèm (DUST, BLOCK... sẽ gây lỗi)
                p = v.getDataType() == Void.class ? v : NONE_P;
            } catch (IllegalArgumentException e) {
                p = NONE_P;
            }
            PARTICLES.put(key, p);
        }
        return p;
    }

    public static void particle(Location loc, String name, int count, double dx, double dy, double dz, double speed) {
        if (!enabled || loc == null || loc.getWorld() == null) return;
        Particle p = particle(name);
        if (p == null) return;
        loc.getWorld().spawnParticle(p, loc, count, dx, dy, dz, speed);
    }

    /** Vòng tròn hạt nằm ngang quanh tâm. */
    public static void ring(Location center, double radius, String name, int points) {
        if (!enabled || center == null || center.getWorld() == null) return;
        Particle p = particle(name);
        if (p == null) return;
        int n = Math.max(8, Math.min(points, 64));
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            Location l = center.clone().add(Math.cos(a) * radius, 0.2, Math.sin(a) * radius);
            center.getWorld().spawnParticle(p, l, 1, 0, 0, 0, 0);
        }
    }

    public static void sound(Location loc, String key, float volume, float pitch) {
        if (loc == null || loc.getWorld() == null || key == null || key.isBlank()) return;
        Sound s = SOUNDS.get(key);
        if (s == null) {
            NamespacedKey nk = NamespacedKey.fromString(key.trim().toLowerCase(Locale.ROOT));
            s = nk == null ? null : Registry.SOUNDS.get(nk);
            if (s == null) return;
            SOUNDS.put(key, s);
        }
        loc.getWorld().playSound(loc, s, SoundCategory.PLAYERS, volume, pitch);
    }
}
