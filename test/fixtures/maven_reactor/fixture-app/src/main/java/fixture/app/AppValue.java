package fixture.app;

import fixture.core.CoreValue;
import fixture.generated.GeneratedValue;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

public final class AppValue {
    private AppValue() {}

    public static String value(@Nullable String suffix) {
        return StringUtils.upperCase(
                CoreValue.value() + GeneratedValue.value() + suffix);
    }
}
