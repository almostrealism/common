/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.compute.Process;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A deprecated computation class that evaluates mathematical expressions on {@link PackedCollection} data.
 * This class provides a bridge between expression-based mathematical operations and collection computations,
 * allowing complex mathematical expressions to be evaluated efficiently on multi-dimensional data.
 * 
 * <p><strong>Note:</strong> This class is deprecated in favor of newer computation mechanisms.
 * Consider using {@link org.almostrealism.collect.computations.TraversableExpressionComputation} 
 * or {@link org.almostrealism.collect.computations.DefaultTraversableExpressionComputation} for new implementations.</p>
 * 
 * <h2>Purpose and Usage</h2>
 * <p>ExpressionComputation takes a list of mathematical expression functions and evaluates them against
 * input {@link PackedCollection} arguments. Each expression function receives a list of {@link ArrayVariable}
 * instances representing the input data and returns an {@link Expression} that defines the computation to perform.</p>
 * 
 * <h2>Basic Usage Example</h2>
 * <pre>{@code
 * // Create an expression that sums two input arrays
 * Function<List<ArrayVariable<Double>>, Expression<Double>> sumExpression = args ->
 *     Sum.of(args.get(1).getValueRelative(0), args.get(2).getValueRelative(0));
 * 
 * // Create the computation with the expression and input producers
 * ExpressionComputation<?> computation = new ExpressionComputation<>(
 *     List.of(sumExpression),
 *     new PassThroughProducer(1, 0),  // First input
 *     new PassThroughProducer(1, 1)   // Second input
 * );
 * 
 * // Evaluate with actual data
 * PackedCollection<?> a = new PackedCollection<>(1);
 * PackedCollection<?> b = new PackedCollection<>(1);
 * a.setMem(0, 3.0);
 * b.setMem(0, 5.0);
 * 
 * PackedCollection<?> result = computation.get().evaluate(a, b);
 * // result contains [8.0] (3.0 + 5.0)
 * }</pre>
 * 
 * <h2>Multi-Element Collections</h2>
 * <p>ExpressionComputation can operate on multi-dimensional collections and supports various traversal patterns:</p>
 * <pre>{@code
 * // Working with larger collections
 * PackedCollection<?> input1 = new PackedCollection<>(4);
 * PackedCollection<?> input2 = new PackedCollection<>(4);
 * input1.setMem(0, 1.0, 2.0, 3.0, 4.0);
 * input2.setMem(0, 5.0, 6.0, 7.0, 8.0);
 * 
 * // The computation will be applied element-wise
 * PackedCollection<?> result = computation.get().evaluate(input1, input2);
 * // result contains [6.0, 8.0, 10.0, 12.0]
 * }</pre>
 * 
 * <h2>Shape Inference</h2>
 * <p>When {@link #enableInferShape} is set to true, the computation can automatically infer
 * the output shape based on the input arguments. This is useful when working with collections
 * of different dimensions.</p>
 * 
 * <h2>Fixed Value Creation</h2>
 * <p>The class provides utility methods for creating computations with fixed values:</p>
 * <pre>{@code
 * // Create a computation that always returns specific values
 * CollectionProducer<PackedCollection<?>> constant = ExpressionComputation.fixed(1.0, 2.0, 3.0);
 * PackedCollection<?> result = constant.get().evaluate();
 * // result contains [1.0, 2.0, 3.0]
 * }</pre>
 * 
 * @param <T> The type of {@link PackedCollection} this computation operates on
 * 
 * @author Michael Murray
 * @see RelativeTraversableProducerComputation
 * @see PackedCollection
 * @see ArrayVariable
 * @see Expression
 * @see TraversableExpressionComputation
 * @deprecated This class is deprecated in favor of newer computation mechanisms.
 *             Use {@link TraversableExpressionComputation} or {@link DefaultTraversableExpressionComputation} instead.
 */
@Deprecated
public class ExpressionComputation<T extends PackedCollection<?>>
		extends RelativeTraversableProducerComputation<T, T> {

	/**
	 * Configuration flag to enable automatic shape inference from input arguments.
	 * When enabled, the computation will attempt to infer the output shape by analyzing
	 * the shapes of input collections. If multiple input collections have different
	 * shapes with counts greater than 1, an exception will be thrown.
	 * 
	 * <p>Default value is {@code false}.</p>
	 * 
	 * @see #shape(int, Supplier...)
	 */
	public static boolean enableInferShape = false;
	
	/**
	 * Configuration flag to enable warning messages during computation construction.
	 * When enabled, warnings will be logged for potentially problematic usage patterns,
	 * such as using modifiable ArrayList instances as expression arguments.
	 * 
	 * <p>Default value is {@code false}.</p>
	 */
	public static boolean enableWarnings = false;

	/**
	 * The list of expression functions that define the mathematical computations to perform.
	 * Each function takes a list of {@link ArrayVariable} instances (representing input data)
	 * and returns an {@link Expression} that defines the computation for that position
	 * in the output collection.
	 */
	private List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expression;

	/**
	 * Creates an ExpressionComputation with automatic shape inference.
	 * The output shape is determined automatically based on the number of expressions
	 * and the shapes of the input arguments (if {@link #enableInferShape} is enabled).
	 * 
	 * @param expression A list of functions, each taking input {@link ArrayVariable}s and
	 *                   returning an {@link Expression} that defines the computation for
	 *                   the corresponding position in the output collection
	 * @param args       Variable arguments representing the input data suppliers.
	 *                   Each supplier provides an {@link Evaluable} that produces
	 *                   a {@link PackedCollection} containing the input data
	 * 
	 * @throws IllegalArgumentException if the inferred shape size doesn't match the number of expressions
	 * 
	 * @see #ExpressionComputation(TraversalPolicy, List, Supplier[])
	 * @see #enableInferShape
	 */
	@SafeVarargs
	public ExpressionComputation(List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expression,
								 Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		this(shape(expression.size(), args), expression, args);
	}

	/**
	 * Creates an ExpressionComputation with an explicitly specified output shape.
	 * This is the main constructor that initializes the computation with all necessary parameters.
	 * 
	 * <p>The number of expressions must exactly match the total size of the specified shape.
	 * Each expression function will be called with the input {@link ArrayVariable}s and should
	 * return an {@link Expression} that computes the value for the corresponding position
	 * in the output collection.</p>
	 * 
	 * <p><strong>Example:</strong></p>
	 * <pre>{@code
	 * // Create a 2-element computation that multiplies corresponding elements
	 * List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expressions = List.of(
	 *     args -> Product.of(args.get(1).getValueRelative(0), args.get(2).getValueRelative(0)),
	 *     args -> Product.of(args.get(1).getValueRelative(1), args.get(2).getValueRelative(1))
	 * );
	 * 
	 * ExpressionComputation<?> computation = new ExpressionComputation<>(
	 *     new TraversalPolicy(2), // Output shape: 2 elements
	 *     expressions,
	 *     inputProducer1,
	 *     inputProducer2
	 * );
	 * }</pre>
	 * 
	 * @param shape      The {@link TraversalPolicy} defining the shape and traversal pattern
	 *                   of the output collection
	 * @param expression A list of functions, each taking input {@link ArrayVariable}s and
	 *                   returning an {@link Expression} for the corresponding output position
	 * @param args       Variable arguments representing the input data suppliers
	 * 
	 * @throws IllegalArgumentException if the shape size doesn't match the number of expressions
	 * 
	 * @see TraversalPolicy
	 * @see ArrayVariable
	 * @see Expression
	 */
	@SafeVarargs
	public ExpressionComputation(TraversalPolicy shape, List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expression,
							   Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(shape, validateArgs(args));
		if (shape.getSize() != expression.size())
			throw new IllegalArgumentException("Expected " + shape.getSize() + " expressions");
		this.expression = expression;

		if (enableWarnings && expression instanceof ArrayList) {
			warn("Modifiable list used as argument to ExpressionComputation constructor");
		}
	}

	/**
	 * Returns the list of expression functions that define this computation.
	 * Each function in the list corresponds to one position in the output collection
	 * and defines how to compute the value at that position based on the input data.
	 * 
	 * @return An unmodifiable view of the expression functions list
	 */
	public List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expression() {
		return expression;
	}

	/**
	 * Adjusts the destination memory bank to accommodate the specified length.
	 * This method ensures that the destination collection has the appropriate shape
	 * and size to hold the computation results.
	 * 
	 * <p>If the existing memory bank is insufficient or incompatible, a new
	 * {@link PackedCollection} will be created with the correct dimensions.
	 * The existing memory bank will be destroyed to free resources.</p>
	 * 
	 * @param existing The existing memory bank, may be null
	 * @param len      The required length for the destination, must not be null
	 * @return A {@link MemoryBank} suitable for storing the computation results
	 * 
	 * @throws IllegalArgumentException if len is null
	 */
	@Override
	protected MemoryBank<?> adjustDestination(MemoryBank<?> existing, Integer len) {
		if (len == null) {
			throw new IllegalArgumentException();
		}

		TraversalPolicy shape = shapeForLength(len);

		if (!(existing instanceof PackedCollection) || existing.getMem() == null ||
				((PackedCollection) existing).getShape().getTotalSize() < shape.getTotalSize()) {
			if (existing != null) existing.destroy();
			return PackedCollection.factory().apply(shape.getTotalSize()).reshape(shape);
		}

		return ((PackedCollection) existing).range(shape);
	}

	/**
	 * Returns a function that provides the {@link Expression} for a given position
	 * in the output collection. This is the core method that bridges the gap between
	 * positional indexing and the expression-based computation model.
	 * 
	 * <p>The returned function takes an integer position and returns the corresponding
	 * {@link Expression} that computes the value for that position. The expression
	 * is obtained by calling the appropriate expression function with the current
	 * input arguments.</p>
	 * 
	 * @return A function that maps output positions to their corresponding expressions
	 * 
	 * @throws IllegalArgumentException if the requested position is out of bounds
	 *                                  (>= expression list size)
	 */
	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (pos >= expression.size()) {
				throw new IllegalArgumentException();
			} else {
				return expression.get(pos).apply(getInputArguments());
			}
		};
	}

	/**
	 * Computes the value at a specific index using the provided input arguments.
	 * This method directly evaluates one of the expression functions with the given
	 * input variables.
	 * 
	 * @param args  The list of input {@link ArrayVariable}s containing the source data
	 * @param index The index in the expression list to evaluate (0-based)
	 * @return The {@link Expression} result for the specified index
	 * 
	 * @throws IndexOutOfBoundsException if the index is out of range
	 */
	public Expression<Double> getValue(List<ArrayVariable<Double>> args, int index) {
		return expression.get(index).apply(args);
	}

	/**
	 * Generates a parallel process version of this computation with the specified child processes.
	 * This method creates a new ExpressionComputation that can be executed as part of a
	 * parallel computation pipeline.
	 * 
	 * @param children List of child processes to be integrated into the parallel execution
	 * @return A {@link CollectionProducerParallelProcess} that can execute this computation in parallel
	 */
	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return new ExpressionComputation<>(getShape(), expression,
				children.stream().skip(1).toArray(Supplier[]::new));
	}

	/**
	 * Returns null, as it is not possible to compute a signature for this
	 * computation due to the {@link Expression}s being dynamically specified
	 * on construction.
	 *
	 * @return  null
	 */
	@Override
	public String signature() { return null; }

	/**
	 * Creates a {@link CollectionProducer} that always returns the specified constant values.
	 * This is a convenience method for creating computations with fixed, predetermined results.
	 * 
	 * <p><strong>Example:</strong></p>
	 * <pre>{@code
	 * CollectionProducer<PackedCollection<?>> constants = ExpressionComputation.fixed(1.0, 2.0, 3.0);
	 * PackedCollection<?> result = constants.get().evaluate();
	 * // result contains [1.0, 2.0, 3.0]
	 * }</pre>
	 * 
	 * @param values The constant values to be returned by the computation
	 * @return A {@link CollectionProducer} that produces a collection containing the specified values
	 */
	public static CollectionProducer<PackedCollection<?>> fixed(double... values) {
		PackedCollection<?> c = PackedCollection.factory().apply(values.length);
		c.setMem(0, values);
		return fixed(c);
	}

	/**
	 * Creates a {@link CollectionProducer} that always returns the specified collection value.
	 * This method creates a computation that produces a copy of the provided collection
	 * without any postprocessing.
	 * 
	 * @param value The {@link PackedCollection} to be returned by the computation
	 * @param <T>   The type of the collection
	 * @return A {@link CollectionProducer} that produces the specified collection
	 */
	public static <T extends PackedCollection<?>> CollectionProducer<T> fixed(T value) {
		return fixed(value, null);
	}

	/**
	 * Creates a {@link CollectionProducer} that returns the specified collection value
	 * with optional postprocessing. This is the most flexible factory method for creating
	 * constant-value computations.
	 * 
	 * <p>The method automatically handles different traversal patterns and creates
	 * appropriate expression functions that return the constant values from the provided
	 * collection. If a postprocessor is provided, it will be applied to the result.</p>
	 * 
	 * <p><strong>Example with postprocessing:</strong></p>
	 * <pre>{@code
	 * PackedCollection<?> originalData = new PackedCollection<>(shape(2, 2));
	 * originalData.setMem(0, 1.0, 2.0, 3.0, 4.0);
	 * 
	 * // Create a computation that doubles all values
	 * BiFunction<MemoryData, Integer, PackedCollection<?>> doubler = 
	 *     (data, index) -> {
	 *         PackedCollection<?> result = new PackedCollection<>(data.getShape());
	 *         for (int i = 0; i < data.getMemLength(); i++) {
	 *             result.setMem(i, data.toArray(i, 1)[0] * 2.0);
	 *         }
	 *         return result;
	 *     };
	 * 
	 * CollectionProducer<PackedCollection<?>> computation = 
	 *     ExpressionComputation.fixed(originalData, doubler);
	 * }</pre>
	 * 
	 * @param value         The {@link PackedCollection} containing the base values
	 * @param postprocessor Optional function to transform the result data, may be null
	 * @param <T>           The type of the collection
	 * @return A {@link CollectionProducer} that produces the specified collection,
	 *         potentially with postprocessing applied
	 */
	public static <T extends PackedCollection<?>> CollectionProducer<T> fixed(T value, BiFunction<MemoryData, Integer, T> postprocessor) {
		int traversalAxis = value.getShape().getTraversalAxis();

		Function<List<ArrayVariable<Double>>, Expression<Double>> comp[] =
			IntStream.range(0, value.getShape().getTotalSize())
					.mapToObj(i ->
						(Function<List<ArrayVariable<Double>>, Expression<Double>>) args -> value.getValueAt(new IntegerConstant(i)))
					.toArray(Function[]::new);

		if (traversalAxis == 0) {
			return (ExpressionComputation<T>) new ExpressionComputation(value.getShape(), List.of(comp)).setPostprocessor(postprocessor).setShortCircuit(args -> {
				PackedCollection v = new PackedCollection(value.getShape());
				v.setMem(value.toArray(0, value.getMemLength()));
				return postprocessor == null ? v : postprocessor.apply(v, 0);
			});
		} else {
			return new ExpressionComputation(value.getShape().traverse(0), List.of(comp)).setPostprocessor(postprocessor).setShortCircuit(args -> {
				PackedCollection v = new PackedCollection(value.getShape());
				v.setMem(value.toArray(0, value.getMemLength()));
				return postprocessor == null ? v : postprocessor.apply(v, 0);
			}).traverse(traversalAxis);
		}
	}

	/**
	 * Determines the appropriate {@link TraversalPolicy} shape for the computation output
	 * based on the expression count and input argument shapes.
	 * 
	 * <p>If {@link #enableInferShape} is false, returns a simple 1D shape with the specified size.
	 * If shape inference is enabled, analyzes the input arguments to determine if they have
	 * consistent multi-dimensional shapes and prepends that dimension to the base shape.</p>
	 * 
	 * <p>Shape inference logic:</p>
	 * <ul>
	 *   <li>If no input arguments have count > 1: returns basic shape of specified size</li>
	 *   <li>If all arguments with count > 1 have the same count: prepends that dimension</li>
	 *   <li>If arguments have different counts > 1: throws IllegalArgumentException</li>
	 * </ul>
	 * 
	 * @param size The base size (number of expressions/output elements)
	 * @param args The input argument suppliers used for shape inference
	 * @return A {@link TraversalPolicy} representing the computed output shape
	 * 
	 * @throws IllegalArgumentException if shape inference fails due to incompatible input shapes
	 */
	private static TraversalPolicy shape(int size, Supplier... args) {
		TraversalPolicy shape = new TraversalPolicy(size);
		if (!enableInferShape) return shape;

		Set<Long> count = Stream.of(args)
				.map(CollectionFeatures.getInstance()::shape)
				.map(TraversalPolicy::getCountLong)
				.filter(i -> i > 1)
				.collect(Collectors.toSet());
		if (count.isEmpty()) {
			return shape;
		} else if (count.size() == 1) {
			return shape.prependDimension(Math.toIntExact(count.iterator().next()));
		} else {
			throw new IllegalArgumentException("Unable to infer shape from arguments");
		}
	}
}
