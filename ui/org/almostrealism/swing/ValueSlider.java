/*
 * Copyright 2016 Michael Murray
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

package org.almostrealism.swing;

import javax.swing.JSlider;

import org.almostrealism.util.ValueProducer;

public class ValueSlider extends JSlider implements ValueProducer {
	private static final int scale = 10000;
	
	private double min, max;
	
	public ValueSlider(int orient, double min, double max, double value) {
		super(orient, 0, scale, 0);
		this.min = min;
		this.max = max;
		this.setValue((int) (scale * (value - min) / (max - min)));
	}
	
	public double value() {
		return (max - min) * (getValue() / (double) scale) + min;
	}
}
