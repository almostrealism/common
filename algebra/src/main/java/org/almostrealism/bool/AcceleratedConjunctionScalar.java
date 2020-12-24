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

package org.almostrealism.bool;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.hardware.MemoryBank;

import java.util.function.Supplier;

public class AcceleratedConjunctionScalar extends AcceleratedConjunctionAdapter<Scalar>
		implements AcceleratedConditionalStatementScalar {
	public AcceleratedConjunctionScalar() {
		this(null, null);
	}

	public AcceleratedConjunctionScalar(Supplier trueValue, Supplier falseValue,
										AcceleratedConditionalStatement<Scalar>... conjuncts) {
		super(2, (Supplier<Scalar>) Scalar::new, trueValue, falseValue, conjuncts);
	}

	@Override
	public MemoryBank<Scalar> createKernelDestination(int size) { return new ScalarBank(size); }
}
