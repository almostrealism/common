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
import org.almostrealism.audio.notes.NoteAudioContext;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.List;

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
				Producer<PackedCollection> note =
						element.getNoteAudio(
								details, relativeAutomationLevel,
								audioContext.getAudioSelection(),
								context.getTimeForDuration());
				destinations.add(new RenderedNoteAudio(note,
						context.frameForPosition(actualPosition)));
			} else if (this == CHORD) {
				p: for (double p : element.getScalePositions()) {
					if (keys.isEmpty()) break;
					int keyIndex = (int) (p * keys.size());

					ElementVoicingDetails details =
							audioContext.createVoicingDetails(melodic,
								keys.get(keyIndex), relativePosition);
					Producer<PackedCollection> note =
							element.getNoteAudio(
									details, relativeAutomationLevel,
									audioContext.getAudioSelection(),
									context.getTimeForDuration());
					destinations.add(new RenderedNoteAudio(note,
							context.frameForPosition(actualPosition)));

					keys.remove(keyIndex);
				}
			} else if (this == SEQUENCE) {
				double p = element.getScalePositions().get(i % element.getScalePositions().size());
				if (keys.isEmpty()) break;

				int keyIndex = (int) (p * keys.size());
				ElementVoicingDetails details =
						audioContext.createVoicingDetails(melodic,
								keys.get(keyIndex), relativePosition);
				Producer<PackedCollection> note = element.getNoteAudio(
							details, relativeAutomationLevel,
							audioContext.getAudioSelection(),
							context.getTimeForDuration());
				destinations.add(new RenderedNoteAudio(note, context.getFrameForPosition().applyAsInt(actualPosition)));
			} else {
				throw new UnsupportedOperationException("Unknown ScaleTraversalStrategy (" + this + ")");
			}
		}

		return destinations;
	}


	@Override
	public Console console() {
		return CellFeatures.console;
	}
}
