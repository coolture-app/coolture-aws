package com.coolture.common.dto.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ParticipationType {
    INTERESTED("INTERESTED"),
    TAKES_PART("TAKES_PART");

    private final String value;

    ParticipationType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ParticipationType from(String value) {
        for (ParticipationType t : values()) {
            if (t.value.equalsIgnoreCase(value)) return t;
        }
        throw new IllegalArgumentException("Unknown participation type: " + value);
    }
}
