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
 * clearing it drops the reference while retaining the LFO for destruction in
 * {@link PolyphonicSynthesizer#destroy()}, so the native buffers an already-compiled tick
 * captured are not freed from under it, yet no LFO is leaked once the synthesizer is destroyed.
 * Clearing when nothing is enabled is a no-op.
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
	 * tick drops the reference. Re-enabling creates a fresh LFO.
	 */
	@Test(timeout = 60000)
	public void clearingVibratoReleasesTheLFO() {
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(4);

		try {
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
		} finally {
			synth.destroy();
		}
	}

	/**
	 * Destroying the synthesizer releases the LFOs it still owns &mdash; both those retired by
	 * {@link PolyphonicSynthesizer#clearVibrato()}/{@link PolyphonicSynthesizer#clearTremolo()} and
	 * the ones still active &mdash; and is idempotent. This exercises the deferred-destruction path
	 * that replaces freeing an LFO from the clear controls, which would free device buffers an
	 * already-compiled tick still references.
	 */
	@Test(timeout = 60000)
	public void destroyReleasesOwnedLFOs() {
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(4);

		synth.setVibrato(5.0, 2.0);
		LFO firstVibrato = synth.getVibratoLFO();
		synth.clearVibrato();
		assertTrue(synth.getVibratoLFO() == null);

		synth.setVibrato(6.0, 1.0);
		synth.setTremolo(5.0, 0.5);
		LFO secondVibrato = synth.getVibratoLFO();
		LFO tremolo = synth.getTremoloLFO();
		assertTrue(firstVibrato != secondVibrato);
		assertTrue(tremolo != null);

		synth.destroy();

		assertTrue(synth.getVibratoLFO() == null);
		assertTrue(synth.getTremoloLFO() == null);

		synth.destroy();
	}

	/**
	 * A synthesizer tick compiled while vibrato and tremolo are enabled keeps running after both
	 * are cleared: the tick captured the LFOs it was built with (retired, not destroyed), and the
	 * cleared depths of zero leave the voice frequency and velocity unmodulated. Before the LFOs
	 * were captured, the compiled modulation dereferenced the cleared field and threw.
	 */
	@Test(timeout = 120000)
	public void compiledTickSurvivesClearingModulation() {
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(2);
		synth.setVibrato(5.0, 2.0);
		synth.setTremolo(5.0, 0.5);

		try {
			Runnable setup = synth.setup().get();
			Runnable tick = synth.tick().get();
			setup.run();

			assertTrue(synth.noteOn(60, 0.8));
			tick.run();

			synth.clearVibrato();
			synth.clearTremolo();
			assertTrue(synth.getVibratoLFO() == null);
			assertTrue(synth.getTremoloLFO() == null);

			for (int i = 0; i < 4; i++) {
				tick.run();
			}

			assertEquals(1, synth.getActiveVoiceCount());
		} finally {
			synth.destroy();
		}
	}

	/**
	 * A synthesizer tick compiled while one tremolo LFO was active stops modulating once that LFO
	 * is replaced: after clearing tremolo and enabling it again with a new LFO and a larger depth,
	 * the older tick &mdash; which advances only the retired LFO &mdash; leaves the voice at its
	 * note velocity instead of applying the new depth to the retired LFO's value. Before the
	 * replacement the same tick does modulate, so the check is not vacuous.
	 */
	@Test(timeout = 120000)
	public void compiledTickIgnoresReplacedModulation() {
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(1);
		synth.setTremolo(5.0, 0.5);

		try {
			Runnable setup = synth.setup().get();
			Runnable tick = synth.tick().get();
			setup.run();

			assertTrue(synth.noteOn(60, 0.8));
			tick.run();
			assertTrue("tremolo modulates before it is replaced",
					Math.abs(synth.getVoice(0).getVelocity() - 0.8) > 1e-3);

			LFO retired = synth.getTremoloLFO();
			synth.clearTremolo();
			synth.setTremolo(5.0, 1.0);
			assertTrue(synth.getTremoloLFO() != retired);

			for (int i = 0; i < 4; i++) {
				tick.run();
				assertEquals(0.8, synth.getVoice(0).getVelocity());
			}
		} finally {
			synth.destroy();
		}
	}

	/**
	 * Enabling tremolo creates an LFO, and clearing it after the LFO has compiled and run its
	 * tick drops the reference, retaining that LFO for destruction in
	 * {@link PolyphonicSynthesizer#destroy()}.
	 */
	@Test(timeout = 60000)
	public void clearingTremoloReleasesTheLFO() {
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(4);

		try {
			synth.setTremolo(5.0, 0.5);
			LFO tremolo = synth.getTremoloLFO();
			assertTrue(tremolo != null);
			tremolo.tick().get().run();

			synth.clearTremolo();
			assertTrue(synth.getTremoloLFO() == null);
		} finally {
			synth.destroy();
		}
	}
}
