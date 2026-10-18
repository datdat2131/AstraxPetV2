package com.astrax.elementer.config;

import com.astrax.elementer.core.Element;
import com.astrax.elementer.core.SkillType;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;

public record SkillDef(
        String id,
        Element element,
        int slot,
        String name,
        Material icon,
        List<String> description,
        SkillType type,
        boolean ultimate,
        int unlockLevel,
        double cooldown,
        double damage,
        double range,
        double radius,
        double power,
        double duration,
        int maxLevel,
        double perLevelBonus,
        int upgradeCost,
        String particle,
        String sound,
        Map<String, Double> effects) {

    public double fx(String key, double def) {
        Double v = effects.get(key);
        return v == null ? def : v;
    }
}
