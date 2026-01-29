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

package org.almostrealism.audio.pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.arrange.AudioSceneContext;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.audio.notes.NoteAudioContext;
import org.almostrealism.audio.notes.PatternNote;
import org.almostrealism.audio.notes.PatternNoteAudio;
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
	private Map<ChannelInfo.Voicing, PatternNote> notes;
	private double position;

	private NoteDurationStrategy durationStrategy;
	private double noteDuration;

	private ScaleTraversalStrategy scaleTraversalStrategy;
	private List<Double> scalePositions;

	private PatternDirection direction;
	private int repeatCount;
	private double repeatDuration;

	private PackedCollection automationParameters;

	public PatternElement() {
		this((PatternNote) null, 0.0);
	}

	public PatternElement(PatternNote note, double position) {
		this(new HashMap<>(), position);

		if (note != null) {
			setNote(ChannelInfo.Voicing.MAIN, note);
		}
	}

	public PatternElement(PatternNote mainNote, PatternNote wetNote, double position) {
		this(new HashMap<>(), position);

		if (mainNote != null) {
			setNote(ChannelInfo.Voicing.MAIN, mainNote);
		}

		if (wetNote != null) {
			setNote(ChannelInfo.Voicing.WET, wetNote);
		}
	}

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

	public Map<ChannelInfo.Voicing, PatternNote> getNotes() { return notes; }
	public void setNotes(Map<ChannelInfo.Voicing, PatternNote> notes) { this.notes = notes; }

	public PatternNote getNote(ChannelInfo.Voicing voicing) { return notes.get(voicing); }
	public void setNote(ChannelInfo.Voicing voicing, PatternNote note) {
		this.notes.put(voicing, note);
	}

	public double getPosition() {
		return position;
	}
	public void setPosition(double position) {
		this.position = position;
	}

	public NoteDurationStrategy getDurationStrategy() { return durationStrategy; }
	public void setDurationStrategy(NoteDurationStrategy durationStrategy) {
		this.durationStrategy = durationStrategy;
	}

	public ScaleTraversalStrategy getScaleTraversalStrategy() {
		return scaleTraversalStrategy;
	}

	public void setScaleTraversalStrategy(ScaleTraversalStrategy scaleTraversalStrategy) {
		this.scaleTraversalStrategy = scaleTraversalStrategy;
	}

	public double getNoteDuration(DoubleUnaryOperator timeForDuration,
								  double position, double nextPosition,
								  double originalDurationSeconds) {
		return durationStrategy.getLength(timeForDuration, position, nextPosition,
							originalDurationSeconds, getNoteDurationSelection());
	}

	public double getNoteDurationSelection() { return noteDuration; }
	public void setNoteDurationSelection(double noteDuration) { this.noteDuration = noteDuration; }

	public List<Double> getScalePositions() { return scalePositions; }
	public void setScalePosition(List<Double> scalePositions) { this.scalePositions = scalePositions; }

	public PatternDirection getDirection() {
		return direction;
	}
	public void setDirection(PatternDirection direction) {
		this.direction = direction;
	}

	public int getRepeatCount() {
		return repeatCount;
	}
	public void setRepeatCount(int repeatCount) {
		this.repeatCount = repeatCount;
	}

	public double getRepeatDuration() {
		return repeatDuration;
	}
	public void setRepeatDuration(double repeatDuration) {
		this.repeatDuration = repeatDuration;
	}

	public PackedCollection getAutomationParameters() {
		return automationParameters;
	}

	public void setAutomationParameters(PackedCollection automationParameters) {
		this.automationParameters = automationParameters;
	}

	@JsonIgnore
	public void setTuning(KeyboardTuning tuning) {
		this.notes.values().forEach(n -> n.setTuning(tuning));
	}

	public List<Double> getPositions() {
		return IntStream.range(0, repeatCount)
				.mapToObj(i -> position + (i * repeatDuration))
				.collect(Collectors.toList());
	}

	public List<RenderedNoteAudio> getNoteDestinations(boolean melodic, double offset,
													   AudioSceneContext context,
													   NoteAudioContext audioContext) {
		return getScaleTraversalStrategy()
				.getNoteDestinations(this, melodic, offset,
									context, audioContext);
	}

	public Producer<PackedCollection> getNoteAudio(ElementVoicingDetails details,
													  Factor<PackedCollection> automationLevel,
													  DoubleFunction<PatternNoteAudio> audioSelection,
													  DoubleUnaryOperator timeForDuration) {
		KeyPosition<?> k = details.isMelodic() ? details.getTarget() : null;

		double originalDuration = getNote(details.getVoicing())
				.getDuration(details.getTarget(), audioSelection);

		if (getDurationStrategy() == NoteDurationStrategy.NONE) {
			return getNote(details.getVoicing()).getAudio(k,
					details.getStereoChannel().getIndex(),
					originalDuration, automationLevel, audioSelection);
		} else {
			double duration = getNoteDuration(timeForDuration, details.getPosition(),
					details.getNextNotePosition(), originalDuration);
			return getNote(details.getVoicing()).getAudio(k,
					details.getStereoChannel().getIndex(), duration,
					automationLevel, audioSelection);
		}
	}

	public boolean isPresent(double start, double end) {
		for (int i = 0; i < repeatCount; i++) {
			double pos = getPosition() + i * repeatDuration;
			if (pos >= start && pos < end) return true;
		}

		return false;
	}
}
