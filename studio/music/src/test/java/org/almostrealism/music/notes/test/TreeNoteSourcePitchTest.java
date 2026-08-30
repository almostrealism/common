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

package org.almostrealism.music.notes.test;

import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.music.notes.TreeNoteSource;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pins which pitch a sample is treated as being at.
 *
 * <p>A source's root is one pitch applied to everything it yields, which is all
 * that can be said about a folder of files. Where a sample's own pitch is known
 * — recorded at the moment it was captured — that is the better answer, and it
 * has to win. What must not change is the other case: a sample nothing knows
 * about goes on being treated exactly as before, since that is nearly every
 * sample in nearly every library.</p>
 */
public class TreeNoteSourcePitchTest extends TestSuiteBase {
	/** Reads the pitch a source would treat the given sample as being at. */
	private static class ReadableSource extends TreeNoteSource {
		/** Creates a source with the given root. */
		ReadableSource(KeyPosition<?> root) { super(root); }

		/**
		 * Returns the pitch this source would use for the given sample.
		 *
		 * @param p the sample
		 * @return the pitch
		 */
		KeyPosition<?> pitchFor(FileWaveDataProvider p) { return rootFor(p); }
	}

	/** A sample to ask about. */
	private FileWaveDataProvider sample(String path) {
		return new FileWaveDataProvider(path);
	}

	/** With nothing else known, a sample is at the source root. */
	@Test(timeout = 30000)
	public void withoutAPitchSourceTheRootIsUsed() {
		ReadableSource source = new ReadableSource(WesternChromatic.C1);

		Assert.assertEquals(WesternChromatic.C1, source.pitchFor(sample("/a/kick.wav")));
	}

	/** A sample whose own pitch is known is at that pitch. */
	@Test(timeout = 30000)
	public void aKnownPitchWinsOverTheRoot() {
		ReadableSource source = new ReadableSource(WesternChromatic.C1);
		source.setPitchSource(p -> WesternChromatic.G3);

		Assert.assertEquals(WesternChromatic.G3, source.pitchFor(sample("/a/kick.wav")));
	}

	/**
	 * A sample the pitch source knows nothing about keeps the root.
	 *
	 * <p>This is the case that must not regress: a library of loose files has
	 * no captured pitches, so every sample in it falls here.</p>
	 */
	@Test(timeout = 30000)
	public void anUnknownSampleKeepsTheRoot() {
		ReadableSource source = new ReadableSource(WesternChromatic.C1);
		source.setPitchSource(p -> null);

		Assert.assertEquals(WesternChromatic.C1, source.pitchFor(sample("/a/kick.wav")));
	}

	/** Each sample is asked about individually, not once for the source. */
	@Test(timeout = 30000)
	public void pitchIsResolvedPerSample() {
		ReadableSource source = new ReadableSource(WesternChromatic.C1);
		source.setPitchSource(p -> p.getResourcePath().contains("known")
				? WesternChromatic.G3 : null);

		Assert.assertEquals(WesternChromatic.G3, source.pitchFor(sample("/a/known.wav")));
		Assert.assertEquals(WesternChromatic.C1, source.pitchFor(sample("/a/other.wav")));
	}
}
