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
 * 
 * @author Michael Murray
 */
public class RandomColorGenerator implements ProducerComputation<PackedCollection> {
 	private Producer<PackedCollection> baseRGB, offsetRGB;
 
	public RandomColorGenerator() {
		this(RGBFeatures.getInstance().black(), RGBFeatures.getInstance().white());
	}
	
	public RandomColorGenerator(Producer<PackedCollection> baseRGB, Producer<PackedCollection> offsetRGB) {
		this.baseRGB = baseRGB;
		this.offsetRGB = offsetRGB;
	}
	
	public void setBaseRGB(Producer<PackedCollection> base) { this.baseRGB = base; }
	public void setOffsetRGB(Producer<PackedCollection> offset) { this.offsetRGB = offset; }
	
	public Producer<PackedCollection> getBaseRGB() { return this.baseRGB; }
	public Producer<PackedCollection> getOffsetRGB() { return this.offsetRGB; }

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

	@Override
	public Scope<PackedCollection> getScope(KernelStructureContext context) {
		throw new RuntimeException("Not implemented");
	}
}
