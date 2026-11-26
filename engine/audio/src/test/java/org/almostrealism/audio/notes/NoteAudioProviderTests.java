/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.notes;

import org.almostrealism.audio.AudioTestFeatures;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;

/**
 * Tests for {@link NoteAudioProvider} and related audio note playback functionality.
 */
public class NoteAudioProviderTests extends TestSuiteBase implements AudioTestFeatures {

	/**
	 * Creates a NoteAudioProvider for organ samples with default keyboard tuning.
	 *
	 * @return a configured NoteAudioProvider instance
	 */
	protected NoteAudioProvider organ() {
		NoteAudioProvider note = NoteAudioProvider.create(
				getTestWavPath(), WesternChromatic.C1);
		note.setTuning(new DefaultKeyboardTuning());
		return note;
	}

	/**
	 * Tests retrieving audio for C1 with default channel and velocity.
	 */
	@Test(timeout = 60000)
	public void noteAudioProvider1() {
		PackedCollection result = organ().getAudio(WesternChromatic.C1, 0).evaluate();
		WaveData data = new WaveData(result, OutputLine.sampleRate);
		data.save(new File("results/note-audio-provider-1.wav"));
	}

	/**
	 * Tests retrieving audio for G1 with explicit channel and velocity.
	 */
	@Test(timeout = 60000)
	public void noteAudioProvider2() {
		PackedCollection result = organ().getAudio(WesternChromatic.G1, 1).evaluate();
		WaveData data = new WaveData(result, OutputLine.sampleRate);
		data.save(new File("results/note-audio-provider-2.wav"));
	}

	/**
	 * Tests that alternate sample rates are correctly handled when getting channel data.
	 */
	@Test(timeout = 60000)
	public void alternateSampleRate() {
		int sampleRate = OutputLine.sampleRate / 2;
		PackedCollection result = organ().getProvider().getChannelData(0, 1.0, sampleRate);
		WaveData data = new WaveData(result, sampleRate);
		data.save(new File("results/alternate-sample-rate.wav"));
	}

	/**
	 * Creates a tuned provider over a short in-memory buffer.
	 *
	 * @return a provider that has cached nothing yet
	 */
	protected NoteAudioProvider inMemory() {
		PackedCollection source = new PackedCollection(512);
		NoteAudioProvider note = NoteAudioProvider.create(() -> source, WesternChromatic.C4);
		note.setTuning(new DefaultKeyboardTuning());
		return note;
	}

	/**
	 * An entry evicted by the shared cache's size bound stops being tracked, so the
	 * cache does not keep every provider it has ever evicted alive. The evicted
	 * provider still delivers audio, re-registering its entry when it recomputes.
	 *
	 * @throws InterruptedException if interrupted while separating access times
	 */
	@Test(timeout = 120000)
	public void evictedEntriesAreDropped() throws InterruptedException {
		NoteAudioProvider oldest = inMemory();
		oldest.getAudio(WesternChromatic.C4, 0).evaluate();
		assertEquals(1, oldest.getCachedAudioCount());

		// Entries are ordered by millisecond access time; make the first strictly oldest.
		Thread.sleep(20);

		for (int i = 0; i < 400; i++) {
			inMemory().getAudio(WesternChromatic.C4, 0).evaluate();
		}

		assertEquals("An evicted entry should no longer be tracked by the cache",
				0, oldest.getCachedAudioCount());

		oldest.getAudio(WesternChromatic.C4, 0).evaluate();
		assertEquals("A recomputed entry should be tracked again",
				1, oldest.getCachedAudioCount());
	}

	/**
	 * Changing the tuning releases the entries computed under the previous tuning
	 * instead of leaving them tracked by the shared cache.
	 */
	@Test(timeout = 60000)
	public void tuningChangeReleasesEntries() {
		NoteAudioProvider note = inMemory();
		note.getAudio(WesternChromatic.G4, 0).evaluate();
		assertEquals(1, note.getCachedAudioCount());

		note.setTuning(new DefaultKeyboardTuning());
		assertEquals(0, note.getCachedAudioCount());
	}
}
