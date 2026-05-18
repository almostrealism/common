/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.render.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.color.Light;
import org.almostrealism.color.PointLight;
import org.almostrealism.color.RGB;
import org.almostrealism.light.PlanarLight;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that {@link PlanarLight} correctly stores and returns its color.
 * Before the fix, {@code setColor} was a no-op and {@code getColor} always
 * returned null, which broke {@code getColorAt} and caused {@code getSamples}
 * to ignore the configured color.
 */
public class PlanarLightColorTest extends TestSuiteBase {

	/**
	 * Verifies that {@link PlanarLight#getColor()} returns the color
	 * that was set via {@link PlanarLight#setColor(RGB)}.
	 */
	@Test(timeout = 5000)
	public void setAndGetColor() {
		PlanarLight light = createPlanarLight();
		RGB red = new RGB(1.0, 0.0, 0.0);
		light.setColor(red);

		RGB result = light.getColor();
		Assert.assertNotNull("getColor() should not return null after setColor()", result);
		Assert.assertEquals(1.0, result.getRed(), 1e-10);
		Assert.assertEquals(0.0, result.getGreen(), 1e-10);
		Assert.assertEquals(0.0, result.getBlue(), 1e-10);
	}

	/**
	 * Verifies that {@link PlanarLight#getColor()} returns null when
	 * no color has been set, preserving the default behavior.
	 */
	@Test(timeout = 5000)
	public void getColorDefaultIsNull() {
		PlanarLight light = createPlanarLight();
		Assert.assertNull("Default color should be null before setColor()", light.getColor());
	}

	/**
	 * Verifies that calling {@link PlanarLight#setColor(RGB)} multiple
	 * times updates the stored color correctly.
	 */
	@Test(timeout = 5000)
	public void setColorOverwritesPrevious() {
		PlanarLight light = createPlanarLight();
		light.setColor(new RGB(1.0, 0.0, 0.0));
		light.setColor(new RGB(0.0, 0.0, 1.0));

		RGB result = light.getColor();
		Assert.assertNotNull(result);
		Assert.assertEquals(0.0, result.getRed(), 1e-10);
		Assert.assertEquals(0.0, result.getGreen(), 1e-10);
		Assert.assertEquals(1.0, result.getBlue(), 1e-10);
	}

	/**
	 * Verifies that {@link PlanarLight#getSamples(int)} uses the configured
	 * color instead of hardcoded white.
	 */
	@Test(timeout = 5000)
	public void getSamplesUsesConfiguredColor() {
		PlanarLight light = createPlanarLight();
		RGB green = new RGB(0.0, 1.0, 0.0);
		light.setColor(green);

		Light[] samples = light.getSamples(5);
		Assert.assertEquals(5, samples.length);

		for (Light sample : samples) {
			RGB sampleColor = sample.getColor();
			Assert.assertNotNull("Sample light color should not be null", sampleColor);
			Assert.assertEquals(0.0, sampleColor.getRed(), 1e-10);
			Assert.assertEquals(1.0, sampleColor.getGreen(), 1e-10);
			Assert.assertEquals(0.0, sampleColor.getBlue(), 1e-10);
		}
	}

	/**
	 * Verifies that {@link PlanarLight#getSamples(int)} falls back to
	 * white when no color has been configured.
	 */
	@Test(timeout = 5000)
	public void getSamplesFallsBackToWhite() {
		PlanarLight light = createPlanarLight();

		Light[] samples = light.getSamples(3);
		for (Light sample : samples) {
			RGB sampleColor = sample.getColor();
			Assert.assertNotNull(sampleColor);
			Assert.assertEquals(1.0, sampleColor.getRed(), 1e-10);
			Assert.assertEquals(1.0, sampleColor.getGreen(), 1e-10);
			Assert.assertEquals(1.0, sampleColor.getBlue(), 1e-10);
		}
	}

	/**
	 * Creates a minimally configured {@link PlanarLight} for testing.
	 */
	private PlanarLight createPlanarLight() {
		PlanarLight light = new PlanarLight();
		light.setLocation(new Vector(0, 0, 0));
		light.setSurfaceNormal(new Vector(0, 0, 1));
		light.setOrientation(new Vector(0, 1, 0));
		light.setWidth(1.0);
		light.setHeight(1.0);
		return light;
	}
}
