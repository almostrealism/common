/*
 * Copyright 2021 Michael Murray
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
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryData;

import java.util.function.BiFunction;
import java.util.function.Supplier;

@Deprecated
public class LessThanScalar extends LessThan<Scalar> implements AcceleratedConditionalStatementScalar {
	public LessThanScalar(
			Supplier leftOperand, Supplier rightOperand,
			Supplier trueValue, Supplier falseValue) {
		this(leftOperand, rightOperand, trueValue, falseValue, false);
	}

	public LessThanScalar(
			Supplier leftOperand, Supplier rightOperand,
			Supplier trueValue, Supplier falseValue,
			boolean includeEqual) {
		super(2, Scalar::new, ScalarBank::new,
				leftOperand, rightOperand,
				trueValue, falseValue,
				includeEqual);
		setPostprocessor((BiFunction) Scalar.postprocessor());
	}
}
