package com.example.token;

public final class TokenDemo {
    private TokenDemo() {
    }

    public static void main(String[] args) {
        Token.ABSTRACT.isModifier();
        Token.WHEN.isKeyword();
        Token.SEMICOLON.isAffix();
        Token.UNDERSCORE.text();
    }
}
