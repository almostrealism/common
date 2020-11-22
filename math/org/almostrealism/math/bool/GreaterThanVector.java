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

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorBank;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.util.Evaluable;

import java.util.function.Supplier;

public class GreaterThanVector extends GreaterThan<Vector> implements AcceleratedConditionalStatementVector {
	public GreaterThanVector() {
		this(null, null, null, null);
	}

	public GreaterThanVector(
			Supplier leftOperand,
			Supplier rightOperand,
			Supplier<Evaluable<Vector>> trueValue,
			Supplier<Evaluable<Vector>> falseValue) {
		super(3, () -> Vector.blank(), leftOperand, rightOperand, trueValue, falseValue, false);
	}

	@Override
	public MemoryBank<Vector> createKernelDestination(int size) { return new VectorBank(size); }
}
