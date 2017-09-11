/*
 * Copyright 2016 Michael Murray
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
package org.almostrealism.util;

import java.awt.Color;
import java.text.DecimalFormat;
import java.util.Random;

/**
 * @author  Michael Murray
 */
public class Defaults {
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
			super("#");

			this.setMinimumIntegerDigits(1);
			this.setMinimumFractionDigits(1);
			this.setMaximumFractionDigits(340);
		}
	}

	private static class TruncatedDecimalFormat extends DecimalFormat {
		public TruncatedDecimalFormat() {
			super("####00.00");

			this.setMinimumIntegerDigits(1);
			this.setMaximumIntegerDigits(6);
			this.setMaximumFractionDigits(2);
			this.setMinimumFractionDigits(2);
		}
	}

	/** An instance of DecimalFormat that can be used to format integer numbers. */
	public static final DecimalFormat integerFormat = new DefaultIntegerFormat();

	/** An instance of DecimalFormat that can be used to format decimal numbers. */
	public static final DecimalFormat decimalFormat = new DefaultDecimalFormat();

	/** An instance of DecimalFormat that can be used to format decimal numbers for display. */
	public static final DecimalFormat displayFormat = new TruncatedDecimalFormat();

	/** AWT Color representing the primary 1 color for the default theme. */
	public static Color themePrimary1 = (new Color(0, 108, 175)).darker();

	/** AWT Color representing the primary 2 color for the default theme. */
	public static Color themePrimary2 = new Color(0, 108, 175);

	/** AWT Color representing the primary 3 color for the default theme. */
	public static Color themePrimary3 =  themePrimary2.brighter();

	/** AWT Color representing the secondary 1 color for the default theme. */
	public static Color themeSecondary1 = new Color(102, 103, 104);

	/** AWT Color representing the secondary 2 color for the default theme. */
	public static Color themeSecondary2 = new Color(170, 174, 177);

	/** AWT Color representing the secondary 3 color for the default theme. */
	public static Color themeSecondary3 = new Color(209, 212, 214);

	/** A general purpose instance of Random to be used whenever necessary. */
	  public static Random random = new Random();
}
