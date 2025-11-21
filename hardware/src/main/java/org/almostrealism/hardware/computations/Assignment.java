/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware.computations;

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Countable;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.uml.Signature;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.DestinationEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.OperationComputationAdapter;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;

import java.util.List;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * {@link OperationComputationAdapter} that assigns computed values to a destination memory location.
 *
 * <p>{@link Assignment} generates code of the form {@code output[i] = value[i]} for each element.
 * It supports:</p>
 * <ul>
 *   <li><strong>Vectorized assignment:</strong> Multiple values per kernel invocation</li>
 *   <li><strong>Adaptive memory length:</strong> Adjusts to kernel parallelism</li>
 *   <li><strong>Short-circuit optimization:</strong> Direct evaluation when possible</li>
 *   <li><strong>Shape preservation:</strong> Maintains traversal policy from input</li>
 * </ul>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * // Create assignment: output = input * 2
 * Producer<PackedCollection> output = output();
 * Producer<PackedCollection> value = multiply(input(), c(2.0));
 *
 * Assignment<PackedCollection> assign =
 *     new Assignment<>(1, output, value);  // memLength = 1
 *
 * // Compile and execute
 * Runnable operation = assign.get();
 * operation.run();  // Writes to output
 * }</pre>
 *
 * <h2>Memory Length (Vectorization)</h2>
 *
 * <p>The {@code memLength} parameter controls how many values each kernel thread processes:</p>
 *
 * <pre>{@code
 * // memLength = 1: Each thread processes 1 value
 * Assignment<T> scalar = new Assignment<>(1, output, value);
 * // Generated code: output[global_id] = value[global_id];
 *
 * // memLength = 4: Each thread processes 4 values
 * Assignment<T> vectorized = new Assignment<>(4, output, value);
 * // Generated code:
 * // output[global_id * 4 + 0] = value[global_id * 4 + 0];
 * // output[global_id * 4 + 1] = value[global_id * 4 + 1];
 * // output[global_id * 4 + 2] = value[global_id * 4 + 2];
 * // output[global_id * 4 + 3] = value[global_id * 4 + 3];
 * }</pre>
 *
 * <h2>Adaptive Memory Length</h2>
 *
 * <p>When {@code enableAdaptiveMemLength = true} (default), the memory length automatically
 * adjusts to match kernel parallelism:</p>
 *
 * <pre>{@code
 * // Total work: 1024 elements
 * // Kernel maximum: 256 threads
 * // memLength = 1 (specified)
 * // Adaptive memLength = 1024 / 256 = 4
 *
 * Assignment<T> assign = new Assignment<>(1, output, value);
 * // With 256 threads, automatically processes 4 elements per thread
 * }</pre>
 *
 * <h2>Short-Circuit Optimization</h2>
 *
 * <p>When the value is already an {@link AcceleratedOperation} or {@link io.almostrealism.relation.Provider},
 * assignment can bypass scope generation and use {@link DestinationEvaluable} directly:</p>
 *
 * <pre>{@code
 * // Value is an accelerated operation
 * Producer<T> value = acceleratedMultiply(a, b);
 *
 * Assignment<T> assign = new Assignment<>(1, output, value);
 *
 * // Short-circuit: Uses DestinationEvaluable instead of compiling new scope
 * Runnable operation = assign.get();  // Returns DestinationEvaluable
 * }</pre>
 *
 * <p><strong>Note:</strong> Short-circuit is disabled for aggregation targets when
 * {@code enableAggregatedShortCircuit = false} to avoid double aggregation.</p>
 *
 * <h2>Generated Scope Structure</h2>
 *
 * <pre>{@code
 * // For memLength = 2:
 * void assign(double *arg0, double *arg1) {
 *     int idx = global_id * 2;
 *
 *     // Assignment 0
 *     arg0[idx + 0] = arg1[idx + 0];
 *
 *     // Assignment 1
 *     arg0[idx + 1] = arg1[idx + 1];
 * }
 * }</pre>
 *
 * <h2>Traversable Expressions</h2>
 *
 * <p>Supports {@link TraversableExpression} for complex memory layouts:</p>
 *
 * <pre>{@code
 * // Multi-dimensional output
 * TraversableExpression out = TraversableExpression.traverse(output);
 *
 * // Generated:
 * // out.getValueAt(global_id * memLength + i) = value[i];
 * }</pre>
 *
 * <h2>Shape Metadata</h2>
 *
 * <p>Preserves shape from input producer:</p>
 *
 * <pre>{@code
 * Producer<PackedCollection> shaped = shaped(3, 4);  // 3x4 shape
 * Assignment<T> assign = new Assignment<>(1, output, shaped);
 *
 * // Metadata contains shape (3, 4)
 * TraversalPolicy shape = assign.getMetadata().getShape();
 * }</pre>
 *
 * <h2>Signature Generation</h2>
 *
 * <pre>{@code
 * String signature = assign.signature();
 * // Example: "assign4->Multiply_f64_3_2"
 * //   - memLength = 4
 * //   - Value signature: Multiply_f64_3_2
 * }</pre>
 *
 * <h2>Configuration Options</h2>
 *
 * <ul>
 *   <li><strong>enableAdaptiveMemLength:</strong> Auto-adjust memLength to kernel parallelism (default: true)</li>
 *   <li><strong>enableAggregatedShortCircuit:</strong> Allow short-circuit for aggregation targets (default: false)</li>
 * </ul>
 *
 * @param <T> The {@link MemoryData} type being assigned
 * @see OperationComputationAdapter
 * @see DestinationEvaluable
 * @see TraversableExpression
 */
public class Assignment<T extends MemoryData> extends OperationComputationAdapter<T> {
	/** Controls automatic adjustment of memory length to match kernel parallelism. */
	public static boolean enableAdaptiveMemLength = true;
	/** Controls whether short-circuit optimization is allowed for aggregation targets. */
	public static boolean enableAggregatedShortCircuit = false;

	/** Number of values each kernel thread processes. */
	private final int memLength;

	/**
	 * Creates a new assignment operation.
	 *
	 * @param memLength the number of values each kernel thread processes
	 * @param result    the destination producer where values will be written
	 * @param value     the source producer providing values to assign
	 * @throws IllegalArgumentException if memLength exceeds {@link ScopeSettings#maxStatements}
	 */
	public Assignment(int memLength, Producer<T> result, Producer<T> value) {
		super(result, value);
		this.memLength = memLength;
		init();

		if (memLength > ScopeSettings.maxStatements) {
			throw new IllegalArgumentException();
		}
	}

	/**
	 * Prepares metadata for this assignment, including shape information from the destination.
	 *
	 * @param metadata the base metadata to augment
	 * @return metadata with shape information if the destination provides it
	 */
	@Override
	protected OperationMetadata prepareMetadata(OperationMetadata metadata) {
		metadata = super.prepareMetadata(metadata);

		if (getInputs().get(0) instanceof Shape<?>) {
			metadata = metadata.withShape(((Shape<?>) getInputs().get(0)).getShape());
		}

		return metadata;
	}

	/** {@inheritDoc} */
	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
	}

	/**
	 * Prepares the scope by setting up inputs and purging unused variables.
	 *
	 * @param manager the scope input manager
	 * @param context the kernel structure context
	 */
	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);

		purgeVariables();
	}

	/**
	 * Returns the count from the destination producer if it is countable, otherwise 1.
	 *
	 * @return the number of elements to process
	 */
	@Override
	public long getCountLong() {
		return getInputs().get(0) instanceof Countable ? ((Countable) getInputs().get(0)).getCountLong() : 1;
	}

	/**
	 * Generates the assignment scope with value-to-destination assignment statements.
	 *
	 * <p>Creates assignment statements for each element based on the memory length,
	 * adapting to kernel context when {@code enableAdaptiveMemLength} is true.</p>
	 *
	 * @param context the kernel structure context for index generation
	 * @return a scope containing the assignment statements
	 * @throws UnsupportedOperationException if count mismatch cannot be resolved
	 */
	@Override
	public Scope<Void> getScope(KernelStructureContext context) {
		Scope<Void> scope = super.getScope(context);

		int len = memLength;
		OptionalLong contextCount = context.getKernelMaximum();

		if (contextCount.isPresent() && contextCount.getAsLong() != getCountLong()) {
			if (enableAdaptiveMemLength && getCountLong() % contextCount.getAsLong() == 0) {
				len = Math.toIntExact(getCountLong() / contextCount.getAsLong());
			} else {
				throw new UnsupportedOperationException();
			}
		}

		ArrayVariable<Double> output = (ArrayVariable<Double>) getArgument(0);

		for (int i = 0; i < len; i++) {
			Expression index = new KernelIndex(context);
			if (len > 1) index = index.multiply(len).add(i);

			TraversableExpression exp = TraversableExpression.traverse(getArgument(1));
			Expression<Double> value = exp == null ? getArgument(1).reference(index) : exp.getValueAt(index);
			if (value == null) {
				throw new UnsupportedOperationException();
			}

			ExpressionAssignment<?> v;
			TraversableExpression out = TraversableExpression.traverse(output);

			if (out == null) {
				v = output.reference(index).assign(value);
			} else {
				Expression o = out.getValueAt(index);
				v = o.assign(value);
			}

			scope.getStatements().add(v);
		}

		return scope;
	}

	/**
	 * Returns a runnable that performs the assignment operation.
	 *
	 * <p>Attempts short-circuit optimization using {@link DestinationEvaluable} when the
	 * value is an {@link AcceleratedOperation} or {@link Provider}. Falls back to
	 * standard scope compilation when shapes don't match or aggregation would cause issues.</p>
	 *
	 * @return a runnable that executes the assignment
	 */
	@Override
	public Runnable get() {
		Supplier<Evaluable<? extends T>> out = getInputs().get(0);
		Supplier<Evaluable<? extends T>> in = getInputs().get(1);

		if (out instanceof Shape && in instanceof Shape) {
			TraversalPolicy inShape = ((Shape<?>) in).getShape();
			TraversalPolicy outShape = ((Shape<?>) out).getShape();

			if (inShape.getTotalSizeLong() != outShape.getTotalSizeLong() ||
				inShape.getCountLong() != outShape.getCountLong()) {
				// There are some cases where it makes sense to just generate a Scope
				// here, because (for example) the alternative might be to provide an
				// Evaluable that repeats the same value many times over
				return super.get();
			}
		}

		Evaluable<?> ev = in.get();

		MemoryBank destination = (MemoryBank) out.get().evaluate();

		if (ev instanceof HardwareEvaluable<?>) {
			ev = ((HardwareEvaluable<?>) ev).getKernel().getValue();
		}

		boolean shortCircuit = ev instanceof AcceleratedOperation<?> || ev instanceof Provider<?>;

		if (!enableAggregatedShortCircuit &&
				MemoryDataArgumentMap.isAggregationTarget(destination)) {
			// Assignment operations that compute a value which itself
			// depends on the destination, have issues when the destination
			// is aggregated (when using DestinationEvaluable it will
			// be aggregated twice, leading to inconsistent evaluation)
			// TODO  It would be better to actually determine whether
			// TODO  the destination is referenced by the the assignment
			// TODO  value, but for now this is sufficient
			shortCircuit = false;
		}

		if (shortCircuit) {
			return new DestinationEvaluable(ev, destination);
		}

		// TODO  It would be preferable to always use DestinationEvaluable, but it
		// TODO  handles the evaluation of Producers which do not directly support
		// TODO  kernel evaluation differently than ProcessDetailsFactory (which is
		// TODO  sometimes not ideal - see DestinationEvaluable.evaluate)
		return super.get();
	}

	/**
	 * Optimizes the given process within this assignment's context.
	 *
	 * <p>Skips optimization for the destination process to preserve its structure.</p>
	 *
	 * @param ctx     the process context
	 * @param process the process to optimize
	 * @return the optimized process, or unchanged if it's the destination
	 */
	@Override
	public Process<Process<?, ?>, Runnable> optimize(ProcessContext ctx, Process<Process<?, ?>, Runnable> process) {
		if (process == (Supplier) getInputs().get(0))
			return process;

		return super.optimize(ctx, process);
	}

	/**
	 * Isolates the given process within this assignment's context.
	 *
	 * <p>Skips isolation for the destination process to preserve its structure.</p>
	 *
	 * @param process the process to isolate
	 * @return the isolated process, or unchanged if it's the destination
	 */
	@Override
	public Process<Process<?, ?>, Runnable> isolate(Process<Process<?, ?>, Runnable> process) {
		if (process == (Supplier) getInputs().get(0))
			return process;

		return super.isolate(process);
	}

	/**
	 * Generates a new assignment with the given child processes.
	 *
	 * @param children list containing exactly 2 processes: destination and value
	 * @return a new Assignment with the given children, or this instance if children size is not 2
	 */
	@Override
	public Assignment<T> generate(List<Process<?, ?>> children) {
		if (children.size() != 2) return this;

		Assignment result = new Assignment<>(memLength, (Producer) children.get(0), (Producer) children.get(1));

		if (getMetadata().getShortDescription() != null) {
			result.getMetadata().setShortDescription(getMetadata().getShortDescription());
		}

		return result;
	}

	/**
	 * Returns a unique signature for this assignment operation.
	 *
	 * <p>Format: "assign{memLength}->{valueSignature}"</p>
	 *
	 * @return the signature string, or null if destination or value lacks a signature
	 */
	@Override
	public String signature() {
		if (Signature.of(getInputs().get(0)) == null) {
			// If the destination does not provide a signature,
			// it is not possible to be certain about the signature
			// for the assignment operation
			return null;
		}

		String signature = Signature.of(getInputs().get(1));
		if (signature == null || memLength == 0) return null;

		return "assign" + memLength + "->" + signature;
	}

	/**
	 * Returns a human-readable description of this assignment.
	 *
	 * @return description in format "{shortDescription} ({count}x{memLength})"
	 */
	@Override
	public String describe() {
		return getMetadata().getShortDescription() + " (" + getCount() + "x" + memLength + ")";
	}
}
