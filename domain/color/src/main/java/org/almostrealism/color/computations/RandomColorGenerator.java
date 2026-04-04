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
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBFeatures;

/**
 * Produces a randomly perturbed RGB color by adding a uniformly-distributed random offset
 * to a base color.
 *
 * <p>At each evaluation the red, green, and blue channels of the base color are independently
 * increased by a random fraction of the corresponding channel in the offset color:
 * <pre>
 * result.r = base.r + random() * offset.r
 * result.g = base.g + random() * offset.g
 * result.b = base.b + random() * offset.b
 * </pre>
 *
 * <p>The default configuration uses black as the base and white as the offset, producing
 * a uniformly random color in [0, 1]^3.</p>
 *
 * @author Michael Murray
 */
public class RandomColorGenerator implements ProducerComputation<PackedCollection> {
	/** The base color to which the random offset is added. */
 	private Producer<PackedCollection> baseRGB;

	/** The maximum random offset applied per channel. */
	private Producer<PackedCollection> offsetRGB;

	/**
	 * Constructs a {@link RandomColorGenerator} with a black base and white offset,
	 * producing uniformly-random colors across [0, 1]^3.
	 */
	public RandomColorGenerator() {
		this(RGBFeatures.getInstance().black(), RGBFeatures.getInstance().white());
	}
	
	/**
	 * Constructs a {@link RandomColorGenerator} with the specified base and offset color producers.
	 *
	 * @param baseRGB   the base color producer
	 * @param offsetRGB the maximum per-channel random offset
	 */
	public RandomColorGenerator(Producer<PackedCollection> baseRGB, Producer<PackedCollection> offsetRGB) {
		this.baseRGB = baseRGB;
		this.offsetRGB = offsetRGB;
	}
	
	/**
	 * Sets the base color producer used before the random offset is applied.
	 *
	 * @param base the new base color producer
	 */
	public void setBaseRGB(Producer<PackedCollection> base) { this.baseRGB = base; }

	/**
	 * Sets the maximum per-channel random offset applied during evaluation.
	 *
	 * @param offset the new offset color producer
	 */
	public void setOffsetRGB(Producer<PackedCollection> offset) { this.offsetRGB = offset; }

	/**
	 * Returns the base color producer.
	 *
	 * @return the base color producer
	 */
	public Producer<PackedCollection> getBaseRGB() { return this.baseRGB; }

	/**
	 * Returns the maximum per-channel random offset producer.
	 *
	 * @return the offset color producer
	 */
	public Producer<PackedCollection> getOffsetRGB() { return this.offsetRGB; }

	/**
	 * Returns an {@link Evaluable} that produces a randomly-perturbed color.
	 *
	 * @return an {@link Evaluable} computing {@code base + random * offset} per channel
	 */
	@Override
	public Evaluable<PackedCollection> get() {
		return new DynamicCollectionProducer(RGB.shape(), args -> {
			PackedCollection baseResult = this.baseRGB.get().evaluate(args);
			PackedCollection offResult = this.offsetRGB.get().evaluate(args);

			RGB base = baseResult instanceof RGB ? (RGB) baseResult : new RGB(baseResult.toDouble(0), baseResult.toDouble(1), baseResult.toDouble(2));
			RGB off = offResult instanceof RGB ? (RGB) offResult : new RGB(offResult.toDouble(0), offResult.toDouble(1), offResult.toDouble(2));

			base.setRed(base.getRed() + Math.random() * off.getRed());
			base.setGreen(base.getGreen() + Math.random() * off.getGreen());
			base.setBlue(base.getBlue() + Math.random() * off.getBlue());

			return base;
		}).get();
	}

	/**
	 * Not implemented — {@link RandomColorGenerator} does not support kernel-based evaluation.
	 *
	 * @param context the kernel structure context (unused)
	 * @throws RuntimeException always
	 */
	@Override
	public Scope<PackedCollection> getScope(KernelStructureContext context) {
		throw new RuntimeException("Not implemented");
	}
}
