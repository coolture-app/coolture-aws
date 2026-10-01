package com.coolture.common.dto.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ReactionType {
    LIKE("LIKE"),
    DISLIKE("DISLIKE");

    private final String value;

    ReactionType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ReactionType from(String value) {
        for (ReactionType t : values()) {
            if (t.value.equalsIgnoreCase(value)) return t;
        }
        throw new IllegalArgumentException("Unknown reaction type: " + value);
    }
}
