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

public class NumberFormats {
	private static class DefaultIntegerFormat extends DecimalFormat {
		public DefaultIntegerFormat() {
			super("#");

			this.setMinimumIntegerDigits(1);
			this.setMinimumFractionDigits(0);
			this.setMaximumFractionDigits(0);
		}
	}

	private static class DefaultDecimalFormat extends DecimalFormat {
		public DefaultDecimalFormat() {
			super("0.000##E0");
		}
	}

	private static class TruncatedDecimalFormat extends DecimalFormat {
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
