package com.astrax.elementer.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Chuyển chuỗi màu kiểu & (và &#rrggbb) sang Component, tắt chữ nghiêng mặc định của item. */
public final class Text {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();

    private Text() {}

    public static Component c(String s) {
        return LEGACY.deserialize(s == null ? "" : s)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> lines(Collection<String> in) {
        List<Component> out = new ArrayList<>(in.size());
        for (String s : in) out.add(c(s));
        return out;
    }

    public static String plain(String legacy) {
        return PlainTextComponentSerializer.plainText().serialize(LEGACY.deserialize(legacy == null ? "" : legacy));
    }

    /** Làm tròn 1 chữ số thập phân để hiển thị. */
    public static String num(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
