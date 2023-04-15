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

package org.almostrealism.algebra;

import io.almostrealism.code.ProducerComputation;
import io.almostrealism.relation.Evaluable;

import org.almostrealism.algebra.computations.ScalarExpressionComputation;
import org.almostrealism.bool.AcceleratedConditionalStatementScalar;
import org.almostrealism.bool.AcceleratedConditionalStatementVector;
import org.almostrealism.bool.GreaterThanScalar;
import org.almostrealism.bool.GreaterThanVector;
import org.almostrealism.bool.LessThanScalar;
import org.almostrealism.bool.LessThanVector;
import org.almostrealism.hardware.KernelizedProducer;

import java.util.function.Supplier;

public interface ScalarProducerBase extends ProducerComputation<Scalar>, KernelizedProducer<Scalar>, ScalarFeatures {
	default ScalarProducerBase add(ScalarProducerBase value) {
		return scalarAdd(this, value);
	}

	default ScalarProducerBase add(Scalar value) {
		return add(ScalarFeatures.of(value));
	}

	default ScalarProducerBase add(double value) {
		return add(new Scalar(value));
	}

	default ScalarProducerBase subtract(double value) {
		return subtract(new Scalar(value));
	}

	default ScalarProducerBase subtract(Scalar value) {
		return subtract(ScalarFeatures.of(value));
	}

	default ScalarProducerBase subtract(ScalarProducerBase value) {
		return add(scalarMinus(value));
	}

	@Deprecated
	default ScalarExpressionComputation multiply(Evaluable<Scalar> value) {
		throw new UnsupportedOperationException();
	}

	default ScalarExpressionComputation multiply(ScalarProducerBase value) {
		return scalarsMultiply(this, value);
	}

	default ScalarExpressionComputation multiply(Scalar value) {
		return multiply(ScalarFeatures.of(value));
	}

	default ScalarExpressionComputation multiply(double value) {
		return multiply(new Scalar(value));
	}

	default ScalarExpressionComputation divide(ScalarProducerBase value) {
		return multiply(pow(value, ScalarFeatures.of(-1.0)));
	}

	default ScalarExpressionComputation divide(Scalar value) {
		return divide(ScalarFeatures.of(value));
	}

	default ScalarExpressionComputation divide(double value) {
		return divide(new Scalar(value));
	}

	default ScalarExpressionComputation minus() { return multiply(-1.0); }

	default ScalarProducerBase pow(ScalarProducerBase exponent) { return pow(this, exponent); }

	default ScalarProducerBase pow(Scalar exp) { return pow(this, exp); }

	default ScalarProducerBase pow(double exp) { return pow(this, exp); }

	default ScalarProducerBase mod(Supplier<Evaluable<? extends Scalar>> divisor) {
		return mod(this, divisor);
	}

	default AcceleratedConditionalStatementScalar greaterThan(double operand) {
		return greaterThan(operand, false);
	}

	default AcceleratedConditionalStatementScalar greaterThan(double operand, boolean includeEqual) {
		return greaterThan(new Scalar(operand), includeEqual);
	}

	default AcceleratedConditionalStatementScalar greaterThan(Scalar operand) {
		return greaterThan(operand, false);
	}

	default AcceleratedConditionalStatementScalar greaterThan(Scalar operand, boolean includeEqual) {
		return greaterThan(ScalarFeatures.of(operand), includeEqual);
	}

	default AcceleratedConditionalStatementScalar greaterThan(Evaluable<Scalar> operand) {
		return greaterThan(operand, false);
	}

	default AcceleratedConditionalStatementScalar greaterThan(Evaluable<Scalar> operand, boolean includeEqual) {
		return greaterThan(() -> operand, includeEqual);
	}

	default AcceleratedConditionalStatementScalar greaterThan(Supplier<Evaluable<? extends Scalar>> operand) {
		return greaterThan(operand, false);
	}

	default AcceleratedConditionalStatementScalar greaterThan(Supplier<Evaluable<? extends Scalar>> operand, boolean includeEqual) {
		return greaterThan(operand, null, null, includeEqual);
	}

	default AcceleratedConditionalStatementScalar greaterThan(Supplier<Evaluable<? extends Scalar>> operand,
															  Supplier<Evaluable<? extends Scalar>> trueValue,
															  Supplier<Evaluable<? extends Scalar>> falseValue) {
		return greaterThan(operand, trueValue, falseValue, false);
	}

	default AcceleratedConditionalStatementScalar greaterThan(Supplier<Evaluable<? extends Scalar>> operand,
															  Supplier<Evaluable<? extends Scalar>> trueValue,
															  Supplier<Evaluable<? extends Scalar>> falseValue,
															  boolean includeEqual) {
		return new GreaterThanScalar(this, operand, trueValue, falseValue, includeEqual);
	}

	default AcceleratedConditionalStatementVector greaterThanv(Supplier<Evaluable<Scalar>> operand) {
		return greaterThanv(operand, null, null);
	}

	default AcceleratedConditionalStatementVector greaterThanv(Supplier<Evaluable<Scalar>> operand,
															   Supplier<Evaluable<? extends Vector>> trueValue,
															   Supplier<Evaluable<? extends Vector>> falseValue) {
		return new GreaterThanVector(this, operand, trueValue, falseValue);
	}

	default AcceleratedConditionalStatementScalar lessThan(double operand, boolean includeEqual) {
		return lessThan(new Scalar(operand), includeEqual);
	}

	default AcceleratedConditionalStatementScalar lessThan(Scalar operand, boolean includeEqual) {
		return lessThan(ScalarFeatures.of(operand), includeEqual);
	}

	default AcceleratedConditionalStatementScalar lessThan(Evaluable<? extends Scalar> operand, boolean includeEqual) {
		return lessThan(() -> operand, includeEqual);
	}

	default AcceleratedConditionalStatementScalar lessThan(Supplier operand) {
		return lessThan(operand, false);
	}

	default AcceleratedConditionalStatementScalar lessThan(Supplier operand, boolean includeEqual) {
		return lessThan(operand, null, null, includeEqual);
	}

	default AcceleratedConditionalStatementScalar lessThan(Evaluable<Scalar> operand,
														   Evaluable<Scalar> trueValue,
														   Evaluable<Scalar> falseValue,
														   boolean includeEqual) {
		return lessThan(() -> operand, () -> trueValue, () -> falseValue, includeEqual);
	}

	default AcceleratedConditionalStatementScalar lessThan(Supplier<Evaluable<? extends Scalar>> operand,
														   Supplier<Evaluable<? extends Scalar>> trueValue,
														   Supplier<Evaluable<? extends Scalar>> falseValue) {
		return lessThan(operand, trueValue, falseValue, false);
	}

	default AcceleratedConditionalStatementScalar lessThan(Supplier<Evaluable<? extends Scalar>> operand,
														   Supplier<Evaluable<? extends Scalar>> trueValue,
														   Supplier<Evaluable<? extends Scalar>> falseValue,
														   boolean includeEqual) {
		return new LessThanScalar(this, operand, trueValue, falseValue, includeEqual);
	}

	default AcceleratedConditionalStatementVector lessThanv(Supplier operand) {
		return lessThanv(operand, null, null);
	}

	default AcceleratedConditionalStatementVector lessThanv(Evaluable<Scalar> operand,
															Evaluable<Vector> trueValue,
															Evaluable<Vector> falseValue) {
		return lessThanv(() -> operand, () -> trueValue, () -> falseValue);
	}

	default AcceleratedConditionalStatementVector lessThanv(Supplier<Evaluable<Scalar>> operand,
															Supplier<Evaluable<Vector>> trueValue,
															Supplier<Evaluable<Vector>> falseValue) {
		return new LessThanVector(this, operand, trueValue, falseValue);
	}
}
