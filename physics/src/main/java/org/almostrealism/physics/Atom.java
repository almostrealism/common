/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.physics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class Atom {
	private int protons;
	private List<Shell> shells;
	
	public Atom(int protons, List<Shell> shells) {
		this.protons = protons;

		List<Shell> unmerged = new ArrayList<>();
		unmerged.addAll(shells);
		List<Shell> merged = new ArrayList<>();

		while (unmerged.size() > 0) {
			Shell s = unmerged.get(0);

			Iterator<Shell> itr = unmerged.iterator();

			s: while (itr.hasNext()) {
				Shell sh = itr.next();

				if (s == sh) {
					itr.remove();
				} else if (s.getEnergyLevel() == sh.getEnergyLevel()) {
					s.merge(sh);
					itr.remove();
				}
			}

			merged.add(s);
		}

		this.shells = Collections.unmodifiableList(merged);
	}

	public int getProtons() { return protons; }
	
	public Shell getValenceShell() {
		int highestEnergy = 0;
		Shell sh = null;
		
		for (Shell s : shells) {
			if (s.getEnergyLevel() > highestEnergy) {
				highestEnergy = s.getEnergyLevel();
				sh = s;
			}
		}
		
		return sh;
	}
}
