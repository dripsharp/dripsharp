package com.example.value;

public interface Value {
    <T> T accept(ValueConverter<T> converter);
}
