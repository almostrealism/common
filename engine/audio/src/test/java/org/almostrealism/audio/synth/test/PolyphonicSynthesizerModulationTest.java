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

package org.almostrealism.audio.synth.test;

import org.almostrealism.audio.synth.LFO;
import org.almostrealism.audio.synth.PolyphonicSynthesizer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Verifies that {@link PolyphonicSynthesizer} manages the lifecycle of the device-backed
 * {@link LFO}s it creates for vibrato and tremolo: enabling modulation creates an LFO, and
 * clearing it releases that LFO's device resources and drops the reference, so repeatedly
 * enabling and disabling modulation does not leak native buffers. Clearing when nothing is
 * enabled is a no-op.
 */
public class PolyphonicSynthesizerModulationTest extends TestSuiteBase {

	/** Clearing vibrato and tremolo before either is enabled does nothing and does not throw. */
	@Test(timeout = 30000)
	public void clearingUnsetModulationIsSafe() {
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(4);

		assertTrue(synth.getVibratoLFO() == null);
		assertTrue(synth.getTremoloLFO() == null);

		synth.clearVibrato();
		synth.clearTremolo();

		assertTrue(synth.getVibratoLFO() == null);
		assertTrue(synth.getTremoloLFO() == null);
	}

	/**
	 * Enabling vibrato creates an LFO, and clearing it after the LFO has compiled and run its
	 * tick releases that LFO and drops the reference. Re-enabling creates a fresh LFO.
	 */
	@Test(timeout = 60000)
	public void clearingVibratoReleasesTheLFO() {
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(4);

		synth.setVibrato(5.0, 2.0);
		LFO vibrato = synth.getVibratoLFO();
		assertTrue(vibrato != null);
		vibrato.tick().get().run();

		synth.clearVibrato();
		assertTrue(synth.getVibratoLFO() == null);

		synth.setVibrato(6.0, 1.0);
		assertTrue(synth.getVibratoLFO() != null);
		assertTrue(synth.getVibratoLFO() != vibrato);
		synth.clearVibrato();
	}

	/**
	 * Enabling tremolo creates an LFO, and clearing it after the LFO has compiled and run its
	 * tick releases that LFO and drops the reference.
	 */
	@Test(timeout = 60000)
	public void clearingTremoloReleasesTheLFO() {
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(4);

		synth.setTremolo(5.0, 0.5);
		LFO tremolo = synth.getTremoloLFO();
		assertTrue(tremolo != null);
		tremolo.tick().get().run();

		synth.clearTremolo();
		assertTrue(synth.getTremoloLFO() == null);
	}
}
