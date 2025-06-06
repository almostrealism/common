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

package org.almostrealism.heredity;

import java.util.HashMap;
import java.util.List;
import java.util.Random;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Factory;
import io.almostrealism.relation.Provider;
import org.almostrealism.collect.PackedCollection;

public class ProbabilisticFactory<V> extends HashMap<Factory<V>, Double> implements Factory<V> {
	private Random random;

	/** Constructs an empty {@link ProbabilisticFactory}. */
	public ProbabilisticFactory() {
		random = new Random();
	}
	
	/**
	 * Constructs a {@link ProbabilisticFactory} with the specified factories, where
	 * probabilities are determined by evaluating the {@link Factor}s in the specified
	 * {@link Gene}. The value 1.0 is used as an argument, making it convenient to use
	 * the {@link ScaleFactor} to specify scalar probability values.
	 */
	public ProbabilisticFactory(List<? extends Factory<V>> factories, Gene<PackedCollection<?>> probabilities) {
		for (int i = 0; i < factories.size(); i++) {
			Evaluable<PackedCollection<?>> ev = probabilities.valueAt(i).getResultant(() -> {
				PackedCollection<?> s = new PackedCollection<>(1);
				s.setMem(1.0);
				return new Provider(s);
			}).get();
			put(factories.get(i), ev.evaluate().toDouble(0));
		}
	}
	
	@Override
	public V construct() {
		double r = random.nextDouble();
		double p = 0;
		
		for (Entry<Factory<V>, Double> e : entrySet()) {
			p += e.getValue();
			if (p > r) return e.getKey().construct();
		}
		
		return null;
	}
	
	protected double total() {
		double tot = 0;
		for (double d : values()) tot += d;
		return tot;
	}
}
