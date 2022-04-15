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

package org.almostrealism.optimize;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.almostrealism.time.Temporal;

public class AverageHealthComputationSet<T extends Temporal> extends HashSet<HealthComputation<T, ?>> implements HealthComputation<T, HealthScore> {
	private final List<BiConsumer<HealthComputation<T, ?>, Temporal>> listeners;

	private T target;

	public AverageHealthComputationSet() {
		listeners = new ArrayList<>();
	}

	public T getTarget() { return target; }

	@Override
	public void setTarget(T target) {
		this.target = target;
		forEach(c -> c.setTarget(target));
	}

	public void addListener(BiConsumer<HealthComputation<T, ?>, Temporal> listener) {
		listeners.add(listener);
	}

	@Override
	public HealthScore computeHealth() {
		double total = 0;

		for (HealthComputation<T, ?> hc : this) {
			listeners.forEach(l -> l.accept(hc, getTarget()));
			total += hc.computeHealth().getScore();
		}

		double score = total / size();
		return () -> score;
	}
}
