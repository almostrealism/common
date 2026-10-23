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

package org.almostrealism.raytrace.test;

import org.almostrealism.color.RGBA;
import org.almostrealism.raytrace.FogParameters;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Tests for {@link FogParameters}, verifying the documented default values (fog disabled,
 * 0.5 blend ratio, no fog color) and that the public configuration fields are mutable.
 */
public class FogParametersTest extends TestSuiteBase {

	/** Verifies fog is disabled by default: no color, zero density, and a 0.5 base blend ratio. */
	@Test(timeout = 5000)
	public void testDefaults() {
		FogParameters fog = new FogParameters();
		assertNull(fog.fogColor);
		assertEquals(0.0, fog.fogDensity, 0.0);
		assertEquals(0.5, fog.fogRatio, 0.0);
	}

	/** Verifies the configuration fields can be assigned and read back. */
	@Test(timeout = 5000)
	public void testConfiguration() {
		FogParameters fog = new FogParameters();
		RGBA color = new RGBA(0.8, 0.8, 0.9, 1.0);
		fog.fogColor = color;
		fog.fogDensity = 0.05;
		fog.fogRatio = 0.25;

		assertEquals(color, fog.fogColor);
		assertEquals(0.05, fog.fogDensity, 0.0);
		assertEquals(0.25, fog.fogRatio, 0.0);
	}
}
