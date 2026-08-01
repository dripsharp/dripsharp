package fixture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UnsupportedAssertionFixture {
  @Test
  void unsupportedAssertion() {
    assertThat("left").hasSameHashCodeAs("right");
  }
}
