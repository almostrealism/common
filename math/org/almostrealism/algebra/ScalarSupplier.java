package org.almostrealism.algebra;

import org.almostrealism.algebra.computations.DefaultScalarProducer;
import org.almostrealism.algebra.computations.ScalarPow;
import org.almostrealism.algebra.computations.ScalarProduct;
import org.almostrealism.math.bool.AcceleratedConditionalStatementVector;
import org.almostrealism.math.bool.GreaterThanVector;
import org.almostrealism.math.bool.LessThanVector;
import org.almostrealism.relation.ProducerComputation;
import org.almostrealism.util.Producer;
import org.almostrealism.util.StaticProducer;

import java.util.function.Supplier;

public interface ScalarSupplier extends ProducerComputation<Scalar>, ScalarFeatures {
	@Override
	default Producer<Scalar> get() { return new DefaultScalarProducer(this); }

	default ScalarSupplier add(Supplier<Producer<? extends Scalar>> value) {
		return scalarAdd(this, value);
	}

	default ScalarSupplier add(Scalar value) {
		return add(() -> StaticProducer.of(value));
	}

	default ScalarSupplier add(double value) {
		return add(new Scalar(value));
	}

	default ScalarSupplier subtract(Producer<Scalar> value) {
		return subtract(() -> value);
	}

	default ScalarSupplier subtract(Supplier<Producer<? extends Scalar>> value) {
		return add(scalarMinus(value));
	}

	default ScalarSupplier multiply(Producer<Scalar> value) {
		return multiply(() -> value);
	}

	default ScalarSupplier multiply(Supplier<Producer<? extends Scalar>> value) {
		return new ScalarProduct(this, value);
	}

	default ScalarSupplier multiply(Scalar value) {
		return multiply(StaticProducer.of(value));
	}

	default ScalarSupplier multiply(double value) {
		return multiply(new Scalar(value));
	}

	default ScalarSupplier divide(Producer<Scalar> value) {
		return divide(() -> value);
	}

	default ScalarSupplier divide(Supplier<Producer<? extends Scalar>> value) {
		return multiply(new ScalarPow(value, () -> StaticProducer.of(-1.0)));
	}

	default ScalarSupplier divide(Scalar value) {
		return divide(StaticProducer.of(value));
	}

	default ScalarSupplier divide(double value) {
		return divide(new Scalar(value));
	}

	default ScalarSupplier minus() { return multiply(-1.0); }

	default ScalarSupplier pow(Supplier<Producer<? extends Scalar>> exponent) { return pow(this, exponent); }

	default ScalarSupplier pow(Scalar exp) { return pow(this, exp); }

	default ScalarSupplier pow(double exp) { return pow(this, exp); }

	default AcceleratedConditionalStatementVector greaterThan(Supplier<Producer<Scalar>> operand) {
		return greaterThan(operand, null, null);
	}

	default AcceleratedConditionalStatementVector greaterThan(Supplier<Producer<Scalar>> operand,
															  Supplier<Producer<Vector>> trueValue,
															  Supplier<Producer<Vector>> falseValue) {
		return new GreaterThanVector(this, operand, trueValue, falseValue);
	}

	default AcceleratedConditionalStatementVector lessThan(Supplier operand) {
		return lessThan(operand, null, null);
	}

	default AcceleratedConditionalStatementVector lessThan(Producer<Scalar> operand,
														   Producer<Vector> trueValue,
														   Producer<Vector> falseValue) {
		return lessThan(() -> operand, () -> trueValue, () -> falseValue);
	}

	default AcceleratedConditionalStatementVector lessThan(Supplier<Producer<Scalar>> operand,
														   Supplier<Producer<Vector>> trueValue,
														   Supplier<Producer<Vector>> falseValue) {
		return new LessThanVector(this, operand, trueValue, falseValue);
	}
}
