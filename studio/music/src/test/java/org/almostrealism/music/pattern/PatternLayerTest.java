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

package org.almostrealism.music.pattern;

import org.almostrealism.music.notes.PatternNote;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests for {@link PatternLayer#getElements(double, double)} range selection in
 * the presence of element repetitions.
 *
 * <p>A {@link PatternElement} sounds at more than one onset when it repeats:
 * {@link PatternElement#getPositions()} enumerates {@code position + i * repeatDuration}
 * for each of {@code repeatCount} repetitions, and the render path
 * ({@code ScaleTraversalStrategy}) emits a note for every one of them. A range
 * query must therefore report an element as present when any of its repetition
 * onsets falls in the range, exactly as {@link PatternElement#isPresent(double, double)}
 * defines it — not only when its base position does.</p>
 *
 * <p>The consequence of the base-position-only filter is visible in
 * {@link PatternLayerManager#nextNotePosition(double)}, which selects elements
 * with {@code getAllElements(position, duration)} and then expands their
 * {@link PatternElement#getPositions()}: an element whose base position precedes
 * {@code position} but which repeats after it is dropped, so the next onset is
 * missed and note durations for the {@code NO_OVERLAP}/{@code UNTIL_NEXT}
 * strategies are stretched to the pattern end.</p>
 */
public class PatternLayerTest extends TestSuiteBase {

	/**
	 * Builds a one-element layer where the element sits at position {@code 0.0}
	 * and repeats four times at a spacing of one measure, so it sounds at
	 * {@code 0.0, 1.0, 2.0, 3.0}.
	 */
	private PatternLayer singleRepeatingElementLayer() {
		PatternElement e = new PatternElement((PatternNote) null, 0.0);
		e.setRepeatCount(4);
		e.setRepeatDuration(1.0);

		PatternLayer layer = new PatternLayer();
		layer.setElements(List.of(e));
		return layer;
	}

	/**
	 * An element whose base position precedes the range but which repeats into
	 * it must be selected: its repetitions at {@code 2.0} and {@code 3.0} fall in
	 * {@code [1.5, 4.0)} even though its base position {@code 0.0} does not.
	 */
	@Test(timeout = 10000)
	public void repeatedElementFoundWhenRepetitionInRange() {
		PatternLayer layer = singleRepeatingElementLayer();

		List<PatternElement> found = layer.getElements(1.5, 4.0);

		Assert.assertEquals(1, found.size());
	}

	/**
	 * The next onset strictly after {@code 0.5}, computed the way
	 * {@link PatternLayerManager#nextNotePosition(double)} does — select the
	 * elements in {@code [0.5, duration)}, expand their repetition positions, and
	 * take the least one greater than {@code 0.5} — is the repetition at
	 * {@code 1.0}, not the pattern end.
	 */
	@Test(timeout = 10000)
	public void nextOnsetAfterPositionUsesRepetitions() {
		PatternLayer layer = singleRepeatingElementLayer();
		double duration = 4.0;

		double next = layer.getAllElements(0.5, duration).stream()
				.map(PatternElement::getPositions)
				.flatMap(List::stream)
				.filter(p -> p > 0.5)
				.mapToDouble(p -> p)
				.min()
				.orElse(duration);

		Assert.assertEquals(1.0, next, 1e-9);
	}

	/**
	 * A non-repeating element is still selected only when its single onset is in
	 * range, so tightening the filter to be repetition-aware does not widen
	 * selection for the ordinary {@code repeatCount == 1} case.
	 */
	@Test(timeout = 10000)
	public void nonRepeatingElementSelectedOnlyWhenOnsetInRange() {
		PatternElement e = new PatternElement((PatternNote) null, 2.0);

		PatternLayer layer = new PatternLayer();
		layer.setElements(List.of(e));

		Assert.assertTrue(layer.getElements(0.0, 1.0).isEmpty());
		Assert.assertEquals(1, layer.getElements(2.0, 3.0).size());
	}
}
