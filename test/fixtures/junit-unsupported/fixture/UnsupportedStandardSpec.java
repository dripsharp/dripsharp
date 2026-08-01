package fixture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnsupportedStandardSpec {
  @Test
  void test() {}
}
