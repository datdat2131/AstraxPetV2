package com.astrax.elementer.core;

import java.util.UUID;

public record TopEntry(UUID uuid, String name, String element, int level, long exp) {
}
