package com.astrax.elementer.core;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Tương khắc: nếu A có lợi thế trước B thì A→B nhận hệ số "strong", B→A nhận hệ số "weak".
 * Các cặp còn lại là 1.0.
 */
public final class AffinityTable {
    private final double strong;
    private final double weak;
    private final Map<Element, Set<Element>> advantages = new EnumMap<>(Element.class);

    public AffinityTable(double strong, double weak, Map<Element, Set<Element>> advantages) {
        this.strong = strong;
        this.weak = weak;
        for (Map.Entry<Element, Set<Element>> e : advantages.entrySet()) {
            this.advantages.put(e.getKey(), EnumSet.copyOf(e.getValue().isEmpty() ? EnumSet.noneOf(Element.class) : e.getValue()));
        }
    }

    public double multiplier(Element attacker, Element defender) {
        if (attacker == null || defender == null) return 1.0;
        Set<Element> a = advantages.get(attacker);
        if (a != null && a.contains(defender)) return strong;
        Set<Element> d = advantages.get(defender);
        if (d != null && d.contains(attacker)) return weak;
        return 1.0;
    }

    public double strong() { return strong; }
    public double weak() { return weak; }
}
