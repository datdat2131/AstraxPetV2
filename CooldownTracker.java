package com.astrax.elementer.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Theo dõi hồi chiêu theo người chơi + kỹ năng, và hồi chiêu chung. Thời gian truyền vào để dễ kiểm thử. Chỉ dùng trên main thread. */
public final class CooldownTracker {
    private final Map<UUID, Map<String, Long>> skillExpiry = new HashMap<>();
    private final Map<UUID, Long> globalExpiry = new HashMap<>();

    public long remaining(UUID id, String key, long nowMs) {
        Map<String, Long> m = skillExpiry.get(id);
        if (m == null) return 0;
        Long e = m.get(key);
        return e == null ? 0 : Math.max(0, e - nowMs);
    }

    public void start(UUID id, String key, long durationMs, long nowMs) {
        skillExpiry.computeIfAbsent(id, k -> new HashMap<>()).put(key, nowMs + Math.max(0, durationMs));
    }

    public long globalRemaining(UUID id, long nowMs) {
        Long e = globalExpiry.get(id);
        return e == null ? 0 : Math.max(0, e - nowMs);
    }

    public void startGlobal(UUID id, long durationMs, long nowMs) {
        globalExpiry.put(id, nowMs + Math.max(0, durationMs));
    }

    public void clear(UUID id) {
        skillExpiry.remove(id);
        globalExpiry.remove(id);
    }

    /** Dọn các mục đã hết hạn để tránh phình bộ nhớ. */
    public void purgeExpired(long nowMs) {
        globalExpiry.values().removeIf(e -> e <= nowMs);
        for (Iterator<Map<String, Long>> it = skillExpiry.values().iterator(); it.hasNext(); ) {
            Map<String, Long> m = it.next();
            m.values().removeIf(e -> e <= nowMs);
            if (m.isEmpty()) it.remove();
        }
    }
}
