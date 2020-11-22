package org.almostrealism.algebra;

import org.almostrealism.algebra.computations.DefaultScalarEvaluable;
import org.almostrealism.algebra.computations.ScalarPow;
import org.almostrealism.algebra.computations.ScalarProduct;
import org.almostrealism.math.bool.AcceleratedConditionalStatementVector;
import org.almostrealism.math.bool.GreaterThanVector;
import org.almostrealism.math.bool.LessThanVector;
import org.almostrealism.relation.ProducerComputation;
import org.almostrealism.util.Evaluable;
import org.almostrealism.util.StaticEvaluable;

import java.util.function.Supplier;

public interface ScalarSupplier extends ProducerComputation<Scalar>, ScalarFeatures {
	@Override
	default Evaluable<Scalar> get() { return new DefaultScalarEvaluable(this); }

	default ScalarSupplier add(Supplier<Evaluable<? extends Scalar>> value) {
		return scalarAdd(this, value);
	}

	default ScalarSupplier add(Scalar value) {
		return add(() -> StaticEvaluable.of(value));
	}

	default ScalarSupplier add(double value) {
		return add(new Scalar(value));
	}

	default ScalarSupplier subtract(Evaluable<Scalar> value) {
		return subtract(() -> value);
	}

	default ScalarSupplier subtract(Supplier<Evaluable<? extends Scalar>> value) {
		return add(scalarMinus(value));
	}

	default ScalarSupplier multiply(Evaluable<Scalar> value) {
		return multiply(() -> value);
	}

	default ScalarSupplier multiply(Supplier<Evaluable<? extends Scalar>> value) {
		return new ScalarProduct(this, value);
	}

	default ScalarSupplier multiply(Scalar value) {
		return multiply(StaticEvaluable.of(value));
	}

	default ScalarSupplier multiply(double value) {
		return multiply(new Scalar(value));
	}

	default ScalarSupplier divide(Evaluable<Scalar> value) {
		return divide(() -> value);
	}

	default ScalarSupplier divide(Supplier<Evaluable<? extends Scalar>> value) {
		return multiply(new ScalarPow(value, () -> StaticEvaluable.of(-1.0)));
	}

	default ScalarSupplier divide(Scalar value) {
		return divide(StaticEvaluable.of(value));
	}

	default ScalarSupplier divide(double value) {
		return divide(new Scalar(value));
	}

	default ScalarSupplier minus() { return multiply(-1.0); }

	default ScalarSupplier pow(Supplier<Evaluable<? extends Scalar>> exponent) { return pow(this, exponent); }

	default ScalarSupplier pow(Scalar exp) { return pow(this, exp); }

	default ScalarSupplier pow(double exp) { return pow(this, exp); }

	default AcceleratedConditionalStatementVector greaterThan(Supplier<Evaluable<Scalar>> operand) {
		return greaterThan(operand, null, null);
	}

	default AcceleratedConditionalStatementVector greaterThan(Supplier<Evaluable<Scalar>> operand,
															  Supplier<Evaluable<Vector>> trueValue,
															  Supplier<Evaluable<Vector>> falseValue) {
		return new GreaterThanVector(this, operand, trueValue, falseValue);
	}

	default AcceleratedConditionalStatementVector lessThan(Supplier operand) {
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
		return new LessThanVector(this, operand, trueValue, falseValue);
	}
}
