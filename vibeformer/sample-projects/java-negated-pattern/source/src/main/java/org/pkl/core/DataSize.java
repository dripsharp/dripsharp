package org.pkl.core;

import static org.pkl.core.DataSizeUnit.*;

public final class DataSize {
  private final double value;
  private final DataSizeUnit unit;

  public DataSize(double value) {
    this.value = value;
    this.unit = DataSizeUnit.BYTES;
  }

  public DataSize(double value, DataSizeUnit unit) {
    this.value = value;
    this.unit = unit;
  }

  public static DataSize ofBytes(double value) {
    return new DataSize(value, BYTES);
  }

  public boolean equals(Object obj) {
    if (!(obj instanceof DataSize other)) {
      return false;
    }

    return value == other.value;
  }

  public String label() {
    return value == 1 ? (long) value + ".byte." + unit : value + ".bytes." + unit;
  }
}
