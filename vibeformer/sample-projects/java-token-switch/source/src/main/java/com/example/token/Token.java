package com.example.token;

public enum Token {
    ABSTRACT,
    OPEN,
    LOCAL,
    IDENTIFIER;

    public boolean isModifier() {
        return switch (this) {
            case ABSTRACT, OPEN, LOCAL -> true;
            default -> false;
        };
    }
}
