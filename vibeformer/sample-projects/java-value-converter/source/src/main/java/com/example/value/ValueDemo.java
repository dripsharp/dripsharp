package com.example.value;

public final class ValueDemo {
    private ValueDemo() {
    }

    public static void main(String[] args) {
        ValueConverter<String> converter = new DisplayValueConverter();
        Value name = new StringValue("pkl");
        Value count = new IntValue(args.length);

        System.out.println(converter.convert(name));
        System.out.println(converter.convert(count));
    }
}
