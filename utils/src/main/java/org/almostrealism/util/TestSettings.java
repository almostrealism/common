package org.almostrealism.util;

import org.almostrealism.io.SystemUtils;

public interface TestSettings {
	boolean enableArgumentCountAssertions = false;

	boolean skipLongTests = !SystemUtils.isEnabled("AR_LONG_TESTS").orElse(true);
}
