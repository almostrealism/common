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

package org.almostrealism.math.bool;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.util.Producer;

public class LessThanScalar extends LessThan<Scalar> implements AcceleratedConditionalStatementScalar {
	public LessThanScalar() {
		this(null, null, null, null);
	}

	public LessThanScalar(Producer<Scalar> leftOperand, Producer<Scalar> rightOperand) {
		this(leftOperand, rightOperand, null, null);
	}

	public LessThanScalar(Producer<Scalar> leftOperand, Producer<Scalar> rightOperand, boolean includeEqual) {
		this(leftOperand, rightOperand, null, null, includeEqual);
	}

	public LessThanScalar(
			Producer<Scalar> leftOperand,
			Producer<Scalar> rightOperand,
			Producer<Scalar> trueValue,
			Producer<Scalar> falseValue) {
		this(leftOperand, rightOperand, trueValue, falseValue, false);
	}

	public LessThanScalar(
			Producer<Scalar> leftOperand,
			Producer<Scalar> rightOperand,
			Producer<Scalar> trueValue,
			Producer<Scalar> falseValue,
			boolean includeEqual) {
		super(2, Scalar.blank(), leftOperand, rightOperand, trueValue, falseValue, includeEqual);
	}

	@Override
	public MemoryBank<Scalar> createKernelDestination(int size) { return new ScalarBank(size); }
}
