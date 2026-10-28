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

import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.data.ParameterFunction;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.music.notes.NoteAudioContext;
import org.almostrealism.music.notes.PatternNoteAudio;
import org.almostrealism.music.notes.PatternNoteAudioChoice;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests the selection-value domain contract of the list-based
 * {@link NoteAudioContext} constructor.
 *
 * <p>Selection values in the pattern system are produced by
 * {@link ParameterFunction#positive()}, which is {@code Math.abs(sin(...))} and
 * therefore ranges over the <em>closed</em> interval {@code [0, 1]} (its own
 * javadoc documents "values in [0, 1]"). A selection of exactly {@code 1.0} is
 * attainable, so every consumer that maps a selection value to a list index must
 * map that boundary to the final element rather than overrunning the list.</p>
 */
public class NoteAudioContextTest extends TestSuiteBase {

	/**
	 * A selection value of exactly {@code 1.0} — the top of the closed selection
	 * domain produced by {@link ParameterFunction#positive()} — must select the
	 * final audio choice, not throw {@link IndexOutOfBoundsException}.
	 */
	@Test(timeout = 30000)
	public void selectAudioAtDomainMaximumReturnsLastChoice() {
		List<PatternNoteAudio> choices = List.of(
				new PatternNoteAudioChoice(0.0),
				new PatternNoteAudioChoice(0.5));

		NoteAudioContext context = new NoteAudioContext(
				ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT,
				choices, pos -> pos);

		// The selection value originates from the pattern system's own producer.
		// ParameterFunction(0, 0, 0, 0.25) evaluates to sin(2*pi*0.25) = sin(pi/2) = 1.0,
		// so positive() yields exactly 1.0 — a legitimate value at the top of [0, 1].
		double selection = new ParameterFunction(0.0, 0.0, 0.0, 0.25)
				.positive().apply(new ParameterSet());
		Assert.assertEquals(1.0, selection, 0.0);

		PatternNoteAudio selected = context.selectAudio(selection);

		Assert.assertSame(choices.get(choices.size() - 1), selected);
	}

	/**
	 * Selection values strictly below {@code 1.0} must keep mapping to
	 * {@code floor(selection * size)}, so the clamp only affects the top boundary
	 * and does not shift the bins of any other choice.
	 */
	@Test(timeout = 30000)
	public void selectAudioBelowDomainMaximumUsesProportionalIndex() {
		List<PatternNoteAudio> choices = List.of(
				new PatternNoteAudioChoice(0.0),
				new PatternNoteAudioChoice(0.25),
				new PatternNoteAudioChoice(0.5),
				new PatternNoteAudioChoice(0.75));

		NoteAudioContext context = new NoteAudioContext(
				ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT,
				choices, pos -> pos);

		Assert.assertSame(choices.get(0), context.selectAudio(0.0));
		Assert.assertSame(choices.get(0), context.selectAudio(0.2499));
		Assert.assertSame(choices.get(1), context.selectAudio(0.25));
		Assert.assertSame(choices.get(2), context.selectAudio(0.5));
		Assert.assertSame(choices.get(2), context.selectAudio(0.7499));
		Assert.assertSame(choices.get(3), context.selectAudio(0.75));
		Assert.assertSame(choices.get(3), context.selectAudio(Math.nextDown(1.0)));
	}

	/**
	 * With no audio choices there is nothing to select, so every selection value,
	 * including both ends of the domain, yields {@code null} rather than an
	 * index into an empty list.
	 */
	@Test(timeout = 30000)
	public void selectAudioWithNoChoicesReturnsNull() {
		NoteAudioContext context = new NoteAudioContext(
				ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT,
				List.of(), pos -> pos);

		Assert.assertNull(context.selectAudio(0.0));
		Assert.assertNull(context.selectAudio(0.5));
		Assert.assertNull(context.selectAudio(1.0));
	}
}
