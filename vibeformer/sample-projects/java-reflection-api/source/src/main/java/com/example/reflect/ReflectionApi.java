package com.example.reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;

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

  public static String className(Class<?> type) {
    return type.getName();
  }

  public static Type parentType(Class<?> type) {
    return type.getGenericSuperclass();
  }

  public static Type[] typeParameters(Class<?> type) {
    return type.getTypeParameters();
  }

  public static Type componentType(Class<?> type) {
    return type.getComponentType();
  }

  public static boolean isEnumType(Class<?> type) {
    return type.isEnum();
  }

  public static ClassLoader classLoader(Class<?> type) {
    return type.getClassLoader();
  }

  public static ClassLoader localLoader() {
    return ReflectionApi.class.getClassLoader();
  }

  public static boolean sameLoader(Class<?> type) {
    return same(type.getClassLoader(), ReflectionApi.class.getClassLoader());
  }

  public static String castString(Class<String> type, Object value) {
    return type.cast(value);
  }

  private static boolean same(ClassLoader left, ClassLoader right) {
    return left == right;
  }

  public static String reflectedTypeName(Type type) {
    return type.getTypeName();
  }

  public static Type[] actualArgs(ParameterizedType type) {
    return type.getActualTypeArguments();
  }

  public static Type rawType(ParameterizedType type) {
    return type.getRawType();
  }

  public static Type ownerType(ParameterizedType type) {
    return type.getOwnerType();
  }

  public static Parameter[] parameters(Constructor<?> constructor) {
    return constructor.getParameters();
  }

  public static String parameterName(Parameter parameter) {
    return parameter.isNamePresent() ? parameter.getName() : "";
  }
}
