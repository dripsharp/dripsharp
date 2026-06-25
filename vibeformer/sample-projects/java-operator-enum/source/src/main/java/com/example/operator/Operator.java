package com.example.operator;

public enum Operator {
    NULL_COALESCE(1, false),
    PIPE(2, true);

    private final int prec;
    private final boolean leftAssoc;

    Operator(int prec, boolean leftAssoc) {
        this.prec = prec;
        this.leftAssoc = leftAssoc;
    }

    public int getPrec() {
        return prec;
    }

    public boolean isLeftAssoc() {
        return leftAssoc;
    }

    public static Operator byName(String name) {
        return switch (name) {
            case "??" -> NULL_COALESCE;
            case "|>" -> PIPE;
            default -> throw new RuntimeException("Unknown operator: " + name);
        };
    }
}
