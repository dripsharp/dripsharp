package fixture;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class TargetFacilityFixture {
  @Test
  void targetFacility() {
    JdbcDataSource source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:test");
  }
}
