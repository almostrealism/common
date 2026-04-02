/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.color.computations;

import io.almostrealism.code.ProducerComputation;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.Scope;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.color.RGB;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Computes a weighted average of multiple {@link RGB} colors.
 *
 * <p>Colors are added with associated probability weights via {@link #addColor(double, RGB)}.
 * When evaluated, each color is scaled by its weight divided by the total weight sum,
 * and the contributions are accumulated into a single output {@link RGB}.</p>
 *
 * <p>If {@code invert} mode is enabled, weights are inverted (replaced by {@code 1.0 / p})
 * before accumulation, giving higher influence to samples with lower raw weights.</p>
 *
 * @see RGB
 * @author Michael Murray
 */
public class AverageColor implements ProducerComputation<PackedCollection> {
	/**
	 * Internal record that pairs a weight with an {@link RGB} color sample.
	 */
	private static class Color {
		/** The weight of this color sample in the weighted average. */
		double p;
		/** The RGB color sample. */
		RGB c;
	}

	/** The list of weighted color samples accumulated so far. */
	private List<Color> colors;

	/** The running total of all weights added via {@link #addColor(double, RGB)}. */
	private double tot;

	/** When {@code true}, weights are inverted before being stored. */
	private boolean invert;

	/**
	 * Constructs an empty {@link AverageColor} with no samples.
	 */
	public AverageColor() {
		this.colors = new ArrayList<Color>();
	}

	/**
	 * Adds a color sample with the given weight.
	 *
	 * <p>If {@link #setInvert(boolean)} is {@code true}, the weight is replaced by
	 * {@code 1.0 / p} before being stored.</p>
	 *
	 * @param p the weight of this color sample (inverted if invert mode is on)
	 * @param c the RGB color sample to add
	 */
	public void addColor(double p, RGB c) {
		if (this.invert) p = 1.0 / p;
		
		Color color = new Color();
		color.p = p;
		color.c = c;
		this.colors.add(color);
		this.tot += p;
	}
	
	/**
	 * Sets whether weights passed to {@link #addColor(double, RGB)} should be inverted.
	 *
	 * @param invert {@code true} to store {@code 1/p} instead of {@code p}
	 */
	public void setInvert(boolean invert) { this.invert = invert; }

	/**
	 * Returns an {@link Evaluable} that computes the weighted average of all added color samples.
	 *
	 * <p>Null color samples are silently skipped.</p>
	 *
	 * @return an {@link Evaluable} producing the weighted-average {@link RGB}
	 */
	@Override
	public Evaluable<PackedCollection> get() {
		return new DynamicCollectionProducer(RGB.shape(), args -> {
			RGB c = new RGB(0.0, 0.0, 0.0);
			Iterator itr = this.colors.iterator();

			w:
			while (itr.hasNext()) {
				Color n = (Color) itr.next();
				if (n.c == null) continue w;
				c.addTo(n.c.multiply(n.p / this.tot));
			}

			return c;
		}).get();
	}

	/**
	 * Not implemented — {@link AverageColor} does not support kernel-based evaluation.
	 *
	 * @param context the kernel structure context (unused)
	 * @throws RuntimeException always
	 */
	@Override
	public Scope<PackedCollection> getScope(KernelStructureContext context) {
		throw new RuntimeException("Not implemented");
	}
}
