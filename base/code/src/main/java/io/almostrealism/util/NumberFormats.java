/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.util;

import java.text.DecimalFormat;

/**
 * Provides standard {@link DecimalFormat} instances for formatting numeric values in logging and display.
 *
 * <p>This utility class holds three pre-configured formats:</p>
 * <ul>
 *   <li>{@link #integerFormat} — formats values as plain integers (no decimal point)</li>
 *   <li>{@link #decimalFormat} — formats values in scientific notation with 5 significant digits</li>
 *   <li>{@link #displayFormat} — formats values for human display with up to 4 decimal places</li>
 * </ul>
 *
 * <p>The {@link #formatNumber(Number)} method selects the appropriate format automatically
 * based on the value's type and magnitude.</p>
 */
public class NumberFormats {
	/**
	 * A {@link DecimalFormat} that formats numbers as plain integers without a decimal point.
	 */
	private static class DefaultIntegerFormat extends DecimalFormat {
		/**
		 * Creates a format with at least one integer digit and no fraction digits.
		 */
		public DefaultIntegerFormat() {
			super("#");

			this.setMinimumIntegerDigits(1);
			this.setMinimumFractionDigits(0);
			this.setMaximumFractionDigits(0);
		}
	}

	/**
	 * A {@link DecimalFormat} that formats numbers in scientific notation with 5 significant digits.
	 */
	private static class DefaultDecimalFormat extends DecimalFormat {
		/**
		 * Creates a format using the pattern {@code "0.000##E0"}.
		 */
		public DefaultDecimalFormat() {
			super("0.000##E0");
		}
	}

	/**
	 * A {@link DecimalFormat} that formats numbers for human-readable display with up to 4 decimal places.
	 */
	private static class TruncatedDecimalFormat extends DecimalFormat {
		/**
		 * Creates a format using the pattern {@code "#####0.00##"}.
		 */
		public TruncatedDecimalFormat() {
			super("#####0.00##");
		}
	}

	/** An instance of DecimalFormat that can be used to format integer numbers. */
	public static final DecimalFormat integerFormat = new DefaultIntegerFormat();

	/** An instance of DecimalFormat that can be used to format decimal numbers. */
	public static final DecimalFormat decimalFormat = new DefaultDecimalFormat();

	/** An instance of DecimalFormat that can be used to format decimal numbers for display. */
	public static final DecimalFormat displayFormat = new TruncatedDecimalFormat();

	/**
	 * Formats a number for display, selecting the appropriate format based on type and magnitude.
	 *
	 * <p>Integer values use {@link #integerFormat}. Values in the range [0.0005, 500000]
	 * use {@link #displayFormat}. All other values use {@link #decimalFormat}.
	 *
	 * @param value the number to format, or {@code null}
	 * @return the formatted string, or {@code null} if {@code value} is {@code null}
	 */
	public static String formatNumber(Number value) {
		if (value == null) {
			return null;
		} else if (value instanceof Integer) {
			return integerFormat.format(value);
		} else if (value.doubleValue() >= 0.0005 && value.doubleValue() <= 500000) {
			return displayFormat.format(value);
		} else {
			return decimalFormat.format(value);
		}
	}
}
