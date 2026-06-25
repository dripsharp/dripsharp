package com.example.value;

public interface ValueConverter<T> {
    T convertString(StringValue value);

    T convertInt(IntValue value);

    default T convert(Object value) {
        if (value instanceof Value v) {
            return v.accept(this);
        }

        throw new IllegalArgumentException("Unsupported value: " + value);
    }
}
