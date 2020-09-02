/*
 * Copyright 2017 Michael Murray
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

import org.almostrealism.color.RGB;
import org.almostrealism.heredity.Gene;

/**
 * TODO  Accept a {@link Gene}.
 * 
 * @author Michael Murray
 */
public class RandomColorGenerator extends ColorProducerAdapter {
 	private RGBProducer baseRGB, offsetRGB;
 
	public RandomColorGenerator() {
		this(new RGB(0.0, 0.0, 0.0), new RGB(1.0, 1.0, 1.0));
	}
	
	public RandomColorGenerator(RGBProducer baseRGB, RGBProducer offsetRGB) {
		this.baseRGB = baseRGB;
		this.offsetRGB = offsetRGB;
	}
	
	public void setBaseRGB(ColorProducer base) { this.baseRGB = base; }
	public void setOffsetRGB(ColorProducer offset) { this.offsetRGB = offset; }
	
	public RGBProducer getBaseRGB() { return this.baseRGB; }
	public RGBProducer getOffsetRGB() { return this.offsetRGB; }
	
	/** @see ColorProducer#evaluate(java.lang.Object[]) */
	public RGB evaluate(Object args[]) {
		RGB base = this.baseRGB.evaluate(args);
		RGB off = this.offsetRGB.evaluate(args);
		
		base.setRed(base.getRed() + Math.random() * off.getRed());
		base.setGreen(base.getGreen() + Math.random() * off.getGreen());
		base.setBlue(base.getBlue() + Math.random() * off.getBlue());
		
		return base;
	}

	/**
	 * Delegates to {@link ColorProducer#compact()}
	 * on the base color and offset color.
	 */
	public void compact() {
		baseRGB.compact();
		offsetRGB.compact();
	}
}