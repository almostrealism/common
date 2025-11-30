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

import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.junit.Test;

import java.io.File;

public class NoteAudioProviderTests {
	protected NoteAudioProvider organ() {
		NoteAudioProvider note = NoteAudioProvider.create(
				"Library/organ.wav", WesternChromatic.C1);
		note.setTuning(new DefaultKeyboardTuning());
		return note;
	}

	@Test
	public void noteAudioProvider1() {
		PackedCollection result = organ().getAudio(WesternChromatic.C1, 0).evaluate();
		WaveData data = new WaveData(result, OutputLine.sampleRate);
		data.save(new File("results/note-audio-provider-1.wav"));
	}

	@Test
	public void noteAudioProvider2() {
		PackedCollection result = organ().getAudio(WesternChromatic.G1, 1).evaluate();
		WaveData data = new WaveData(result, OutputLine.sampleRate);
		data.save(new File("results/note-audio-provider-2.wav"));
	}

	@Test
	public void alternateSampleRate() {
		int sampleRate = OutputLine.sampleRate / 2;
		PackedCollection result = organ().getProvider().getChannelData(0, 1.0, sampleRate);
		WaveData data = new WaveData(result, sampleRate);
		data.save(new File("results/alternate-sample-rate.wav"));
	}
}
