package fixture;

import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class MockWithoutExtensionFixture {
  @Mock Object unsupported;

  @Test
  void rejected() {}
}
