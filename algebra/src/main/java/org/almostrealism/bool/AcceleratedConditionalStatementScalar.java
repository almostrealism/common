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
import org.almostrealism.algebra.ScalarEvaluable;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.hardware.CollectionUtils;
import io.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

public interface AcceleratedConditionalStatementScalar extends AcceleratedConditionalStatement<Scalar>, ScalarProducer {
	default AcceleratedConjunctionScalar and(AcceleratedConditionalStatement<Scalar> operand, Supplier<Evaluable<Scalar>> trueValue, Supplier<Evaluable<Scalar>> falseValue) {
		return and(trueValue, falseValue, operand);
	}

	default AcceleratedConjunctionScalar and(Supplier<Evaluable<Scalar>> trueValue, Supplier<Evaluable<Scalar>> falseValue, AcceleratedConditionalStatement<Scalar>... operands) {
		return new AcceleratedConjunctionScalar(trueValue, falseValue,
				CollectionUtils.include(new AcceleratedConditionalStatement[0], this, operands));
	}
}
