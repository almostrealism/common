/*
 * Copyright 2022 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.io;

import java.util.Optional;
import java.util.OptionalInt;

public class SystemUtils {
	private SystemUtils() { }

	public static boolean isAarch64() {
		return "aarch64".equals(System.getProperty("os.arch"));
	}

	public static boolean isMacOS() {
		return System.getProperty("os.name", "").contains("Mac OS X");
	}

	public static String getProperty(String key) {
		String value = System.getProperty(key);
		if (value == null) {
			value = System.getenv(key);
		}

		return value;
	}

	public static String getProperty(String key, String defaultValue) {
		String value = getProperty(key);
		if (value == null) {
			value = defaultValue;
		}

		return value;
	}

	public static OptionalInt getInt(String key) {
		String value = getProperty(key);
		if (value == null) {
			return OptionalInt.empty();
		}

		try {
			return OptionalInt.of(Integer.parseInt(value));
		} catch (NumberFormatException e) {
			Console.root().warn("Invalid value for " + key + ": " + value);
			return OptionalInt.empty();
		}
	}

	public static Optional<Boolean> isEnabled(String key) {
		String value = getProperty(key);

		if ("enabled".equalsIgnoreCase(value)) {
			return Optional.of(true);
		} else if ("disabled".equalsIgnoreCase(value)) {
			return Optional.of(false);
		} else {
			return Optional.empty();
		}
	}
}
