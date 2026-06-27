package com.example.reflect;

public final class ReflectionForName {
  public static void main(String[] args) {
  }

  public static Class<?> load(String javaName) throws Exception {
    return Class.forName(javaName);
  }
}
