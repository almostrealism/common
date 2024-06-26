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
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBFeatures;

/**
 * 
 * @author Michael Murray
 */
public class RandomColorGenerator implements ProducerComputation<RGB> {
 	private Producer<RGB> baseRGB, offsetRGB;
 
	public RandomColorGenerator() {
		this(RGBFeatures.getInstance().black(), RGBFeatures.getInstance().white());
	}
	
	public RandomColorGenerator(Producer<RGB> baseRGB, Producer<RGB> offsetRGB) {
		this.baseRGB = baseRGB;
		this.offsetRGB = offsetRGB;
	}
	
	public void setBaseRGB(Producer<RGB> base) { this.baseRGB = base; }
	public void setOffsetRGB(Producer<RGB> offset) { this.offsetRGB = offset; }
	
	public Producer<RGB> getBaseRGB() { return this.baseRGB; }
	public Producer<RGB> getOffsetRGB() { return this.offsetRGB; }

	@Override
	public Evaluable<RGB> get() {
		return new DynamicCollectionProducer<>(RGB.shape(), args -> {
			RGB base = this.baseRGB.get().evaluate(args);
			RGB off = this.offsetRGB.get().evaluate(args);

			base.setRed(base.getRed() + Math.random() * off.getRed());
			base.setGreen(base.getGreen() + Math.random() * off.getGreen());
			base.setBlue(base.getBlue() + Math.random() * off.getBlue());

			return base;
		}).get();
	}

	@Override
	public Scope<RGB> getScope(KernelStructureContext context) {
		throw new RuntimeException("Not implemented");
	}
}
