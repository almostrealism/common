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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.physics.Absorber;
import org.almostrealism.physics.Clock;

/**
 * A minimal {@link Absorber} with configurable emission behavior, shared by the raytrace tests.
 * It never absorbs; its emit energy, next-emit time, emitted direction and emit position are set
 * directly on the public fields so a test can drive emitter selection deterministically.
 */
class StubAbsorber implements Absorber {
	/** The energy reported by {@link #getEmitEnergy()}. */
	double energy;
	/** The next-emit time reported by {@link #getNextEmit()}; defaults to "never". */
	double nextEmit = Integer.MAX_VALUE;
	/** The direction producer returned by {@link #emit()}. */
	Producer<PackedCollection> emitDir;
	/** The position producer returned by {@link #getEmitPosition()}. */
	Producer<PackedCollection> emitPos;
	/** The clock most recently assigned via {@link #setClock(Clock)}. */
	private Clock clock;
	/** The value returned by {@link #absorb(Vector, Vector, double)}; defaults to not absorbing. */
	boolean absorbResult = false;

	@Override
	public boolean absorb(Vector x, Vector p, double energy) { return absorbResult; }

	@Override
	public Producer<PackedCollection> emit() { return emitDir; }

	@Override
	public double getEmitEnergy() { return energy; }

	@Override
	public double getNextEmit() { return nextEmit; }

	@Override
	public Producer<PackedCollection> getEmitPosition() { return emitPos; }

	@Override
	public void setClock(Clock c) { this.clock = c; }

	@Override
	public Clock getClock() { return clock; }
}
