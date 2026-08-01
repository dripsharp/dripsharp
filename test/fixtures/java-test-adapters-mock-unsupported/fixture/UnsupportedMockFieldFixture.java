package fixture;

import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;

class UnsupportedMockFieldFixture {
  @InjectMocks Object unsupported;

  @Test
  void rejected() {}
}
