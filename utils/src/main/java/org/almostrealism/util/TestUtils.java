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
import org.almostrealism.io.Console;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.io.SystemUtils;

import java.util.Objects;

public class TestUtils implements TestSettings {
	public static final String PIPELINE = "pipeline";

	static {
		if (CLMemoryProvider.enableWarnings)
			CLMemoryProvider.enableWarnings = !skipLongTests;
		if (MetalMemoryProvider.enableWarnings)
			MetalMemoryProvider.enableWarnings = !skipLongTests;

		Console.root().addListener(OutputFeatures.fileOutput("results/logs/test.out"));
	}

	public static boolean getSkipLongTests() {
		if (Objects.equals(getTestProfile(), PIPELINE)) return false;

		return !SystemUtils.isEnabled("AR_LONG_TESTS").orElse(true);
	}

	public static boolean getSkipKnownIssues() {
		if (Objects.equals(getTestProfile(), PIPELINE)) return true;
		if (getSkipLongTests()) return true;

		return !SystemUtils.isEnabled("AR_KNOWN_ISSUES").orElse(true);
	}

	public static boolean getTrainTests() {
		if (Objects.equals(getTestProfile(), PIPELINE)) return false;

		return SystemUtils.isEnabled("AR_TRAIN_TESTS").orElse(false);
	}

	public static boolean getVerboseLogs() {
		if (Objects.equals(getTestProfile(), PIPELINE)) return false;

		return !getSkipLongTests() && !getSkipKnownIssues();
	}

	public static int getTestDepth() {
		if (Objects.equals(getTestProfile(), PIPELINE)) return Integer.MAX_VALUE;

		String depth = SystemUtils.getProperty("AR_TEST_DEPTH");
		if (depth == null) return Integer.MAX_VALUE;
		return Integer.parseInt(depth);
	}

	public static String getTestProfile() { return SystemUtils.getProperty("AR_TEST_PROFILE", "default"); }
}
