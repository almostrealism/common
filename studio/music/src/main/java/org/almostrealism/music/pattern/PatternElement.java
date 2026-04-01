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

package org.almostrealism.music.pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.notes.NoteAudioContext;
import org.almostrealism.music.notes.PatternNote;
import org.almostrealism.music.notes.PatternNoteAudio;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.collect.PackedCollection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleFunction;
import java.util.function.DoubleUnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Represents a single musical event within a pattern layer.
 *
 * <p>{@code PatternElement} is the fundamental unit of musical arrangement in the
 * pattern system. Each element specifies:</p>
 * <ul>
 *   <li><strong>Position</strong>: When the element occurs within its pattern (in measures)</li>
 *   <li><strong>Notes</strong>: The audio samples to play, mapped by {@link ChannelInfo.Voicing}</li>
 *   <li><strong>Duration Strategy</strong>: How note length is determined</li>
 *   <li><strong>Scale Traversal</strong>: How melodic notes traverse the current scale</li>
 *   <li><strong>Repetition</strong>: Optional repeat count and duration for rhythmic patterns</li>
 * </ul>
 *
 * <h2>Note Destinations</h2>
 *
 * <p>The key method {@link #getNoteDestinations(boolean, double, AudioSceneContext, NoteAudioContext)}
 * converts this element into renderable {@link RenderedNoteAudio} objects. This involves:</p>
 * <ol>
 *   <li>Determining all positions (accounting for repeats)</li>
 *   <li>Looking up the current scale at each position</li>
 *   <li>Applying the {@link ScaleTraversalStrategy} to select pitches</li>
 *   <li>Computing frame offsets for each note</li>
 * </ol>
 *
 * <h2>Voicing Support</h2>
 *
 * <p>Elements can have different notes for MAIN and WET voicings, enabling parallel
 * dry/wet signal paths with different audio content.</p>
 *
 * <h2>Duration Strategies</h2>
 *
 * <ul>
 *   <li>{@link NoteDurationStrategy#NONE}: Use the note's natural duration</li>
 *   <li>{@link NoteDurationStrategy#UNTIL_NEXT}: Extend until the next note</li>
 *   <li>{@link NoteDurationStrategy#FIXED}: Use a fixed duration multiplier</li>
 * </ul>
 *
 * <h2>Real-Time Considerations</h2>
 *
 * <p>For real-time rendering, the frame offset computation in {@link #getNoteDestinations}
 * needs to account for buffer-relative positioning. The {@code AudioSceneContext} must
 * provide frame conversion that works with incremental buffer positions.</p>
 *
 * @see PatternLayer
 * @see PatternNote
 * @see ScaleTraversalStrategy
 * @see NoteDurationStrategy
 * @see RenderedNoteAudio
 *
 * @author Michael Murray
 */
public class PatternElement implements CodeFeatures {
	/** The notes to play, keyed by voicing (MAIN or WET). */
	private Map<ChannelInfo.Voicing, PatternNote> notes;
	/** The position of this element within its pattern, in measures. */
	private double position;

	/** The strategy for determining note duration. */
	private NoteDurationStrategy durationStrategy;
	/** The duration selection multiplier used by the FIXED duration strategy. */
	private double noteDuration;

	/** The strategy for traversing the current musical scale. */
	private ScaleTraversalStrategy scaleTraversalStrategy;
	/** The list of scale positions used to select pitches from the scale. */
	private List<Double> scalePositions;

	/** The playback direction for this element. */
	private PatternDirection direction;
	/** The number of times this element repeats. */
	private int repeatCount;
	/** The measure duration between successive repetitions. */
	private double repeatDuration;

	/** The automation parameters applied to this element's audio processing. */
	private PackedCollection automationParameters;

	/** Creates a default PatternElement at position 0 with no notes. */
	public PatternElement() {
		this((PatternNote) null, 0.0);
	}

	/**
	 * Creates a PatternElement with a single MAIN-voicing note at the given position.
	 *
	 * @param note     the note to play (may be null)
	 * @param position the position in measures
	 */
	public PatternElement(PatternNote note, double position) {
		this(new HashMap<>(), position);

		if (note != null) {
			setNote(ChannelInfo.Voicing.MAIN, note);
		}
	}

	/**
	 * Creates a PatternElement with separate MAIN and WET voicing notes.
	 *
	 * @param mainNote the note for the MAIN voicing (may be null)
	 * @param wetNote  the note for the WET voicing (may be null)
	 * @param position the position in measures
	 */
	public PatternElement(PatternNote mainNote, PatternNote wetNote, double position) {
		this(new HashMap<>(), position);

		if (mainNote != null) {
			setNote(ChannelInfo.Voicing.MAIN, mainNote);
		}

		if (wetNote != null) {
			setNote(ChannelInfo.Voicing.WET, wetNote);
		}
	}

	/**
	 * Creates a PatternElement with an explicit notes map and position.
	 *
	 * @param notes    the map of voicing to note
	 * @param position the position in measures
	 */
	public PatternElement(Map<ChannelInfo.Voicing, PatternNote> notes, double position) {
		setNotes(notes);
		setPosition(position);
		setDurationStrategy(NoteDurationStrategy.NONE);
		setScaleTraversalStrategy(ScaleTraversalStrategy.CHORD);
		setScalePosition(List.of(0.0));
		setDirection(PatternDirection.FORWARD);
		setRepeatCount(1);
		setRepeatDuration(1);
	}

	/** Returns the map of voicing to note. */
	public Map<ChannelInfo.Voicing, PatternNote> getNotes() { return notes; }
	/** Sets the map of voicing to note. */
	public void setNotes(Map<ChannelInfo.Voicing, PatternNote> notes) { this.notes = notes; }

	/** Returns the note for the given voicing. */
	public PatternNote getNote(ChannelInfo.Voicing voicing) { return notes.get(voicing); }
	/** Sets the note for the given voicing. */
	public void setNote(ChannelInfo.Voicing voicing, PatternNote note) {
		this.notes.put(voicing, note);
	}

	/** Returns the position of this element in measures. */
	public double getPosition() {
		return position;
	}
	/** Sets the position of this element in measures. */
	public void setPosition(double position) {
		this.position = position;
	}

	/** Returns the duration strategy controlling how note length is computed. */
	public NoteDurationStrategy getDurationStrategy() { return durationStrategy; }
	/** Sets the duration strategy controlling how note length is computed. */
	public void setDurationStrategy(NoteDurationStrategy durationStrategy) {
		this.durationStrategy = durationStrategy;
	}

	/** Returns the scale traversal strategy for this element. */
	public ScaleTraversalStrategy getScaleTraversalStrategy() {
		return scaleTraversalStrategy;
	}

	/** Sets the scale traversal strategy for this element. */
	public void setScaleTraversalStrategy(ScaleTraversalStrategy scaleTraversalStrategy) {
		this.scaleTraversalStrategy = scaleTraversalStrategy;
	}

	/**
	 * Returns the effective note duration in seconds using this element's duration strategy.
	 *
	 * @param timeForDuration        converts measure durations to seconds
	 * @param position               the current note position in measures
	 * @param nextPosition           the next note position in measures (for NO_OVERLAP)
	 * @param originalDurationSeconds the natural sample duration in seconds
	 * @return the effective note duration in seconds
	 */
	public double getNoteDuration(DoubleUnaryOperator timeForDuration,
								  double position, double nextPosition,
								  double originalDurationSeconds) {
		return durationStrategy.getLength(timeForDuration, position, nextPosition,
							originalDurationSeconds, getNoteDurationSelection());
	}

	/** Returns the duration selection value used by the FIXED duration strategy. */
	public double getNoteDurationSelection() { return noteDuration; }
	/** Sets the duration selection value used by the FIXED duration strategy. */
	public void setNoteDurationSelection(double noteDuration) { this.noteDuration = noteDuration; }

	/** Returns the list of scale positions used to select pitches. */
	public List<Double> getScalePositions() { return scalePositions; }
	/** Sets the list of scale positions used to select pitches. */
	public void setScalePosition(List<Double> scalePositions) { this.scalePositions = scalePositions; }

	/** Returns the playback direction for this element. */
	public PatternDirection getDirection() {
		return direction;
	}
	/** Sets the playback direction for this element. */
	public void setDirection(PatternDirection direction) {
		this.direction = direction;
	}

	/** Returns the number of times this element repeats. */
	public int getRepeatCount() {
		return repeatCount;
	}
	/** Sets the number of times this element repeats. */
	public void setRepeatCount(int repeatCount) {
		this.repeatCount = repeatCount;
	}

	/** Returns the measure duration between successive repetitions. */
	public double getRepeatDuration() {
		return repeatDuration;
	}
	/** Sets the measure duration between successive repetitions. */
	public void setRepeatDuration(double repeatDuration) {
		this.repeatDuration = repeatDuration;
	}

	/** Returns the automation parameters for this element's audio processing. */
	public PackedCollection getAutomationParameters() {
		return automationParameters;
	}

	/** Sets the automation parameters for this element's audio processing. */
	public void setAutomationParameters(PackedCollection automationParameters) {
		this.automationParameters = automationParameters;
	}

	/**
	 * Propagates the keyboard tuning to all notes in this element.
	 *
	 * @param tuning the tuning to apply
	 */
	@JsonIgnore
	public void setTuning(KeyboardTuning tuning) {
		this.notes.values().forEach(n -> n.setTuning(tuning));
	}

	/**
	 * Returns all absolute positions for this element, accounting for repetitions.
	 *
	 * @return list of measure positions for each repetition
	 */
	public List<Double> getPositions() {
		return IntStream.range(0, repeatCount)
				.mapToObj(i -> position + (i * repeatDuration))
				.collect(Collectors.toList());
	}

	/**
	 * Returns the list of rendered note audio objects for this element.
	 *
	 * @param melodic      whether the pattern is melodic
	 * @param offset       the measure offset for this pattern repetition
	 * @param context      the audio scene context
	 * @param audioContext the note audio context
	 * @return list of rendered note audio destinations
	 */
	public List<RenderedNoteAudio> getNoteDestinations(boolean melodic, double offset,
													   AudioSceneContext context,
													   NoteAudioContext audioContext) {
		return getScaleTraversalStrategy()
				.getNoteDestinations(this, melodic, offset,
									context, audioContext);
	}

	/**
	 * Returns the effective duration of this element's note in seconds.
	 *
	 * <p>This accounts for the {@link NoteDurationStrategy}: if {@code NONE},
	 * the note's natural duration is used; otherwise the strategy computes
	 * a modified duration based on position and next-note timing.</p>
	 *
	 * @param details voicing details for the note
	 * @param audioSelection audio selection function
	 * @param timeForDuration measure-to-seconds conversion
	 * @return the effective note duration in seconds
	 */
	public double getEffectiveDuration(ElementVoicingDetails details,
									   DoubleFunction<PatternNoteAudio> audioSelection,
									   DoubleUnaryOperator timeForDuration) {
		double originalDuration = getNote(details.getVoicing())
				.getDuration(details.getTarget(), audioSelection);

		if (getDurationStrategy() == NoteDurationStrategy.NONE) {
			return originalDuration;
		} else {
			return getNoteDuration(timeForDuration, details.getPosition(),
					details.getNextNotePosition(), originalDuration);
		}
	}

	/**
	 * Returns a {@link Producer} for this note's audio, optionally for a specific frame range.
	 *
	 * <p>When {@code offset} is non-null, only {@code frameCount} frames are produced,
	 * with the start position determined by the value stored in the {@code offset}
	 * {@link PackedCollection}. When {@code offset} is null, the full note is evaluated.</p>
	 *
	 * @param details voicing details
	 * @param automationLevel automation factor
	 * @param audioSelection audio selection function
	 * @param timeForDuration measure-to-seconds conversion
	 * @param offset PackedCollection containing the start frame (note-relative), or null for full evaluation
	 * @param frameCount number of frames to evaluate (ignored when offset is null)
	 * @return a Producer generating the requested audio
	 */
	public Producer<PackedCollection> getNoteAudio(ElementVoicingDetails details,
													  Factor<PackedCollection> automationLevel,
													  DoubleFunction<PatternNoteAudio> audioSelection,
													  DoubleUnaryOperator timeForDuration,
													  PackedCollection offset, int frameCount) {
		KeyPosition<?> k = details.isMelodic() ? details.getTarget() : null;
		double duration = getEffectiveDuration(details, audioSelection, timeForDuration);
		return getNote(details.getVoicing()).getAudio(k,
				details.getStereoChannel().getIndex(), duration,
				automationLevel, audioSelection,
				offset, frameCount);
	}

	/**
	 * Returns true if any repetition of this element falls within {@code [start, end)}.
	 *
	 * @param start inclusive start position in measures
	 * @param end   exclusive end position in measures
	 * @return true if this element has at least one position in the range
	 */
	public boolean isPresent(double start, double end) {
		for (int i = 0; i < repeatCount; i++) {
			double pos = getPosition() + i * repeatDuration;
			if (pos >= start && pos < end) return true;
		}

		return false;
	}
}
