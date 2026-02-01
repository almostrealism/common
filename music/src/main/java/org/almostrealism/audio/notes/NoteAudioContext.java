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

/**
 * Provides context for rendering note audio within a pattern.
 *
 * <p>{@code NoteAudioContext} encapsulates the information needed during the
 * note destination generation process in {@link ScaleTraversalStrategy}:</p>
 * <ul>
 *   <li><strong>voicing</strong>: MAIN or WET signal path</li>
 *   <li><strong>audioChannel</strong>: LEFT or RIGHT stereo channel</li>
 *   <li><strong>audioSelection</strong>: Function to select note audio based on a 0-1 value</li>
 *   <li><strong>nextNotePosition</strong>: Function to find the next note position (for duration)</li>
 * </ul>
 *
 * <h2>Audio Selection</h2>
 *
 * <p>The {@code audioSelection} function maps a double value [0, 1) to a {@link PatternNoteAudio}.
 * This is typically constructed from a list of available notes, with the selection value
 * indexing into the list.</p>
 *
 * <h2>Voicing Details Creation</h2>
 *
 * <p>The {@link #createVoicingDetails} method creates an {@link ElementVoicingDetails}
 * instance that combines this context with melodic/pitch information for rendering.</p>
 *
 * <h2>Duration Calculation</h2>
 *
 * <p>The {@code nextNotePosition} function is used by {@link NoteDurationStrategy#NO_OVERLAP}
 * to extend notes until the next note begins.</p>
 *
 * @see ScaleTraversalStrategy
 * @see ElementVoicingDetails
 * @see PatternNoteAudio
 *
 * @author Michael Murray
 */
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
