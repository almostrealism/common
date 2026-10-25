/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.texture.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.color.RGB;
import org.almostrealism.texture.StripeTexture;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link StripeTexture}, exercising the compiled color-producer graph it
 * builds for hard-edged and smooth stripes across each axis.
 */
public class StripeTextureTest extends TextureTestBase {
	/**
	 * Verifies the default hard-edged stripe selects the first color where the sine
	 * test is positive and the second color where it is not. At the default width
	 * 0.1, X=0.05 gives {@code sin(0.5*PI) > 0} (white) and X=0.15 gives
	 * {@code sin(1.5*PI) < 0} (black).
	 */
	@Test(timeout = 60000)
	public void hardStripeSelectsColorByAxisPosition() {
		StripeTexture texture = new StripeTexture();

		assertColor(1.0, 1.0, 1.0, texture.operate(new Vector(0.05, 0.0, 0.0)));
		assertColor(0.0, 0.0, 0.0, texture.operate(new Vector(0.15, 0.0, 0.0)));
	}

	/**
	 * Verifies custom stripe colors are honored by the producer.
	 */
	@Test(timeout = 60000)
	public void customColorsAreUsed() {
		StripeTexture texture = new StripeTexture();
		texture.setFirstColor(new RGB(0.2, 0.4, 0.6));
		texture.setSecondColor(new RGB(0.9, 0.8, 0.7));

		assertColor(0.2, 0.4, 0.6, texture.operate(new Vector(0.05, 0.0, 0.0)));
		assertColor(0.9, 0.8, 0.7, texture.operate(new Vector(0.15, 0.0, 0.0)));
	}

	/**
	 * Verifies smooth mode blends the two colors using the sine interpolation
	 * factor {@code t = (1 + sin(PI * value / width)) / 2}. At value 0, {@code t}
	 * is 0.5, so white and black blend to mid-gray.
	 */
	@Test(timeout = 60000)
	public void smoothStripeBlendsColors() {
		StripeTexture texture = new StripeTexture();
		texture.setSmooth(true);

		assertColor(0.5, 0.5, 0.5, texture.operate(new Vector(0.0, 0.0, 0.0)));
	}

	/**
	 * Verifies the Y-axis orientation drives stripe selection off the Y coordinate,
	 * ignoring X and Z.
	 */
	@Test(timeout = 60000)
	public void yAxisUsesYCoordinate() {
		StripeTexture texture = new StripeTexture();
		texture.setAxis(StripeTexture.YAxis);

		assertColor(1.0, 1.0, 1.0, texture.operate(new Vector(0.9, 0.05, 0.9)));
		assertColor(0.0, 0.0, 0.0, texture.operate(new Vector(0.9, 0.15, 0.9)));
	}

	/**
	 * Verifies the Z-axis orientation drives stripe selection off the Z coordinate,
	 * ignoring X and Y.
	 */
	@Test(timeout = 60000)
	public void zAxisUsesZCoordinate() {
		StripeTexture texture = new StripeTexture();
		texture.setAxis(StripeTexture.ZAxis);

		assertColor(1.0, 1.0, 1.0, texture.operate(new Vector(0.9, 0.9, 0.05)));
		assertColor(0.0, 0.0, 0.0, texture.operate(new Vector(0.9, 0.9, 0.15)));
	}

	/**
	 * Verifies that widening the stripe period changes which color a fixed point
	 * maps to: at width 0.1, X=0.15 is black; widening to 0.3 moves the same point
	 * into the first (white) stripe.
	 */
	@Test(timeout = 60000)
	public void stripeWidthChangesPeriod() {
		StripeTexture texture = new StripeTexture();
		assertColor(0.0, 0.0, 0.0, texture.operate(new Vector(0.15, 0.0, 0.0)));

		texture.setStripeWidth(0.3);
		assertColor(1.0, 1.0, 1.0, texture.operate(new Vector(0.15, 0.0, 0.0)));
	}

	/**
	 * Verifies a phase offset shifts the stripe pattern: an offset of 1.0 inverts
	 * the sign of the sine test at X=0.05, flipping white to black.
	 */
	@Test(timeout = 60000)
	public void offsetShiftsPattern() {
		StripeTexture texture = new StripeTexture();
		assertColor(1.0, 1.0, 1.0, texture.operate(new Vector(0.05, 0.0, 0.0)));

		texture.setOffset(1.0);
		assertColor(0.0, 0.0, 0.0, texture.operate(new Vector(0.05, 0.0, 0.0)));
	}

	/**
	 * Verifies the human-readable name.
	 */
	@Test(timeout = 30000)
	public void toStringIsStableLabel() {
		Assert.assertEquals("Stripe Texture", new StripeTexture().toString());
	}
}
