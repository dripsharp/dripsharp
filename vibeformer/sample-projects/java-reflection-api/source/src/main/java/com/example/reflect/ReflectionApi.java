package com.example.reflect;

import java.lang.reflect.Modifier;

public final class ReflectionApi {
  public static void main(String[] args) {
  }

  public static boolean canInstantiate(Class<?> requestedType, Class<?> implementationType) {
    return requestedType.isAssignableFrom(implementationType)
        && !Modifier.isAbstract(implementationType.getModifiers())
        && !implementationType.isArray()
        && !implementationType.isPrimitive();
  }

  public static String typeLabel() {
    return String.class.getTypeName() + ":" + String.class.getSimpleName();
  }
}
