/*
 * Copyright 2024 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.util;

import org.almostrealism.hardware.cl.CLMemoryProvider;
import org.almostrealism.hardware.metal.MetalMemoryProvider;
import org.almostrealism.io.SystemUtils;

import java.util.Objects;

public class TestUtils implements TestSettings {
	public static final String PIPELINE = "pipeline";

	static {
		if (CLMemoryProvider.enableWarnings)
			CLMemoryProvider.enableWarnings = !skipLongTests;
		if (MetalMemoryProvider.enableWarnings)
			MetalMemoryProvider.enableWarnings = !skipLongTests;
	}

	public static boolean getSkipLongTests() {
		if (Objects.equals(getTestProfile(), PIPELINE)) return false;

		return !SystemUtils.isEnabled("AR_LONG_TESTS").orElse(true);
	}

	public static boolean getTrainTests() {
		if (Objects.equals(getTestProfile(), PIPELINE)) return false;

		return SystemUtils.isEnabled("AR_TRAIN_TESTS").orElse(false);
	}

	public static String getTestProfile() { return SystemUtils.getProperty("AR_TEST_PROFILE", "default"); }
}
