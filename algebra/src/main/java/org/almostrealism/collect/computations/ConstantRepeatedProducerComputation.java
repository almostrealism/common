/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.collect.computations;

import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * A specialized {@link RepeatedProducerComputation} that performs a fixed number of iterations.
 * This class simplifies the creation of repeated computations where the number of iterations
 * is known at construction time, automatically generating the appropriate condition function.
 * 
 * <p>This class is particularly useful for algorithms that require a predetermined number
 * of iteration steps, such as:</p>
 * <ul>
 *   <li>Fixed-step numerical integration methods</li>
 *   <li>Matrix power computations with known exponents</li>
 *   <li>Multi-step filtering or smoothing operations</li>
 *   <li>Unrolled optimization algorithms with fixed step counts</li>
 * </ul>
 * 
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * // Create a computation that applies a transformation 10 times
 * ConstantRepeatedProducerComputation<PackedCollection<?>> fixedIterations = 
 *     new ConstantRepeatedProducerComputation<>("fixedLoop", shape, 10,
 *         // Initial: copy input values
 *         (args, index) -> args[0].getValueRelative(index),
 *         // Expression: apply transformation each iteration
 *         (args, index) -> transform(args[0].getValueRelative(index)),
 *         inputProducer);
 * }</pre>
 * 
 * @param <T> The type of {@link PackedCollection} this computation produces
 * 
 * @see RepeatedProducerComputation
 */
public class ConstantRepeatedProducerComputation<T extends PackedCollection<?>>
		extends RepeatedProducerComputation<T> {
	/**
	 * The fixed number of iterations this computation will perform.
	 * This value is used to automatically generate the condition function
	 * and provide optimization hints via {@link #getIndexLimit()}.
	 */
	protected int count;

	/**
	 * Constructs a {@code ConstantRepeatedProducerComputation} with default memory length.
	 * This constructor creates a repeated computation that will execute exactly {@code count}
	 * iterations, automatically generating the appropriate termination condition.
	 * 
	 * @param name       Human-readable name for this computation
	 * @param shape      The {@link TraversalPolicy} defining output shape and data access patterns  
	 * @param count      The fixed number of iterations to perform, must be non-negative
	 * @param initial    Function to compute initial values for the iteration
	 * @param expression Function defining the computation for each iteration step
	 * @param args       Variable number of input suppliers providing data to the computation
	 * 
	 * @throws IllegalArgumentException if count is negative
	 * 
	 * @see #ConstantRepeatedProducerComputation(String, TraversalPolicy, int, int, BiFunction, BiFunction, Supplier[])
	 */
	@SafeVarargs
	public ConstantRepeatedProducerComputation(String name, TraversalPolicy shape, int count,
											   BiFunction<TraversableExpression[], Expression, Expression> initial,
											   BiFunction<TraversableExpression[], Expression, Expression> expression,
											   Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		this(name, shape, 1, count, initial, expression, args);
	}

	/**
	 * Constructs a {@code ConstantRepeatedProducerComputation} with configurable memory length.
	 * This constructor provides full control over both the iteration count and memory allocation,
	 * enabling optimization for performance-critical applications.
	 * 
	 * @param name       Human-readable name for this computation
	 * @param shape      The {@link TraversalPolicy} defining output shape and data access patterns
	 * @param size       Memory length for buffer allocation  
	 * @param count      The fixed number of iterations to perform, must be non-negative
	 * @param initial    Function to compute initial values for the iteration
	 * @param expression Function defining the computation for each iteration step
	 * @param inputs     Variable number of input suppliers providing data to the computation
	 * 
	 * @throws IllegalArgumentException if count is negative or size is non-positive
	 */
	@SafeVarargs
	public ConstantRepeatedProducerComputation(String name, TraversalPolicy shape, int size, int count,
											   BiFunction<TraversableExpression[], Expression, Expression> initial,
											   BiFunction<TraversableExpression[], Expression, Expression> expression,
											   Supplier<Evaluable<? extends PackedCollection<?>>>... inputs) {
		super(name, shape, size, initial, (args, index) -> index.lessThan(new IntegerConstant(count)), expression, inputs);
		this.count = count;
	}

	/**
	 * Returns the fixed iteration limit for optimization purposes.
	 * This override provides the exact number of iterations that will be performed,
	 * enabling the computation engine to optimize memory allocation and kernel generation.
	 * 
	 * @return {@link OptionalInt} containing the exact iteration count specified during construction
	 * 
	 * @see #count
	 */
	@Override
	protected OptionalInt getIndexLimit() { return OptionalInt.of(count); }

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		CollectionProducer<T> delta = attemptDelta(target);
		if (delta != null) return delta;

		return ConstantRepeatedDeltaComputation.create(
				getShape(), shape(target),
				count, (args, localIndex) -> getExpression(args, null, localIndex), target,
				getInputs().stream().skip(1).toArray(Supplier[]::new));
	}

	@Override
	public ConstantRepeatedProducerComputation<T> generate(List<Process<?, ?>> children) {
		return new ConstantRepeatedProducerComputation<>(
				getName(), getShape(), getMemLength(), count,
				initial, expression,
				children.stream().skip(1).toArray(Supplier[]::new));
	}
}
