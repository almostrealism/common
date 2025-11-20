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

package org.almostrealism.hardware;

import io.almostrealism.code.OperationAdapter;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;
import io.almostrealism.streams.StreamingEvaluable;
import io.almostrealism.uml.Named;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.hardware.mem.AcceleratedProcessDetails;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.jocl.CLException;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * An {@link Evaluable} wrapper that writes results directly into a pre-allocated {@link MemoryBank} destination.
 *
 * <p>{@link DestinationEvaluable} wraps another evaluable, intercepting its output and writing it to a
 * specific {@link MemoryBank} instead of allocating new memory. This pattern is essential for:
 * <ul>
 *   <li><strong>Zero-allocation batch processing:</strong> Reuse output banks across iterations</li>
 *   <li><strong>Pipeline efficiency:</strong> Write directly to next stage's input</li>
 *   <li><strong>Memory management:</strong> Control exactly where results are stored</li>
 * </ul>
 *
 * <h2>Core Concept: Destination-Driven Evaluation</h2>
 *
 * <p>Normally, evaluables allocate their own output memory:</p>
 * <pre>{@code
 * Evaluable<PackedCollection<?>> op = multiply(a, b).get();
 * PackedCollection<?> result = op.evaluate(dataA, dataB);  // Allocates new memory
 * }</pre>
 *
 * <p>With {@link DestinationEvaluable}, the destination is provided upfront:</p>
 * <pre>{@code
 * MemoryBank<PackedCollection<?>> output = PackedCollection.bank(100, 1000);
 * Evaluable<PackedCollection<?>> destOp = new DestinationEvaluable<>(op, output);
 *
 * destOp.evaluate(batchA, batchB);  // Writes to output bank, no allocation
 * // output now contains results
 * }</pre>
 *
 * <h2>Execution Strategies</h2>
 *
 * <p>The evaluable uses different execution strategies based on the wrapped operation:</p>
 *
 * <h3>1. Provider-Based (Preferred)</h3>
 * <pre>{@code
 * // If operation implements Provider:
 * operation.into(destination).evaluate(args);  // Uses into() for efficiency
 * }</pre>
 *
 * <h3>2. Kernel-Based (Hardware Accelerated)</h3>
 * <pre>{@code
 * // If operation is an accelerated kernel:
 * AcceleratedProcessDetails details = operation.apply(destination, args);
 * details.getSemaphore().waitFor();  // Single kernel invocation writes to bank
 * }</pre>
 *
 * <h3>3. Element-wise Iteration (Fallback)</h3>
 * <pre>{@code
 * // For non-accelerated operations:
 * for (int i = 0; i < destination.getCount(); i++) {
 *     Object[] elementArgs = extractElement(args, i);
 *     T result = operation.evaluate(elementArgs);
 *     destination.set(i, result);
 * }
 * }</pre>
 *
 * <h2>Common Usage Patterns</h2>
 *
 * <h3>Batch Processing Without Allocation</h3>
 * <pre>{@code
 * Evaluable<PackedCollection<?>> normalize = normalizeOp.get();
 * MemoryBank<PackedCollection<?>> output = PackedCollection.bank(1000, 256);
 *
 * // Reuse output bank for all batches
 * for (int batch = 0; batch < 100; batch++) {
 *     MemoryBank<PackedCollection<?>> input = loadBatch(batch);
 *     new DestinationEvaluable<>(normalize, output).evaluate(input);
 *     processBatch(output);
 *     // No allocation occurred
 * }
 * }</pre>
 *
 * <h3>Pipeline with Intermediate Banks</h3>
 * <pre>{@code
 * MemoryBank<Vector> stage1Out = Vector.bank(1000);
 * MemoryBank<Vector> stage2Out = Vector.bank(1000);
 *
 * // Stage 1: input -> stage1Out
 * new DestinationEvaluable<>(stage1Op, stage1Out).evaluate(inputBank);
 *
 * // Stage 2: stage1Out -> stage2Out (reuses stage1Out as input)
 * new DestinationEvaluable<>(stage2Op, stage2Out).evaluate(stage1Out);
 *
 * // Final result in stage2Out, no intermediate allocations
 * }</pre>
 *
 * <h3>Async Streaming Evaluation</h3>
 * <pre>{@code
 * Executor executor = Executors.newFixedThreadPool(4);
 * MemoryBank<PackedCollection<?>> output = PackedCollection.bank(1000, 256);
 *
 * StreamingEvaluable<MemoryBank<PackedCollection<?>>> stream =
 *     new DestinationEvaluable<>(op, output).async(executor);
 *
 * stream.setDownstream(result -> {
 *     // Called when async operation completes
 *     processResult(result);
 * });
 *
 * stream.request(new Object[] { inputBatch });  // Non-blocking
 * }</pre>
 *
 * <h2>Argument Batching</h2>
 *
 * <p>When using element-wise iteration, arguments must be {@link MemoryBank}s:</p>
 * <pre>
 * Destination Bank:   [e0][e1][e2]...[eN]
 * Argument Bank 0:    [a0][a1][a2]...[aN]
 * Argument Bank 1:    [b0][b1][b2]...[bN]
 *
 * Evaluation i:  result[i] = operation.evaluate(args[0].get(i), args[1].get(i))
 * </pre>
 *
 * <h2>Performance Considerations</h2>
 *
 * <ul>
 *   <li><strong>Preferred:</strong> Wrap {@link Provider} or {@link AcceleratedOperation} for efficiency</li>
 *   <li><strong>Element-wise fallback:</strong> Has loop overhead but avoids allocations</li>
 *   <li><strong>Memory reuse:</strong> Destination bank can be reused across calls</li>
 *   <li><strong>Async support:</strong> Enables non-blocking batch processing pipelines</li>
 * </ul>
 *
 * <h2>Integration with {@code into()} Method</h2>
 *
 * <p>Many evaluables provide an {@code into(destination)} method that returns a
 * {@link DestinationEvaluable}:</p>
 * <pre>{@code
 * Evaluable<PackedCollection<?>> op = multiply(a, b).get();
 * MemoryBank<PackedCollection<?>> output = PackedCollection.bank(100, 1000);
 *
 * // These are equivalent:
 * op.into(output).evaluate(batchA, batchB);
 * new DestinationEvaluable<>(op, output).evaluate(batchA, batchB);
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Not thread-safe. Each {@link DestinationEvaluable} instance should be used by a single
 * thread or protected with external synchronization. The destination {@link MemoryBank} is
 * modified in-place during evaluation.</p>
 *
 * @param <T> The type of {@link MemoryBank} serving as the destination
 *
 * @see MemoryBank
 * @see Evaluable
 * @see StreamingEvaluable
 * @see Provider
 */
public class DestinationEvaluable<T extends MemoryBank> implements
		Evaluable<T>, StreamingEvaluable<T>, Runnable, Destroyable, ConsoleFeatures {
	private Evaluable<T> operation;
	private MemoryBank destination;

	private Executor executor;
	private Consumer<T> downstream;

	public DestinationEvaluable(Evaluable<T> operation, MemoryBank destination) {
		this(operation, destination, null, null);
	}

	public DestinationEvaluable(Evaluable<T> operation, MemoryBank destination,
								Executor executor) {
		this(operation, destination, executor, null);

		if (operation instanceof HardwareEvaluable) {
			// DestinationEvaluable is intended to be used only as an alternative
			// to HardwareEvaluable, when it is not possible to use it
			throw new UnsupportedOperationException();
		} else if (!(operation instanceof AcceleratedOperation<?>) && !(operation instanceof Provider)) {
			warn("Creating DestinationEvaluable for " + operation.getClass().getSimpleName() +
					" will not leverage hardware acceleration");
		}
	}

	private DestinationEvaluable(Evaluable<T> operation, MemoryBank destination,
								Executor executor, Consumer<T> downstream) {
		this.operation = operation;
		this.destination = destination;
		this.executor = executor;
		this.downstream = downstream;
	}

	@Override
	public void run() { evaluate(); }

	@Override
	public T evaluate(Object... args) {
		if (operation instanceof Provider<T>) {
			operation.into(destination).evaluate(args);
		} else if (operation instanceof AcceleratedOperation && ((AcceleratedOperation) operation).isKernel()) {
			AcceleratedProcessDetails details = ((AcceleratedOperation) operation)
					.apply(destination, Stream.of(args).map(arg -> (MemoryData) arg).toArray(MemoryData[]::new));
			details.getSemaphore().waitFor();
		} else {
			String name = operation instanceof Named ? ((Named) operation).getName() : OperationAdapter.operationName(null, getClass(), "function");
			if (HardwareOperator.enableVerboseLog) log("Evaluating " + name + " kernel...");

			boolean enableLog = false;

			for (int i = 0; i < destination.getCountLong(); i++) {
				T r = null;

				try {
					final int fi = i;
					Object o[] = Stream.of(args)
							.map(arg -> ((MemoryBank) arg).get(fi)).toArray();

					r = operation.evaluate(o);
					if (r == null) r = replaceNull(o);

					destination.set(i, r);
				} catch (UnsupportedOperationException e) {
					throw new HardwareException("i = " + i + " of " + destination.getCountLong() + ", r = " + r, e);
				} catch (CLException e) {
					throw new HardwareException("i = " + i + " of " + destination.getCountLong() + ", r = " + r, e);
				}

				if (enableLog && (i + 1) % 100 == 0) log((i + 1) + " kernel results collected");
			}
		}

		return (T) destination;
	}

	@Override
	public void request(Object[] args) {
		if (operation instanceof AcceleratedOperation && ((AcceleratedOperation) operation).isKernel()) {
			AcceleratedProcessDetails details = ((AcceleratedOperation) operation)
					.apply(destination, Stream.of(args).map(arg -> (MemoryData) arg).toArray(MemoryData[]::new));
			details.getSemaphore().onComplete(() -> downstream.accept((T) destination));
		} else {
			throw new UnsupportedOperationException();
		}
	}

	@Override
	public void setDownstream(Consumer<T> consumer) {
		if (downstream != null) {
			throw new UnsupportedOperationException();
		}

		this.downstream = consumer;
	}

	@Override
	public StreamingEvaluable<T> async(Executor executor) {
		return new DestinationEvaluable<>(operation, destination, executor);
	}

	public T replaceNull(Object[] o) {
		if (operation instanceof NullProcessor) {
			return (T) ((NullProcessor) operation).replaceNull(o);
		} else {
			throw new NullPointerException();
		}
	}

	@Override
	public void destroy() {
		Destroyable.destroy(operation);
	}

	@Override
	public Console console() { return Hardware.console; }
}
