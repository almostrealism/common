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

package org.almostrealism.color.test;

import org.almostrealism.color.AmbientLight;
import org.almostrealism.color.Light;
import org.almostrealism.color.PointLight;
import org.almostrealism.color.RGB;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link Light#getRadiance()}, the color of a light scaled by its intensity.
 */
public class LightRadianceTest extends TestSuiteBase {

	/** Verifies that each channel of an ambient light's radiance is its color times its intensity. */
	@Test(timeout = 5000)
	public void ambientRadianceScalesColor() {
		AmbientLight light = new AmbientLight(0.5, new RGB(0.2, 0.4, 0.8));
		assertRadiance(light.getRadiance(), 0.1, 0.2, 0.4);
	}

	/** Verifies that each channel of a point light's radiance is its color times its intensity. */
	@Test(timeout = 5000)
	public void pointRadianceScalesColor() {
		PointLight light = new PointLight(3.0, new RGB(0.1, 0.5, 1.0));
		assertRadiance(light.getRadiance(), 0.3, 1.5, 3.0);
	}

	/** Verifies that a light with zero intensity has black radiance whatever its color. */
	@Test(timeout = 5000)
	public void zeroIntensityIsBlack() {
		PointLight light = new PointLight(0.0, new RGB(1.0, 0.7, 0.3));
		assertRadiance(light.getRadiance(), 0.0, 0.0, 0.0);
	}

	/** Verifies that computing the radiance does not modify the light's own color. */
	@Test(timeout = 5000)
	public void radianceDoesNotModifyColor() {
		AmbientLight light = new AmbientLight(4.0, new RGB(0.25, 0.5, 0.75));
		light.getRadiance();
		assertRadiance(light.getColor(), 0.25, 0.5, 0.75);
	}

	/** Verifies that the radiance follows later changes to the intensity and color. */
	@Test(timeout = 5000)
	public void radianceTracksIntensityAndColorChanges() {
		PointLight light = new PointLight(1.0, new RGB(1.0, 1.0, 1.0));
		light.setIntensity(2.0);
		light.setColor(new RGB(0.5, 0.0, 0.25));
		assertRadiance(light.getRadiance(), 1.0, 0.0, 0.5);
	}

	/** Asserts that the channels of {@code color} match the expected values. */
	private static void assertRadiance(RGB color, double red, double green, double blue) {
		Assert.assertEquals(red, color.getRed(), 1e-6);
		Assert.assertEquals(green, color.getGreen(), 1e-6);
		Assert.assertEquals(blue, color.getBlue(), 1e-6);
	}
}
