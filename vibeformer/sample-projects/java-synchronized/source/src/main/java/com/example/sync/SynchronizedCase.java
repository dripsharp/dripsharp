package com.example.sync;

public final class SynchronizedCase {
  private int value;

  public static void main(String[] args) {
  }

  public synchronized int increment() {
    value = value + 1;
    return value;
  }

  public int add(int amount) {
    synchronized (this) {
      value = value + amount;
      return value;
    }
  }

  public static synchronized int zero() {
    return 0;
  }
}
