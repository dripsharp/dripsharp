public final class InvalidNestedLabel {
  public static int invalid() {
    int result = 0;
    duplicate: {
      duplicate: {
        result++;
        break duplicate;
      }
    }
    return result;
  }
}
