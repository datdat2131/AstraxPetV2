package com.astrax.elementer.core;

/** Các kiểu hành vi kỹ năng được SkillService hỗ trợ. */
public enum SkillType {
    /** Tia/đạn theo hướng nhìn, có thể xuyên nhiều mục tiêu hoặc nổ lan. */
    PROJECTILE,
    /** Vùng quanh người dùng (có thể lặp nhiều nhịp). */
    AOE_SELF,
    /** Vùng tại điểm đang nhắm (có thể trễ và lặp nhiều nhịp). */
    AOE_TARGET,
    /** Đánh một mục tiêu đang nhắm. */
    STRIKE_TARGET,
    /** Lướt về phía trước. */
    DASH,
    /** Lá chắn hấp thụ sát thương. */
    SHIELD,
    /** Hồi máu vùng. */
    HEAL_AREA
}
