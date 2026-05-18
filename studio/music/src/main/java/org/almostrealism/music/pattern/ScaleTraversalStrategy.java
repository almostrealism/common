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

package org.almostrealism.music.pattern;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.music.midi.MidiNoteEvent;
import org.almostrealism.music.notes.NoteAudioContext;
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
	/** All scale positions play simultaneously as a chord at each element position. */
	CHORD,
	/** Scale positions are traversed sequentially across element repetitions. */
	SEQUENCE;

	/**
	 * Converts a pattern element into a list of renderable note audio destinations.
	 *
	 * @param element      the pattern element to render
	 * @param melodic      whether the pattern is melodic
	 * @param offset       the measure offset for this pattern repetition
	 * @param context      the audio scene context
	 * @param audioContext the note audio context
	 * @return list of rendered note audio destinations
	 */
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

	/**
	 * Converts a pattern element's note destinations to MIDI events.
	 *
	 * <p>This mirrors the traversal logic of {@link #getNoteDestinations} but
	 * produces {@link MidiNoteEvent} objects instead of {@link RenderedNoteAudio}.
	 * Pitch is resolved from {@link KeyPosition#position()} plus
	 * {@link MidiNoteEvent#PITCH_OFFSET}. Duration is computed from the element's
	 * duration strategy, with a fallback to the repeat duration when no audio
	 * sample is available for natural duration.</p>
	 *
	 * @param element  the pattern element to convert
	 * @param melodic  whether this is melodic (pitched) content
	 * @param offset   the pattern repetition offset in measures
	 * @param context  the audio scene context for timing and scale resolution
	 * @param instrument MIDI instrument number (0-127, or 128 for drums)
	 * @return list of MIDI note events
	 */
	public List<MidiNoteEvent> toMidiEvents(PatternElement element,
											 boolean melodic, double offset,
											 AudioSceneContext context,
											 int instrument) {
		List<MidiNoteEvent> events = new ArrayList<>();
		double sampleRate = OutputLine.sampleRate;

		for (int i = 0; i < element.getRepeatCount(); i++) {
			double relativePosition = element.getPosition() + i * element.getRepeatDuration();
			double actualPosition = offset + relativePosition;

			List<KeyPosition<?>> keys = new ArrayList<>();
			context.getScaleForPosition().apply(actualPosition).forEach(keys::add);

			int velocity = resolveVelocity(element);

			if (!melodic) {
				if (element.getScalePositions().size() > 1) {
					warn("Multiple scale position for non-melodic material");
				}

				long onsetTicks = framesToTicks(context.frameForPosition(actualPosition), sampleRate);
				long durationTicks = computeDurationTicks(element, context);
				int pitch = keys.isEmpty() ? 0 : resolvePitch(keys.get(0));
				events.add(new MidiNoteEvent(pitch, onsetTicks, durationTicks, velocity, instrument));
			} else if (this == CHORD) {
				for (double p : element.getScalePositions()) {
					if (keys.isEmpty()) break;
					int keyIndex = Math.min((int) (p * keys.size()), keys.size() - 1);

					long onsetTicks = framesToTicks(context.frameForPosition(actualPosition), sampleRate);
					long durationTicks = computeDurationTicks(element, context);
					int pitch = resolvePitch(keys.get(keyIndex));
					events.add(new MidiNoteEvent(pitch, onsetTicks, durationTicks, velocity, instrument));

					keys.remove(keyIndex);
				}
			} else if (this == SEQUENCE) {
				double p = element.getScalePositions().get(i % element.getScalePositions().size());
				if (keys.isEmpty()) break;

				int keyIndex = Math.min((int) (p * keys.size()), keys.size() - 1);
				long onsetTicks = framesToTicks(context.frameForPosition(actualPosition), sampleRate);
				long durationTicks = computeDurationTicks(element, context);
				int pitch = resolvePitch(keys.get(keyIndex));
				events.add(new MidiNoteEvent(pitch, onsetTicks, durationTicks, velocity, instrument));
			}
		}

		return events;
	}

	/**
	 * Converts a {@link KeyPosition} to a MIDI pitch number (0-127).
	 *
	 * @param key the key position to convert
	 * @return MIDI pitch number, clamped to [0, 127]
	 */
	private static int resolvePitch(KeyPosition<?> key) {
		int midiPitch = key.position() + MidiNoteEvent.PITCH_OFFSET;
		return Math.max(0, Math.min(127, midiPitch));
	}

	/**
	 * Resolves MIDI velocity from the element's automation parameters.
	 *
	 * <p>If automation parameters are available, the first value is interpreted
	 * as a velocity scale factor (0.0-1.0) and mapped to the MIDI range.
	 * Otherwise, {@link MidiNoteEvent#DEFAULT_VELOCITY} is returned.</p>
	 */
	private static int resolveVelocity(PatternElement element) {
		PackedCollection automation = element.getAutomationParameters();
		if (automation != null && automation.getMemLength() > 0) {
			double level = automation.toDouble(0);
			if (level > 0.0 && level <= 1.0) {
				return Math.max(1, Math.min(127, (int) (level * 127)));
			}
		}
		return MidiNoteEvent.DEFAULT_VELOCITY;
	}

	/**
	 * Computes note duration in ticks based on the element's duration strategy.
	 *
	 * <p>For {@link NoteDurationStrategy#FIXED}, the fixed duration is converted
	 * to ticks via the context's time-for-duration function. For
	 * {@link NoteDurationStrategy#NO_OVERLAP}, the duration extends to the
	 * next repeat position. For {@link NoteDurationStrategy#NONE}, the repeat
	 * duration is used as a fallback.</p>
	 */
	private static long computeDurationTicks(PatternElement element,
											  AudioSceneContext context) {
		double durationMeasures;

		switch (element.getDurationStrategy()) {
			case FIXED:
				durationMeasures = element.getNoteDurationSelection();
				break;
			case NO_OVERLAP:
				durationMeasures = element.getRepeatDuration();
				break;
			default:
				durationMeasures = element.getRepeatDuration();
				break;
		}

		double durationSeconds = context.getTimeForDuration().applyAsDouble(durationMeasures);
		return Math.max(1, (long) (durationSeconds * MidiNoteEvent.TIME_RESOLUTION));
	}

	/**
	 * Converts an absolute frame position to MIDI ticks.
	 */
	private static long framesToTicks(int frames, double sampleRate) {
		double seconds = frames / sampleRate;
		return (long) (seconds * MidiNoteEvent.TIME_RESOLUTION);
	}

	/**
	 * Creates a single {@link RenderedNoteAudio} for the given element and voicing details.
	 *
	 * @param element         the pattern element
	 * @param details         the voicing details for this note
	 * @param automationLevel the automation level factor
	 * @param audioContext    the note audio context
	 * @param context         the audio scene context
	 * @param actualPosition  the actual measure position (offset + element position + repetition)
	 * @return the rendered note audio
	 */
	private RenderedNoteAudio createRenderedNote(PatternElement element,
												 ElementVoicingDetails details,
												 Factor<PackedCollection> automationLevel,
												 NoteAudioContext audioContext,
												 AudioSceneContext context,
												 double actualPosition) {
		int frameOffset = context.frameForPosition(actualPosition);
		double durationSec = element.getEffectiveDuration(
				details, audioContext.getAudioSelection(),
				context.getTimeForDuration());
		int expectedFrameCount = (int) (durationSec * OutputLine.sampleRate);
		RenderedNoteAudio note = new RenderedNoteAudio(frameOffset, expectedFrameCount);
		PackedCollection offsetArg = new PackedCollection(1);
		note.setOffsetArg(offsetArg);
		note.setProducerFactory((frameCount) ->
				element.getNoteAudio(details, automationLevel,
						audioContext.getAudioSelection(),
						context.getTimeForDuration(),
						frameCount > 0 ? offsetArg : null, frameCount));
		return note;
	}


	@Override
	public Console console() {
		return CellFeatures.console;
	}
}
