package com.example.value;

public final class IntValue implements Value {
    private final int value;

    public IntValue(int value) {
        this.value = value;
    }

    public int number() {
        return value;
    }

    @Override
    public <T> T accept(ValueConverter<T> converter) {
        return converter.convertInt(this);
    }
}
