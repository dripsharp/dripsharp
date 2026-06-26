package org.pkl.core;

public final class DataSize {
  private final double value;

  public DataSize(double value) {
    this.value = value;
  }

  public boolean equals(Object obj) {
    if (!(obj instanceof DataSize other)) {
      return false;
    }

    return value == other.value;
  }

  public String label() {
    return value == 1 ? "byte" : "bytes";
  }
}
