package com.example.value;

public final class DisplayValueConverter implements ValueConverter<String> {
    @Override
    public String convertString(StringValue value) {
        return value.text();
    }

    @Override
    public String convertInt(IntValue value) {
        return Integer.toString(value.number());
    }
}
