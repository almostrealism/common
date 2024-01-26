package org.almostrealism.util;

public interface TestSettings {
	boolean enableArgumentCountAssertions = false;

	boolean skipLongTests = TestUtils.getSkipLongTests();
}
