package com.example.reflect;

import java.lang.reflect.Method;

public final class ReflectionMethodInvoke {
  public static void main(String[] args) {
  }

  public static Object call(Method method, Object target) throws Exception {
    return method.invoke(target);
  }

  public static Object callWithValue(Method method, Object target, Object value) throws Exception {
    return method.invoke(target, value);
  }
}
