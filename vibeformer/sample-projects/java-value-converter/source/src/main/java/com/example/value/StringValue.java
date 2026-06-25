package com.example.value;

public final class StringValue implements Value {
    private final String value;

    public StringValue(String value) {
        this.value = value;
    }

    public String text() {
        return value;
    }

    @Override
    public <T> T accept(ValueConverter<T> converter) {
        return converter.convertString(this);
    }
}
