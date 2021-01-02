package org.almostrealism.algebra;

import io.almostrealism.code.ProducerComputation;
import io.almostrealism.relation.Evaluable;

import org.almostrealism.algebra.computations.DefaultScalarEvaluable;
import org.almostrealism.algebra.computations.ScalarPow;
import org.almostrealism.algebra.computations.ScalarProduct;
import org.almostrealism.bool.AcceleratedConditionalStatementScalar;
import org.almostrealism.bool.AcceleratedConditionalStatementVector;
import org.almostrealism.bool.GreaterThanScalar;
import org.almostrealism.bool.GreaterThanVector;
import org.almostrealism.bool.LessThanScalar;
import org.almostrealism.bool.LessThanVector;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.KernelizedProducer;

import java.util.function.Supplier;

public interface ScalarProducer extends ProducerComputation<Scalar>, KernelizedProducer<Scalar>, ScalarFeatures {
	@Override
	default KernelizedEvaluable<Scalar> get() { return new DefaultScalarEvaluable(this); }

	default ScalarProducer add(Supplier<Evaluable<? extends Scalar>> value) {
		return scalarAdd(this, value);
	}

	default ScalarProducer add(Scalar value) {
		return add(ScalarFeatures.of(value));
	}

	default ScalarProducer add(double value) {
		return add(new Scalar(value));
	}

	default ScalarProducer subtract(double value) {
		return subtract(new Scalar(value));
	}

	default ScalarProducer subtract(Scalar value) {
		return subtract(ScalarFeatures.of(value));
	}

	default ScalarProducer subtract(Evaluable<Scalar> value) {
		return subtract(() -> value);
	}

	default ScalarProducer subtract(Supplier<Evaluable<? extends Scalar>> value) {
		return add(scalarMinus(value));
	}

	default ScalarProducer multiply(Evaluable<Scalar> value) {
		return multiply(() -> value);
	}

	default ScalarProducer multiply(Supplier<Evaluable<? extends Scalar>> value) {
		return new ScalarProduct(this, value);
	}

	default ScalarProducer multiply(Scalar value) {
		return multiply(ScalarFeatures.of(value));
	}

	default ScalarProducer multiply(double value) {
		return multiply(new Scalar(value));
	}

	default ScalarProducer divide(Evaluable<Scalar> value) {
		return divide(() -> value);
	}

	default ScalarProducer divide(Supplier<Evaluable<? extends Scalar>> value) {
		return multiply(new ScalarPow(value, ScalarFeatures.of(-1.0)));
	}

	default ScalarProducer divide(Scalar value) {
		return divide(ScalarFeatures.of(value));
	}

	default ScalarProducer divide(double value) {
		return divide(new Scalar(value));
	}

	default ScalarProducer minus() { return multiply(-1.0); }

	default ScalarProducer pow(Supplier<Evaluable<? extends Scalar>> exponent) { return pow(this, exponent); }

	default ScalarProducer pow(Scalar exp) { return pow(this, exp); }

	default ScalarProducer pow(double exp) { return pow(this, exp); }

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
															  Supplier<Evaluable<? extends Vector>> trueValue,
															  Supplier<Evaluable<? extends Vector>> falseValue) {
		return greaterThan(operand, trueValue, falseValue, false);
	}

	default AcceleratedConditionalStatementScalar greaterThan(Supplier<Evaluable<? extends Scalar>> operand,
															  Supplier<Evaluable<? extends Vector>> trueValue,
															  Supplier<Evaluable<? extends Vector>> falseValue,
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
