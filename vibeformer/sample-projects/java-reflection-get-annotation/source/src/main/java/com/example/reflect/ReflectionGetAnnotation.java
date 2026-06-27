package com.example.reflect;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;

public final class ReflectionGetAnnotation {
  public static void main(String[] args) {
  }

  public static Annotation classAnnotation(Class<?> type, Class<? extends Annotation> annotationType) {
    return type.getAnnotation(annotationType);
  }

  public static Annotation constructorAnnotation(Constructor<?> constructor, Class<? extends Annotation> annotationType) {
    return constructor.getAnnotation(annotationType);
  }

  public static Annotation parameterAnnotation(Parameter parameter, Class<? extends Annotation> annotationType) {
    return parameter.getAnnotation(annotationType);
  }
}
