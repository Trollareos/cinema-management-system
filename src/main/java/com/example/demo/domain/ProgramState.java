package com.example.demo.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ProgramState {
    CREATED,
    SUBMISSION,
    ASSIGNMENT,
    REVIEW,
    SCHEDULING,

    /**
     * Internally we keep FINAL_SUBMISSION for DB stability,
     * but the API must expose/accept FINAL_PUBLICATION per assignment text.
     */
    FINAL_SUBMISSION,

    DECISION,
    ANNOUNCED;

    @JsonCreator
    public static ProgramState fromJson(String value) {
        if (value == null) return null;
        String v = value.trim().toUpperCase();

        // Accept both names (assignment says FINAL_PUBLICATION)
        if ("FINAL_PUBLICATION".equals(v)) return FINAL_SUBMISSION;
        if ("FINAL_SUBMISSION".equals(v)) return FINAL_SUBMISSION;

        return ProgramState.valueOf(v);
    }

    @JsonValue
    public String toJson() {
        // Expose FINAL_PUBLICATION in JSON
        if (this == FINAL_SUBMISSION) return "FINAL_PUBLICATION";
        return this.name();
    }
}
