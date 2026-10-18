package com.astrax.elementer.config;

import org.bukkit.attribute.AttributeModifier;

/** Một modifier attribute nội tại: giá trị = base + perLevel × (cấp - 1). */
public record AttrDef(String attribute, AttributeModifier.Operation operation, double base, double perLevel) {
    public double amount(int level) {
        return base + perLevel * Math.max(0, level - 1);
    }
}
