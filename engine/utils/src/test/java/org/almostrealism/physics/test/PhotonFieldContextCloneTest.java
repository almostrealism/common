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

package org.almostrealism.physics.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.physics.Absorber;
import org.almostrealism.physics.Clock;
import org.almostrealism.physics.PhotonField;
import org.almostrealism.physics.PhotonFieldContext;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link PhotonFieldContext#clone()}.
 */
public class PhotonFieldContextCloneTest extends TestSuiteBase {

	/**
	 * Verifies that clone produces a non-null instance distinct from the original.
	 */
	@Test(timeout = 5000)
	public void cloneReturnsNewInstance() {
		PhotonFieldContext<PhotonField, Absorber> ctx =
				new PhotonFieldContext<>(null, null, null, null);
		PhotonFieldContext<PhotonField, Absorber> cloned = ctx.clone();

		Assert.assertNotNull("Clone should not be null", cloned);
		Assert.assertNotSame("Clone should be a different instance", ctx, cloned);
	}

	/**
	 * Verifies that clone preserves the photon field and film references.
	 */
	@Test(timeout = 5000)
	public void clonePreservesFieldAndFilm() {
		StubPhotonField field = new StubPhotonField();
		StubAbsorber film = new StubAbsorber();
		PhotonFieldContext<StubPhotonField, StubAbsorber> ctx =
				new PhotonFieldContext<>(null, null, field, film);

		PhotonFieldContext<StubPhotonField, StubAbsorber> cloned = ctx.clone();

		Assert.assertSame("Photon field should be preserved", field, cloned.getPhotonField());
		Assert.assertSame("Film should be preserved", film, cloned.getFilm());
	}

	/**
	 * Verifies that clone preserves fog parameters from the parent ShaderContext.
	 */
	@Test(timeout = 5000)
	public void clonePreservesFogParameters() {
		PhotonFieldContext<PhotonField, Absorber> ctx =
				new PhotonFieldContext<>(null, null, null, null);
		ctx.fogColor = new RGB(0.5, 0.5, 0.5);
		ctx.fogRatio = 0.75;
		ctx.fogDensity = 0.3;

		PhotonFieldContext<PhotonField, Absorber> cloned = ctx.clone();

		Assert.assertSame("Fog color should be preserved", ctx.fogColor, cloned.fogColor);
		Assert.assertEquals("Fog ratio should be preserved", 0.75, cloned.fogRatio, 0.0001);
		Assert.assertEquals("Fog density should be preserved", 0.3, cloned.fogDensity, 0.0001);
	}

	/**
	 * Verifies that clone preserves reflection, entrance, and exit counts.
	 */
	@Test(timeout = 5000)
	public void clonePreservesReflectionCounts() {
		PhotonFieldContext<PhotonField, Absorber> ctx =
				new PhotonFieldContext<>(null, null, null, null);
		ctx.addReflection();
		ctx.addReflection();
		ctx.addEntrance();
		ctx.addExit();

		PhotonFieldContext<PhotonField, Absorber> cloned = ctx.clone();

		Assert.assertEquals("Reflection count should be preserved",
				ctx.getReflectionCount(), cloned.getReflectionCount());
		Assert.assertEquals("Entrance count should be preserved",
				ctx.getEnteranceCount(), cloned.getEnteranceCount());
		Assert.assertEquals("Exit count should be preserved",
				ctx.getExitCount(), cloned.getExitCount());
	}

	/**
	 * Verifies that modifications to the clone do not affect the original.
	 */
	@Test(timeout = 5000)
	public void cloneIsIndependent() {
		PhotonFieldContext<PhotonField, Absorber> ctx =
				new PhotonFieldContext<>(null, null, null, null);
		ctx.addReflection();

		PhotonFieldContext<PhotonField, Absorber> cloned = ctx.clone();
		cloned.addReflection();
		cloned.addReflection();

		Assert.assertEquals("Original should have 1 reflection", 1, ctx.getReflectionCount());
		Assert.assertEquals("Clone should have 3 reflections", 3, cloned.getReflectionCount());
	}

	/** Minimal stub for testing. */
	private static class StubPhotonField implements PhotonField {
		@Override public void setClock(Clock c) { }
		@Override public Clock getClock() { return null; }
		@Override public double getEnergy(double[] x, double radius) { return 0; }
		@Override public void tick(double d) { }
		@Override public void setAbsorber(Absorber a) { }
		@Override public Absorber getAbsorber() { return null; }
		@Override public void setGranularity(long delta) { }
		@Override public long getGranularity() { return 0; }
		@Override public long getSize() { return 0; }
		@Override public void setMaxLifetime(double l) { }
		@Override public double getMaxLifetime() { return 0; }
		@Override public void addPhoton(double[] x, double[] p, double energy) { }
		@Override public int removePhotons(double[] x, double radius) { return 0; }
	}

	/** Minimal stub for testing. */
	private static class StubAbsorber implements Absorber {
		@Override public boolean absorb(Vector x, Vector p, double energy) { return false; }
		@Override public Producer<PackedCollection> emit() { return null; }
		@Override public double getEmitEnergy() { return 0; }
		@Override public double getNextEmit() { return 0; }
		@Override public Producer<PackedCollection> getEmitPosition() { return null; }
		@Override public void setClock(Clock c) { }
		@Override public Clock getClock() { return null; }
	}
}
