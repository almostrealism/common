/*
 * Copyright 2020 Michael Murray
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

import io.almostrealism.code.Scope;
import org.almostrealism.color.RGB;
import org.almostrealism.heredity.Gene;
import io.almostrealism.relation.NameProvider;
import io.almostrealism.relation.Evaluable;

/**
 * TODO  Accept a {@link Gene}.
 * 
 * @author Michael Murray
 */
public class RandomColorGenerator extends ColorProducerAdapter {
 	private RGBProducer baseRGB, offsetRGB;
 
	public RandomColorGenerator() {
		this(RGBBlack.getInstance(), RGBWhite.getInstance());
	}
	
	public RandomColorGenerator(RGBProducer baseRGB, RGBProducer offsetRGB) {
		this.baseRGB = baseRGB;
		this.offsetRGB = offsetRGB;
	}
	
	public void setBaseRGB(RGBProducer base) { this.baseRGB = base; }
	public void setOffsetRGB(RGBProducer offset) { this.offsetRGB = offset; }
	
	public RGBProducer getBaseRGB() { return this.baseRGB; }
	public RGBProducer getOffsetRGB() { return this.offsetRGB; }

	@Override
	public Evaluable<RGB> get() {
		return args -> {
			RGB base = this.baseRGB.get().evaluate(args);
			RGB off = this.offsetRGB.get().evaluate(args);

			base.setRed(base.getRed() + Math.random() * off.getRed());
			base.setGreen(base.getGreen() + Math.random() * off.getGreen());
			base.setBlue(base.getBlue() + Math.random() * off.getBlue());

			return base;
		};
	}

	@Override
	public Scope<RGB> getScope(NameProvider provider) {
		throw new RuntimeException("Not implemented");
	}

	/**
	 * Delegates to {@link RGBProducer#compact()}
	 * on the base color and offset color.
	 */
	@Override
	public void compact() {
		baseRGB.compact();
		offsetRGB.compact();
	}
}
