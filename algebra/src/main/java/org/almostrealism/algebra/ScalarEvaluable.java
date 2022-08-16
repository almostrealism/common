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

package org.almostrealism.algebra;

import io.almostrealism.code.ProducerComputation;
import io.almostrealism.relation.Evaluable;

import org.almostrealism.bool.AcceleratedConditionalStatementVector;
import org.almostrealism.bool.GreaterThanVector;
import org.almostrealism.bool.LessThanVector;

import java.util.function.Supplier;

public interface ScalarEvaluable extends Evaluable<Scalar>, ScalarFeatures {

	default ScalarEvaluable add(Evaluable<Scalar> value) {
		return scalarAdd(this, value);
	}

	default ScalarEvaluable add(Scalar value) {
		return add(ScalarFeatures.of(value).get());
	}

	default ScalarEvaluable add(double value) {
		return add(new Scalar(value));
	}

	default ScalarEvaluable subtract(Evaluable<Scalar> value) {
		return add(scalarMinus(value));
	}

	default ScalarEvaluable multiply(Evaluable<Scalar> value) {
		return (ScalarEvaluable) multiply(() -> value).get();
	}

	default ProducerComputation<Scalar> multiply(Supplier<Evaluable<? extends Scalar>> value) {
		return scalarsMultiply(() -> this, value);
	}

	default ScalarEvaluable multiply(Scalar value) {
		return multiply(ScalarFeatures.of(value).get());
	}

	default ScalarEvaluable multiply(double value) {
		return multiply(new Scalar(value));
	}

	default ScalarEvaluable divide(Evaluable<Scalar> value) {
		return (ScalarEvaluable) divide(() -> value).get();
	}

	default ProducerComputation<Scalar> divide(Supplier<Evaluable<? extends Scalar>> value) {
		return multiply(pow(value, ScalarFeatures.of(-1.0)));
	}

	default ScalarEvaluable divide(Scalar value) {
		return divide(ScalarFeatures.of(value).get());
	}

	default ScalarEvaluable divide(double value) {
		return divide(new Scalar(value));
	}

	default ScalarEvaluable minus() { return multiply(-1.0); }

	default ScalarEvaluable pow(Evaluable<Scalar> exponent) { return pow(this, exponent); }

	default ScalarEvaluable pow(Scalar exp) { return pow(this, exp); }

	default ScalarEvaluable pow(double exp) { return pow(this, exp); }

	default AcceleratedConditionalStatementVector greaterThan(Evaluable<Scalar> operand) {
		return greaterThan(operand, null, null);
	}

	default AcceleratedConditionalStatementVector greaterThan(Evaluable<Scalar> operand,
															  Evaluable<Vector> trueValue,
															  Evaluable<Vector> falseValue) {
		return new GreaterThanVector(() -> this, () -> operand, () -> trueValue, () -> falseValue);
	}

	default AcceleratedConditionalStatementVector lessThan(Evaluable<Scalar> operand) {
		return lessThan(operand, null, null);
	}

	default AcceleratedConditionalStatementVector lessThan(Evaluable<Scalar> operand,
														   Evaluable<Vector> trueValue,
														   Evaluable<Vector> falseValue) {
		return lessThan(() -> operand, () -> trueValue, () -> falseValue);
	}

	default AcceleratedConditionalStatementVector lessThan(Supplier<Evaluable<Scalar>> operand,
														   Supplier<Evaluable<Vector>> trueValue,
														   Supplier<Evaluable<Vector>> falseValue) {
		return new LessThanVector(() -> this, operand, trueValue, falseValue);
	}
}
