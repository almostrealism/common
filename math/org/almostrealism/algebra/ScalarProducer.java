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

import org.almostrealism.algebra.computations.ScalarPow;
import org.almostrealism.algebra.computations.ScalarProduct;
import org.almostrealism.algebra.computations.ScalarSum;
import org.almostrealism.math.bool.AcceleratedConditionalStatementVector;
import org.almostrealism.math.bool.GreaterThanVector;
import org.almostrealism.math.bool.LessThanVector;
import org.almostrealism.util.Producer;
import org.almostrealism.util.StaticProducer;

public interface ScalarProducer extends Producer<Scalar> {
	ScalarProducer minusOne = StaticProducer.of(-1.0);

	default ScalarSum add(Producer<Scalar> value) {
		return new ScalarSum(this, value);
	}

	default ScalarSum add(Scalar value) {
		return add(StaticProducer.of(value));
	}

	default ScalarSum add(double value) {
		return add(new Scalar(value));
	}

	default ScalarSum subtract(Producer<Scalar> value) {
		return add(minus(value));
	}

	default ScalarProducer multiply(Producer<Scalar> value) {
		return new ScalarProduct(this, value);
	}

	default ScalarProducer multiply(Scalar value) {
		return multiply(StaticProducer.of(value));
	}

	default ScalarProducer multiply(double value) {
		return multiply(new Scalar(value));
	}

	default ScalarProducer divide(Producer<Scalar> value) {
		return multiply(new ScalarPow(value, StaticProducer.of(-1.0)));
	}

	default ScalarProducer divide(Scalar value) {
		return divide(StaticProducer.of(value));
	}

	default ScalarProducer divide(double value) {
		return divide(new Scalar(value));
	}

	default ScalarProducer minus() { return multiply(-1.0); }

	static ScalarProducer minus(Producer<Scalar> p) {
		return new ScalarProduct(p, minusOne);
	}

	default ScalarProducer pow(Producer<Scalar> exponent) {
		return new ScalarPow(this, exponent);
	}

	default ScalarProducer pow(Scalar value) {
		return pow(StaticProducer.of(value));
	}

	default ScalarProducer pow(double value) {
		return pow(new Scalar(value));
	}

	default AcceleratedConditionalStatementVector greaterThan(Producer<Scalar> operand) {
		return greaterThan(operand, null, null);
	}

	default AcceleratedConditionalStatementVector greaterThan(Producer<Scalar> operand,
															  Producer<Vector> trueValue,
															  Producer<Vector> falseValue) {
		return new GreaterThanVector(this, operand, trueValue, falseValue);
	}

	default AcceleratedConditionalStatementVector lessThan(Producer<Scalar> operand) {
		return lessThan(operand, null, null);
	}

	default AcceleratedConditionalStatementVector lessThan(Producer<Scalar> operand,
															  Producer<Vector> trueValue,
															  Producer<Vector> falseValue) {
		return new LessThanVector(this, operand, trueValue, falseValue);
	}
}