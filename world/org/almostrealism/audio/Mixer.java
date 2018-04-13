/*
 * Copyright 2018 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.audio;

import java.util.ArrayList;
import java.util.Iterator;

import org.almostrealism.graph.Source;
import org.almostrealism.graph.SummationCell;
import org.almostrealism.time.Temporal;

public class Mixer extends ArrayList<Source> implements Temporal {
	private AudioProteinCache cache;
	private SummationCell c;
	
	public Mixer(AudioProteinCache cache, SummationCell receptor) {
		this.cache = cache;
		this.c = receptor;
	}
	
	@Override
	public void tick() {
		for (Source s : this) {
			c.push(cache.addProtein(s.next()));
		}
		
		Iterator<Source> itr = iterator();
		while (itr.hasNext()) if (itr.next().isDone()) itr.remove();
		
		this.c.tick();
	}
}
