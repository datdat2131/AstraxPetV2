package com.astrax.elementer.core;

import java.util.Locale;

/** Sáu nguyên tố chính. Dữ liệu hiển thị/chỉ số nằm trong elements.yml. */
public enum Element {
    FIRE, ICE, LIGHTNING, EARTH, WIND, LIGHT;

    /** Nhận cả tên tiếng Anh (FIRE) lẫn tiếng Việt (hoa, bang, loi, tho, phong, quang). Trả về null nếu không hợp lệ. */
    public static Element parse(String input) {
        if (input == null) return null;
        String n = input.trim().toUpperCase(Locale.ROOT);
        switch (n) {
            case "HOA": case "HỎA": return FIRE;
            case "BANG": case "BĂNG": return ICE;
            case "LOI": case "LÔI": return LIGHTNING;
            case "THO": case "THỔ": return EARTH;
            case "PHONG": return WIND;
            case "QUANG": return LIGHT;
            default:
                try {
                    return valueOf(n);
                } catch (IllegalArgumentException ex) {
                    return null;
                }
        }
    }
}
