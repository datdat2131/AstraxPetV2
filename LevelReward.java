package com.astrax.elementer.config;

import java.util.List;

public record LevelReward(int level, String message, List<String> commands) {
}
