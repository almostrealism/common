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

import org.almostrealism.algebra.computations.NAryDynamicAcceleratedProducer;
import org.almostrealism.color.RGB;
import org.almostrealism.util.Producer;

import java.util.function.Supplier;


// TODO  Combine ColorSums that are equal by converting to ColorProduct
// TODO  If all the members of a ColorSum are ColorProducts, and those
//       ColorProducts contain matching terms, the matching terms can
//       be extracted and a the multiplication can be performed after
//       the sum. In a simple example this could take 3 products and
//       two sums and convert it into two sums and two sums and one
//       product

/**
 * @author  Michael Murray
 */
public class ColorSum extends NAryDynamicAcceleratedProducer<RGB> implements RGBSupplier {
	public ColorSum(Supplier<Producer<? extends RGB>>... producers) {
		super("+", 3, () -> RGB.blank(), producers);
	}

	@Override
	public double getIdentity() { return 0; }

	@Override
	public double combine(double a, double b) { return a + b; }

	/**
	 * Returns true if the specified value is 0.0, false otherwise.
	 */
	public boolean isRemove(double value) { return value == 0.0; }
}
