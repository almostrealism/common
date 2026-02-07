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

import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.audio.pattern.ElementVoicingDetails;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.List;
import java.util.function.DoubleFunction;
import java.util.function.DoubleUnaryOperator;

public class NoteAudioContext implements ConsoleFeatures {
	private ChannelInfo.Voicing voicing;
	private ChannelInfo.StereoChannel audioChannel;
	private DoubleFunction<PatternNoteAudio> audioSelection;
	private DoubleUnaryOperator nextNotePosition;

	public NoteAudioContext() {
		this.voicing = ChannelInfo.Voicing.MAIN;
	}

	public NoteAudioContext(ChannelInfo.Voicing voicing,
							ChannelInfo.StereoChannel audioChannel,
							List<PatternNoteAudio> audioChoices,
							DoubleUnaryOperator nextNotePosition) {
		this(voicing, audioChannel,
				c -> audioChoices.isEmpty() ? null : audioChoices.get((int) (c * audioChoices.size())),
				nextNotePosition);
	}

	public NoteAudioContext(ChannelInfo.Voicing voicing,
							ChannelInfo.StereoChannel audioChannel,
							DoubleFunction<PatternNoteAudio> audioSelection,
							DoubleUnaryOperator nextNotePosition) {
		if (audioSelection == null) {
			warn("No audio selection provided");
		}

		this.voicing = voicing;
		this.audioChannel = audioChannel;
		this.audioSelection = audioSelection;
		this.nextNotePosition = nextNotePosition;
	}

	public ChannelInfo.Voicing getVoicing() { return voicing; }
	public void setVoicing(ChannelInfo.Voicing voicing) {
		this.voicing = voicing;
	}

	public ChannelInfo.StereoChannel getAudioChannel() { return audioChannel; }
	public void setAudioChannel(ChannelInfo.StereoChannel audioChannel) {
		this.audioChannel = audioChannel;
	}

	public DoubleFunction<PatternNoteAudio> getAudioSelection() {
		return audioSelection;
	}

	public void setAudioSelection(DoubleFunction<PatternNoteAudio> audioSelection) {
		this.audioSelection = audioSelection;
	}

	public PatternNoteAudio selectAudio(double selection) {
		return getAudioSelection().apply(selection);
	}

	public DoubleUnaryOperator getNextNotePosition() {
		return nextNotePosition;
	}

	public void setNextNotePosition(DoubleUnaryOperator nextNotePosition) {
		this.nextNotePosition = nextNotePosition;
	}

	public double nextNotePosition(double pos) {
		return nextNotePosition == null ? 0.0 : nextNotePosition.applyAsDouble(pos);
	}

	public ElementVoicingDetails createVoicingDetails(boolean melodic, KeyPosition<?> target, double position) {
		return new ElementVoicingDetails(
				voicing, audioChannel, melodic, target,
				position, nextNotePosition(position));
	}

	@Override
	public Console console() { return CellFeatures.console; }
}
