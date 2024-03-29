/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.code.ProducerComputation;
import org.almostrealism.algebra.Vector;
import io.almostrealism.code.CollectionUtils;

import java.util.function.Supplier;

public interface AcceleratedConditionalStatementVector extends AcceleratedConditionalStatement<Vector>, ProducerComputation<Vector> {
	default AcceleratedConjunctionVector and(AcceleratedConditionalStatement<Vector> operand, Supplier trueValue, Supplier falseValue) {
		return and(trueValue, falseValue, operand);
	}

	default AcceleratedConjunctionVector and(Supplier trueValue, Supplier falseValue, AcceleratedConditionalStatement<Vector>... operands) {
		return new AcceleratedConjunctionVector(trueValue, falseValue,
				CollectionUtils.include(new AcceleratedConditionalStatement[0], this, operands));
	}
}
