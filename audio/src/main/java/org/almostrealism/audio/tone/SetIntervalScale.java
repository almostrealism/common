/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.audio.tone;

/**
 * A scale implementation that generates notes algorithmically from a root note and interval pattern.
 *
 * <p>SetIntervalScale constructs musical scales by applying a sequence of semitone intervals
 * starting from a root note. This is the standard approach for defining scales like major,
 * minor, pentatonic, etc., and is used by {@link WesternScales} for creating common scales.</p>
 *
 * <h2>Interval Representation</h2>
 * <p>Intervals are specified as the number of semitones (half-steps) between adjacent notes
 * in the scale. For example:</p>
 * <ul>
 *   <li><b>Major scale</b>: [2, 2, 1, 2, 2, 2, 1] (whole-whole-half-whole-whole-whole-half)</li>
 *   <li><b>Minor scale</b>: [2, 1, 2, 2, 1, 2, 2] (whole-half-whole-whole-half-whole-whole)</li>
 *   <li><b>Pentatonic</b>: [2, 2, 3, 2, 3]</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create a C major scale (1 octave)
 * SetIntervalScale<WesternChromatic> cMajor = new SetIntervalScale<>(
 *     WesternChromatic.C4,  // root
 *     1,                    // repetitions (1 octave)
 *     2, 2, 1, 2, 2, 2, 1   // major scale intervals
 * );
 *
 * // Access notes generated from intervals
 * WesternChromatic root = cMajor.valueAt(0);  // C4
 * WesternChromatic second = cMajor.valueAt(1);  // D4 (2 semitones up)
 * WesternChromatic third = cMajor.valueAt(2);  // E4 (2 more semitones)
 * }</pre>
 *
 * <p>The scale length is determined by {@code repetitions * intervals.length}, allowing
 * multi-octave scales to be generated from a single interval pattern.</p>
 *
 * @param <T> the type of key position (typically {@link WesternChromatic})
 * @see Scale
 * @see StaticScale
 * @see WesternScales
 * @see KeyPosition
 */
public class SetIntervalScale<T extends KeyPosition<T>> implements Scale<T> {
	private T root;
	private int repetitions;
	private int[] intervals;

	public SetIntervalScale() { }

	public SetIntervalScale(T root, int repetitions, int... intervals) {
		this.root = root;
		this.repetitions = repetitions;
		this.intervals = intervals;
	}

	public T getRoot() { return root; }
	public void setRoot(T root) { this.root = root; }

	public void setRoot(String root) { setRoot((T) WesternChromatic.valueOf(root)); }

	public int getRepetitions() { return repetitions; }
	public void setRepetitions(int repetitions) { this.repetitions = repetitions; }

	public int[] getIntervals() { return intervals; }
	public void setIntervals(int[] intervals) { this.intervals = intervals; }

	@Override
	public T valueAt(int position) {
		if (position == 0) {
			return root;
		} else if (position > intervals.length) {
			throw new UnsupportedOperationException(); // TODO
		} else {
			T note = valueAt(position - 1);
			for (int i = 0; i < intervals[position - 1]; i++) {
				note = note.next();
			}

			return note;
		}
	}

	@Override
	public int length() { return repetitions * intervals.length; }
}
