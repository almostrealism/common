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
