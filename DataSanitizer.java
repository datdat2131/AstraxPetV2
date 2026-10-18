package com.astrax.elementer.core;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Kiểm tra và sửa dữ liệu đọc từ DB (nguyên tố lạ, cấp âm, kỹ năng không tồn tại...) trước khi dùng. */
public final class DataSanitizer {
    private DataSanitizer() {}

    /**
     * @param maxSkillLevel trả về cấp tối đa của kỹ năng, hoặc null nếu kỹ năng không tồn tại
     */
    public static PlayerData sanitize(PlayerSnapshot s, String currentName, ExpCurve curve,
                                      Function<String, Integer> maxSkillLevel, Logger log) {
        PlayerData d = new PlayerData(s.uuid(), s.name());
        d.setName(currentName);
        Element el = Element.parse(s.element());
        if (s.element() != null && el == null) {
            log.log(Level.WARNING, "Dữ liệu của {0} có nguyên tố không hợp lệ ''{1}'' - đã đặt lại về chưa thức tỉnh.",
                    new Object[]{s.uuid(), s.element()});
        }
        d.setElement(el);
        int level = (int) DataSanitizerHelper.clamp(s.level(), 1, curve.maxLevel());
        if (level != s.level()) log.log(Level.WARNING, "Cấp độ không hợp lệ ({0}) của {1} - đã sửa thành {2}.",
                new Object[]{s.level(), s.uuid(), level});
        d.setLevel(level);
        long exp = Math.max(0, s.exp());
        long req = curve.required(level);
        if (req == 0) exp = 0; else if (exp >= req) exp = req - 1;
        d.setExp(exp);
        d.setSkillPoints(Math.max(0, s.skillPoints()));
        d.setSelectedSlot(s.selectedSlot());
        d.setDailyExp(s.dailyExp());
        d.setDailyDay(s.dailyDay());
        d.setAwakenedAt(s.awakenedAt());
        if (s.skills() != null) {
            for (Map.Entry<String, Integer> e : s.skills().entrySet()) {
                Integer max = maxSkillLevel.apply(e.getKey());
                if (max == null) {
                    log.log(Level.WARNING, "Bỏ qua kỹ năng không tồn tại ''{0}'' trong dữ liệu của {1}.",
                            new Object[]{e.getKey(), s.uuid()});
                    continue;
                }
                d.setSkillLevel(e.getKey(), (int) DataSanitizerHelper.clamp(e.getValue() == null ? 1 : e.getValue(), 1, max));
            }
        }
        d.clearDirty();
        return d;
    }

    /** Tạo mới dữ liệu trống cho người chơi lần đầu. */
    public static PlayerData fresh(UUID uuid, String name) {
        PlayerData d = new PlayerData(uuid, name);
        d.markDirty();
        return d;
    }
}

final class DataSanitizerHelper {
    private DataSanitizerHelper() {}
    static long clamp(long v, long min, long max) { return Math.max(min, Math.min(max, v)); }
}
