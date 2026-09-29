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

package org.almostrealism.space.test;

import org.almostrealism.color.Light;
import org.almostrealism.color.PointLight;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.space.Scene;
import org.almostrealism.space.StandardLightingRigs;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Behavioral tests for {@link StandardLightingRigs#addDefaultLights(Scene)}.
 */
public class StandardLightingRigsTest extends TestSuiteBase {

	/** The default rig adds a single point light to an empty scene. */
	@Test(timeout = 10000)
	public void addsSinglePointLight() {
		Scene<ShadableSurface> scene = new Scene<>();
		Assert.assertEquals(0, scene.getLights().size());

		StandardLightingRigs.addDefaultLights(scene);

		Assert.assertEquals(1, scene.getLights().size());
		Light added = scene.getLight(0);
		Assert.assertTrue(added instanceof PointLight);
	}

	/** Calling the rig twice adds a second light rather than replacing the first. */
	@Test(timeout = 10000)
	public void repeatedCallsAccumulate() {
		Scene<ShadableSurface> scene = new Scene<>();
		StandardLightingRigs.addDefaultLights(scene);
		StandardLightingRigs.addDefaultLights(scene);
		Assert.assertEquals(2, scene.getLights().size());
	}
}
