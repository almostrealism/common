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

import io.almostrealism.relation.Validity;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.audio.notes.ReversePlaybackAudioFilter;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.notes.PatternNoteLayer;
import org.almostrealism.music.notes.SimplePatternNote;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;

/**
 * Tests that a {@link PatternNoteLayer} reports the validity of the audio it filters,
 * so the selection layer's validity filter excludes a layer (such as the
 * reverse-playback layer a note source adds for every sample) over an unreadable
 * sample instead of selecting a note that fails every time it is rendered.
 */
public class PatternNoteLayerValidityTest extends TestSuiteBase {

	/** A layer over an unreadable sample file is invalid, matching its delegate. */
	@Test(timeout = 30000)
	public void layerOverUnreadableSampleIsInvalid() {
		File missing = new File(System.getProperty("java.io.tmpdir"),
				"pattern-note-layer-validity-missing-" + System.nanoTime() + ".wav");
		SimplePatternNote note = new SimplePatternNote(
				NoteAudioProvider.create(missing.getPath(), WesternChromatic.C3));
		PatternNoteLayer layer = note.filter(new ReversePlaybackAudioFilter());

		Assert.assertFalse("The unreadable sample's note must be invalid", Validity.valid(note));
		Assert.assertFalse("A layer over an unreadable sample must be invalid", Validity.valid(layer));
	}

	/** A layer over a readable sample is valid, matching its delegate. */
	@Test(timeout = 30000)
	public void layerOverReadableSampleIsValid() {
		PackedCollection source = new PackedCollection(1024);
		SimplePatternNote note = new SimplePatternNote(
				NoteAudioProvider.create(() -> source, WesternChromatic.C3));
		PatternNoteLayer layer = note.filter(new ReversePlaybackAudioFilter());

		Assert.assertTrue("The readable sample's note must be valid", Validity.valid(note));
		Assert.assertTrue("A layer over a readable sample must be valid", Validity.valid(layer));
	}
}
