/*
 * Copyright 2022 Michael Murray
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

import java.util.ArrayList;
import java.util.List;

public class StaticScale<T extends KeyPosition> implements Scale<T> {
	private List<T> notes;

	public StaticScale() { }

	public StaticScale(T[] notes) { setNotes(List.of(notes)); }

	public List<T> getNotes() { return notes; }

	public void setNotes(List<T> notes) {
		this.notes = new ArrayList<>();

		List n = notes;
		for (int i = 0; i < n.size(); i++) {
			if (n.get(i) instanceof String) {
				this.notes.add((T) WesternChromatic.valueOf((String) n.get(i)));
			} else {
				this.notes.add(notes.get(i));
			}
		}
	}

	@Override
	public int length() {
		return notes.size();
	}

	@Override
	public T valueAt(int pos) {
		return notes.get(pos);
	}
}
