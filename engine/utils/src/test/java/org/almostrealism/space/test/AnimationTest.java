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

import org.almostrealism.color.ShadableSurface;
import org.almostrealism.physics.Clock;
import org.almostrealism.space.Animation;
import org.almostrealism.space.Scene;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Behavioral tests for {@link Animation}'s configuration accessors, clock management,
 * scene cloning, and the empty-scene average-velocity edge case.
 */
public class AnimationTest extends TestSuiteBase {

	/** A fresh animation starts with a clock, zero time, and default flags off. */
	@Test(timeout = 10000)
	public void defaultState() {
		Animation<ShadableSurface> anim = new Animation<>();
		Assert.assertNotNull(anim.getClock());
		Assert.assertEquals(0.0, anim.getTime(), 1e-12);
		Assert.assertEquals(0.0, anim.getTickDuration(), 1e-12);
		Assert.assertFalse(anim.getSleepEachFrame());
		Assert.assertFalse(anim.getRenderEachFrame());
		Assert.assertFalse(anim.getLogEachFrame());
		Assert.assertNull(anim.getOutputDirectory());
	}

	/** Iteration count round-trips. */
	@Test(timeout = 10000)
	public void iterations() {
		Animation<ShadableSurface> anim = new Animation<>();
		anim.setIterations(42);
		Assert.assertEquals(42, anim.getIterations());
	}

	/** Frames-per-second is stored as the reciprocal frame duration. */
	@Test(timeout = 10000)
	public void framesPerSecond() {
		Animation<ShadableSurface> anim = new Animation<>();
		anim.setFPS(25.0);
		Assert.assertEquals(1.0 / 25.0, anim.getFrameDuration(), 1e-12);
	}

	/** The boolean rendering/logging/sleep flags round-trip. */
	@Test(timeout = 10000)
	public void booleanFlags() {
		Animation<ShadableSurface> anim = new Animation<>();
		anim.setSleepEachFrame(true);
		anim.setRenderEachFrame(true);
		anim.setLogEachFrame(true);
		Assert.assertTrue(anim.getSleepEachFrame());
		Assert.assertTrue(anim.getRenderEachFrame());
		Assert.assertTrue(anim.getLogEachFrame());
	}

	/** The output directory round-trips. */
	@Test(timeout = 10000)
	public void outputDirectory() {
		Animation<ShadableSurface> anim = new Animation<>();
		anim.setOutputDirectory("/tmp/frames/");
		Assert.assertEquals("/tmp/frames/", anim.getOutputDirectory());
	}

	/** A replacement clock is stored and returned. */
	@Test(timeout = 10000)
	public void clockReplacement() {
		Animation<ShadableSurface> anim = new Animation<>();
		Clock clock = new Clock();
		anim.setClock(clock);
		Assert.assertSame(clock, anim.getClock());
	}

	/** getScene returns a Scene clone distinct from the animation instance. */
	@Test(timeout = 10000)
	public void getSceneReturnsClone() {
		Animation<ShadableSurface> anim = new Animation<>();
		Scene scene = anim.getScene();
		Assert.assertNotNull(scene);
		Assert.assertNotSame(anim, scene);
	}

	/** An empty scene reports a mean linear velocity of 0.0, per the documented contract. */
	@Test(timeout = 10000)
	public void averageVelocityOfEmptySceneIsZero() {
		Animation<ShadableSurface> anim = new Animation<>();
		Assert.assertEquals(0.0, anim.getAverageLinearVelocity(), 0.0);
	}
}
