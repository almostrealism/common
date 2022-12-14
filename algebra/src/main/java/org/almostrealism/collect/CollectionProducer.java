package org.almostrealism.collect;

import io.almostrealism.code.ProducerComputation;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.algebra.Vector;
import org.almostrealism.bool.AcceleratedConditionalStatementCollection;
import org.almostrealism.bool.AcceleratedConditionalStatementScalar;
import org.almostrealism.bool.AcceleratedConditionalStatementVector;
import org.almostrealism.bool.GreaterThanCollection;
import org.almostrealism.bool.GreaterThanScalar;
import org.almostrealism.bool.GreaterThanVector;
import org.almostrealism.bool.LessThanCollection;
import org.almostrealism.bool.LessThanScalar;
import org.almostrealism.collect.computations.DefaultCollectionEvaluable;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.Input;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.KernelizedProducer;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.MemoryDataAdapter;

import java.util.function.Function;
import java.util.function.Supplier;

public interface CollectionProducer<T extends PackedCollection<?>> extends ProducerComputation<T>, KernelizedProducer<T>, CollectionFeatures {
	// This should be 0, but Scalar is actually a Pair so a set of scalars is 2D not 1D
	int SCALAR_AXIS = 1;

	TraversalPolicy getShape();

	default T postProcessOutput(MemoryData output, int offset) {
		return (T) new PackedCollection(getShape(), 0, output, offset);
	}

	@Override
	default KernelizedEvaluable<T> get() {
		AcceleratedComputationEvaluable<T> ev = new DefaultCollectionEvaluable<T>(getShape(), this, this::postProcessOutput);
		ev.compile();
		return ev;
	}

	default CollectionProducer<PackedCollection<?>> scalarMap(Function<Producer<Scalar>, Producer<Scalar>> f) {
		Producer<Scalar> p = f.apply(Input.value(Scalar.class, 0));

		return new CollectionProducer<>() {
			@Override
			public TraversalPolicy getShape() {
				throw new UnsupportedOperationException();
			}

			@Override
			public Scope<PackedCollection<?>> getScope() {
				throw new UnsupportedOperationException();
			}

			@Override
			public KernelizedEvaluable<PackedCollection<?>> get() {
				return new KernelizedEvaluable<>() {
					@Override
					public MemoryBank<PackedCollection<?>> createKernelDestination(int size) {
						throw new UnsupportedOperationException();
					}

					@Override
					public PackedCollection<?> evaluate(Object... args) {
						PackedCollection c = get().evaluate();
						KernelizedEvaluable<Scalar> ev = (KernelizedEvaluable<Scalar>) p.get();
						MemoryBank<Scalar> bank = ev.createKernelDestination(c.getShape().length(SCALAR_AXIS));
						ev.kernelEvaluate(bank, new MemoryBank[] { c.traverse(SCALAR_AXIS) });
						return new PackedCollection(c.getShape(), c.getShape().getDimensions(), bank, 0);
					}
				};
			}
		};
	}

	default <T extends MemoryDataAdapter> T collect(Function<TraversalPolicy, T> factory) {
		PackedCollection c = get().evaluate();
		T data = factory.apply(c.getShape());
		data.setDelegate(c, 0);
		return data;
	}

	// TODO  Rename
	default <T extends PackedCollection<?>> ExpressionComputation<T> _add(Supplier<Evaluable<? extends PackedCollection<?>>> value) {
		return CollectionFeatures.super._add((Producer) this, value);
	}

	// TODO  Rename
	default <T extends PackedCollection<?>> ExpressionComputation<T> _subtract(Supplier<Evaluable<? extends PackedCollection<?>>> value) {
		return CollectionFeatures.super._subtract((Producer) this, value);
	}

	// TODO  Rename
	default <T extends PackedCollection<?>> ExpressionComputation<T> _multiply(Supplier<Evaluable<? extends PackedCollection<?>>> value) {
		return CollectionFeatures.super._multiply((Producer) this, value);
	}

	// TODO  Rename
	default <T extends PackedCollection<?>> ExpressionComputation<T> _divide(Supplier<Evaluable<? extends PackedCollection<?>>> value) {
		return CollectionFeatures.super._divide((Producer) this, value);
	}

	// TODO  Rename
	default <T extends PackedCollection<?>> ExpressionComputation<T> _pow(Supplier<Evaluable<? extends PackedCollection<?>>> value) {
		return CollectionFeatures.super._pow((Producer) this, value);
	}

	default AcceleratedConditionalStatementCollection _greaterThan(Supplier<Evaluable<? extends PackedCollection<?>>> operand) {
		return _greaterThan(operand, false);
	}

	default AcceleratedConditionalStatementCollection _greaterThan(Supplier<Evaluable<? extends PackedCollection<?>>> operand, boolean includeEqual) {
		return _greaterThan(operand, null, null, includeEqual);
	}

	default AcceleratedConditionalStatementCollection _greaterThan(Supplier<Evaluable<? extends PackedCollection<?>>> operand,
															  Supplier<Evaluable<? extends PackedCollection<?>>> trueValue,
															  Supplier<Evaluable<? extends PackedCollection<?>>> falseValue) {
		return _greaterThan(operand, trueValue, falseValue, false);
	}

	default AcceleratedConditionalStatementCollection _greaterThan(Supplier<Evaluable<? extends PackedCollection<?>>> operand,
																  Supplier<Evaluable<? extends PackedCollection<?>>> trueValue,
																  Supplier<Evaluable<? extends PackedCollection<?>>> falseValue,
																  boolean includeEqual) {
		return new GreaterThanCollection(this, operand, trueValue, falseValue, includeEqual);
	}

	default AcceleratedConditionalStatementCollection _lessThan(Supplier operand) {
		return _lessThan(operand, false);
	}

	default AcceleratedConditionalStatementCollection _lessThan(Supplier operand, boolean includeEqual) {
		return _lessThan(operand, null, null, includeEqual);
	}

	default AcceleratedConditionalStatementCollection _lessThan(Supplier<Evaluable<? extends PackedCollection<?>>> operand,
														   Supplier<Evaluable<? extends PackedCollection<?>>> trueValue,
														   Supplier<Evaluable<? extends PackedCollection<?>>> falseValue) {
		return _lessThan(operand, trueValue, falseValue, false);
	}

	default AcceleratedConditionalStatementCollection _lessThan(Supplier<Evaluable<? extends PackedCollection<?>>> operand,
														   Supplier<Evaluable<? extends PackedCollection<?>>> trueValue,
														   Supplier<Evaluable<? extends PackedCollection<?>>> falseValue,
														   boolean includeEqual) {
		return new LessThanCollection(this, operand, trueValue, falseValue, includeEqual);
	}
}
