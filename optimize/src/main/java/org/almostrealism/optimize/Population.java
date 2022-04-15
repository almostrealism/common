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

package org.almostrealism.optimize;

import org.almostrealism.graph.Receptor;
import org.almostrealism.heredity.Genome;
import org.almostrealism.graph.temporal.GeneticTemporalFactory;
import org.almostrealism.time.Temporal;

import java.util.List;

public interface Population<G, T, O extends Temporal> {
	void init(Genome<G> templateGenome, List<? extends Receptor<T>> measures, Receptor<T> output);

	List<Genome<G>> getGenomes();
	
	int size();

	O enableGenome(int index);

	void disableGenome();
}
