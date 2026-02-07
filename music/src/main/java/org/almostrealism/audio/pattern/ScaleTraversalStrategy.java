/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.audio.pattern;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.arrange.AudioSceneContext;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.notes.NoteAudioContext;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines how melodic pattern elements traverse the current musical scale.
 *
 * <p>{@code ScaleTraversalStrategy} determines how notes within a {@link PatternElement}
 * are mapped to pitches in the current scale. This is critical for melodic (pitched)
 * patterns as opposed to percussive (unpitched) patterns.</p>
 *
 * <h2>Strategies</h2>
 *
 * <ul>
 *   <li><strong>CHORD</strong>: All scale positions play simultaneously as a chord.
 *       Each scale position from {@link PatternElement#getScalePositions()} selects
 *       a different key from the current scale, and all notes trigger at the same time.</li>
 *   <li><strong>SEQUENCE</strong>: Scale positions are traversed sequentially across
 *       repetitions. For repeating elements, each repetition plays the next scale position
 *       in sequence, creating arpeggios or melodic runs.</li>
 * </ul>
 *
 * <h2>Note Destination Generation</h2>
 *
 * <p>The primary method {@link #getNoteDestinations} converts a pattern element into
 * renderable note audio by:</p>
 * <ol>
 *   <li>Iterating through each repetition of the element</li>
 *   <li>Looking up the scale at the current position via {@code context.getScaleForPosition()}</li>
 *   <li>Applying automation levels from the element's parameters</li>
 *   <li>Selecting keys based on scale positions and the traversal strategy</li>
 *   <li>Computing frame offsets via {@code context.frameForPosition()}</li>
 * </ol>
 *
 * <h2>Frame Position Computation</h2>
 *
 * <p><strong>Critical for real-time:</strong> This enum uses {@code context.frameForPosition()}
 * and {@code context.getFrameForPosition()} to compute absolute frame offsets. For real-time
 * rendering with buffer-relative positions, the context must translate measure positions
 * to offsets within the current buffer, not the full arrangement.</p>
 *
 * <h2>Non-Melodic Handling</h2>
 *
 * <p>For non-melodic (percussive) elements, only the first key from the scale is used,
 * and a warning is logged if multiple scale positions are specified.</p>
 *
 * @see PatternElement
 * @see RenderedNoteAudio
 * @see AudioSceneContext#frameForPosition(double)
 *
 * @author Michael Murray
 */
public enum ScaleTraversalStrategy implements CodeFeatures, ConsoleFeatures {
	CHORD, SEQUENCE;

	public List<RenderedNoteAudio> getNoteDestinations(PatternElement element,
													   boolean melodic, double offset,
													   AudioSceneContext context,
													   NoteAudioContext audioContext) {
		List<RenderedNoteAudio> destinations = new ArrayList<>();

		for (int i = 0; i < element.getRepeatCount(); i++) {
			double relativePosition = element.getPosition() + i * element.getRepeatDuration();
			double actualPosition = offset + relativePosition;
			double actualTime = context.timeForPosition(actualPosition);

			List<KeyPosition<?>> keys = new ArrayList<>();
			context.getScaleForPosition().apply(actualPosition).forEach(keys::add);

			Factor<PackedCollection> automationLevel =
					context.getAutomationLevel().apply(element.getAutomationParameters());
			Factor<PackedCollection> relativeAutomationLevel =
					time -> automationLevel.getResultant(c(actualTime).add(time));

			if (!melodic) {
				if (element.getScalePositions().size() > 1) {
					warn("Multiple scale position for non-melodic material");
				}

				ElementVoicingDetails details =
						audioContext.createVoicingDetails(melodic,
								keys.get(0), relativePosition);
				destinations.add(createRenderedNote(element, details,
						relativeAutomationLevel, audioContext, context, actualPosition));
			} else if (this == CHORD) {
				p: for (double p : element.getScalePositions()) {
					if (keys.isEmpty()) break;
					int keyIndex = (int) (p * keys.size());

					ElementVoicingDetails details =
							audioContext.createVoicingDetails(melodic,
								keys.get(keyIndex), relativePosition);
					destinations.add(createRenderedNote(element, details,
							relativeAutomationLevel, audioContext, context, actualPosition));

					keys.remove(keyIndex);
				}
			} else if (this == SEQUENCE) {
				double p = element.getScalePositions().get(i % element.getScalePositions().size());
				if (keys.isEmpty()) break;

				int keyIndex = (int) (p * keys.size());
				ElementVoicingDetails details =
						audioContext.createVoicingDetails(melodic,
								keys.get(keyIndex), relativePosition);
				destinations.add(createRenderedNote(element, details,
						relativeAutomationLevel, audioContext, context, actualPosition));
			} else {
				throw new UnsupportedOperationException("Unknown ScaleTraversalStrategy (" + this + ")");
			}
		}

		return destinations;
	}

	private RenderedNoteAudio createRenderedNote(PatternElement element,
												 ElementVoicingDetails details,
												 Factor<PackedCollection> automationLevel,
												 NoteAudioContext audioContext,
												 AudioSceneContext context,
												 double actualPosition) {
		Producer<PackedCollection> producer = element.getNoteAudio(
				details, automationLevel,
				audioContext.getAudioSelection(),
				context.getTimeForDuration());
		int frameOffset = context.frameForPosition(actualPosition);
		double durationSec = element.getEffectiveDuration(
				details, audioContext.getAudioSelection(),
				context.getTimeForDuration());
		int expectedFrameCount = (int) (durationSec * OutputLine.sampleRate);
		return new RenderedNoteAudio(producer, frameOffset, expectedFrameCount);
	}


	@Override
	public Console console() {
		return CellFeatures.console;
	}
}
