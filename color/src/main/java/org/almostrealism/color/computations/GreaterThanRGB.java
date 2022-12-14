/*
 * Copyright 2020 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.color.computations;

import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBBank;
import org.almostrealism.color.RGBProducer;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.bool.GreaterThan;

import java.util.function.Supplier;

public class GreaterThanRGB extends GreaterThan<RGB> implements RGBProducer {
	public GreaterThanRGB(
			Supplier leftOperand,
			Supplier rightOperand,
			Supplier trueValue,
			Supplier falseValue) {
		super(3, RGB::new, RGBBank::new, leftOperand, rightOperand,
				trueValue, falseValue, false);
	}
}
