package org.pkl.core;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class Version {
  private final int major;
  private final int minor;
  private final int patch;
  private final @Nullable String preRelease;

  public Version(int major, int minor, int patch, @Nullable String preRelease) {
    this.major = major;
    this.minor = minor;
    this.patch = patch;
    this.preRelease = preRelease;
  }

  public int hashCode() {
    return Objects.hash(major, minor, patch, preRelease);
  }

  public int smallerMajor(Version other) {
    return Math.min(major, other.major);
  }

  public int largerMajor(Version other) {
    return Math.max(major, other.major);
  }
}
