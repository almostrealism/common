/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.hardware.test;

import io.almostrealism.code.ComputeRequirement;
import org.almostrealism.CodeFeatures;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarProducer;
import org.junit.Test;

public class AltComputeContextsTest implements CodeFeatures {
	// TODO  @Test
	public void clAndNative() {
		dc(() -> {
			Scalar result = new Scalar();

			ScalarProducer sum = scalarAdd(v(1.0), v(2.0));
			ScalarProducer product = scalarsMultiply(v(3.0), v(2.0));

			cc(() -> a(2, p(result), sum).get().run(), ComputeRequirement.CL);
			System.out.println("Result = " + result.getValue());

			cc(() -> a(2, p(result), product).get().run(), ComputeRequirement.C);
			System.out.println("Result = " + result.getValue());
		});
	}
}
