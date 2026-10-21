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

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.geometry.Ray;
import org.almostrealism.raytrace.FogParameters;
import org.almostrealism.raytrace.RayIntersectionEngine;
import org.almostrealism.space.Scene;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

/**
 * Tests for {@link RayIntersectionEngine}, verifying that tracing a ray through an empty scene
 * yields a (lazy) color {@link Producer} rather than null — the engine assembles a lighting
 * aggregator and wraps it, without requiring any surfaces to be present.
 */
public class RayIntersectionEngineTest extends TestSuiteBase {

	/**
	 * Verifies {@link RayIntersectionEngine#trace} returns a non-null color producer for an empty
	 * scene (no surfaces or lights to aggregate).
	 */
	@Test(timeout = 30000)
	public void testTraceEmptySceneProducesProducer() {
		Scene<ShadableSurface> scene = new Scene<>();
		RayIntersectionEngine engine = new RayIntersectionEngine(scene, new FogParameters());

		Producer<Ray> ray = () -> args -> new Ray(new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, -1.0));
		assertNotNull(engine.trace(ray));
	}
}
