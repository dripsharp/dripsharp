package com.example.token;

public enum Token {
    EXTERNAL,
    ABSTRACT,
    OPEN,
    LOCAL,
    HIDDEN,
    FIXED,
    CONST,
    WHEN,
    SWITCH,
    LINE_COMMENT,
    BLOCK_COMMENT,
    SEMICOLON,
    IDENTIFIER;

    public boolean isModifier() {
        return switch (this) {
            case EXTERNAL, ABSTRACT, OPEN, LOCAL, HIDDEN, FIXED, CONST -> true;
            default -> false;
        };
    }

    public boolean isKeyword() {
        return switch (this) {
            case ABSTRACT,
                    CONST,
                    EXTERNAL,
                    FIXED,
                    HIDDEN,
                    LOCAL,
                    OPEN,
                    WHEN,
                    SWITCH ->
                    true;
            default -> false;
        };
    }

    public boolean isAffix() {
        return switch (this) {
            case LINE_COMMENT, BLOCK_COMMENT, SEMICOLON -> true;
            default -> false;
        };
    }
}
