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

package org.almostrealism.primitives.test;

import org.almostrealism.color.Light;
import org.almostrealism.color.PointLight;
import org.almostrealism.primitives.RectangularLight;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests {@link RectangularLight}: its dimensions and sample count, how it splits its intensity
 * across the point lights it samples, and its label.
 */
// TODO(review): getSamples tests fail on pre-existing bug — RectangularLight.getSamples NPEs because getTransform(true) returns null (see review-followup memory).
public class RectangularLightTest extends TestSuiteBase {
	/** The tolerance for comparing dimensions and intensities. */
	private static final double TOLERANCE = 1e-6;

	/** The dimension constructor records the width and height and defaults to a single sample. */
	@Test(timeout = 30000)
	public void dimensionsAndSampleCountAreRecorded() {
		RectangularLight light = new RectangularLight(4.0, 2.0);

		assertEquals(4.0, light.getWidth(), TOLERANCE);
		assertEquals(2.0, light.getHeight(), TOLERANCE);
		assertEquals(1, light.getSampleCount());

		light.setWidth(5.0);
		light.setHeight(3.0);
		light.setSampleCount(7);
		assertEquals(5.0, light.getWidth(), TOLERANCE);
		assertEquals(3.0, light.getHeight(), TOLERANCE);
		assertEquals(7, light.getSampleCount());

		assertEquals("Rectangular Light", light.toString());
	}

	/** The intensity is stored and split evenly across the requested number of samples. */
	@Test(timeout = 30000)
	public void samplesSplitIntensity() {
		RectangularLight light = new RectangularLight(4.0, 2.0);
		light.setIntensity(9.0);
		assertEquals(9.0, light.getIntensity(), TOLERANCE);

		Light[] samples = light.getSamples(3);
		assertEquals(3, samples.length);
		for (Light l : samples) {
			assertEquals(3.0, ((PointLight) l).getIntensity(), TOLERANCE);
		}
	}

	/** The no-argument sampler uses the configured default sample count. */
	@Test(timeout = 30000)
	public void defaultSampleCountDrivesSampleArray() {
		RectangularLight light = new RectangularLight(4.0, 2.0);
		light.setIntensity(2.0);
		light.setSampleCount(2);

		Light[] samples = light.getSamples();
		assertEquals(2, samples.length);
		for (Light l : samples) {
			assertEquals(1.0, ((PointLight) l).getIntensity(), TOLERANCE);
		}
	}
}
