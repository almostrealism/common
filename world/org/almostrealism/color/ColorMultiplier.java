/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.color;

import org.almostrealism.uml.Function;

/**
 * {@link ColorMultiplier} is a {@link ColorProducer} which evaluates another.
 *
 * @author  Michael Murray
 */
@Deprecated // Replaced with ColorProduct
@Function
public class ColorMultiplier extends ColorProducerAdapter {
	private RGBProducer color;
	private RGBProducer multiplier;
	
	public ColorMultiplier(RGBProducer color, double multiplier) {
		this.color = color;
		this.multiplier = new RGB(multiplier, multiplier, multiplier);
	}
	
	public ColorMultiplier(RGBProducer color, RGBProducer multiplier) {
		this.color = color;
		this.multiplier = multiplier;
	}
	
	@Override
	public RGB evaluate(Object[] args) {
		return new RGB(color.evaluate(args).getRed() * multiplier.evaluate(args).getRed(),
				color.evaluate(args).getGreen() * multiplier.evaluate(args).getGreen(),
				color.evaluate(args).getBlue() * multiplier.evaluate(args).getBlue());
	}

	@Override
	public void compact() {
		color.compact();
		multiplier.compact();
	}
}
