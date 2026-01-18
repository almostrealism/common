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

package org.almostrealism.hardware.computations;

import io.almostrealism.compute.Process;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.uml.Signature;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.io.Describable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Wrapper {@link Producer} that delegates to another producer while enriching metadata.
 *
 * <p>{@link DelegatedProducer} creates a new {@link Process} node that wraps an existing
 * producer, useful for:</p>
 * <ul>
 *   <li><strong>Metadata enrichment:</strong> Add custom descriptions to operations</li>
 *   <li><strong>Direct delegation:</strong> Pass through original {@link Evaluable}</li>
 *   <li><strong>Indirect delegation:</strong> Hide implementation details</li>
 *   <li><strong>Process tree construction:</strong> Create hierarchical computation graphs</li>
 * </ul>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * // Wrap existing producer
 * Producer<Matrix> original = multiply(a, b);
 *
 * // Create delegated producer with enriched metadata
 * DelegatedProducer<Matrix> wrapper =
 *     new DelegatedProducer<>(original);  // Direct delegation
 *
 * // Metadata shows: "delegate(MatMul_f64_3_2)"
 * String description = wrapper.getMetadata().getShortDescription();
 * }</pre>
 *
 * <h2>Direct vs Indirect Delegation</h2>
 *
 * <p>The {@code directDelegate} flag controls how the {@link Evaluable} is exposed:</p>
 *
 * <pre>{@code
 * // Direct delegation (default): Returns original Evaluable
 * DelegatedProducer<T> direct = new DelegatedProducer<>(op, true);
 * Evaluable<T> eval1 = direct.get();  // Returns op.get() directly
 *
 * // Indirect delegation: Hides original Evaluable
 * DelegatedProducer<T> indirect = new DelegatedProducer<>(op, false);
 * Evaluable<T> eval2 = indirect.get();  // Returns lambda wrapper
 * }</pre>
 *
 * <p>Indirect delegation is useful when you want to hide information about the
 * original evaluable's structure from the caller.</p>
 *
 * <h2>Metadata Enrichment</h2>
 *
 * <p>Automatically wraps child metadata with "delegate(...)" prefix:</p>
 *
 * <pre>{@code
 * // Original operation
 * Producer<T> matmul = matmul(a, b);  // Metadata: "MatMul_f64_3_2"
 *
 * // Wrapped operation
 * DelegatedProducer<T> wrapper = new DelegatedProducer<>(matmul);
 * // Display name: "delegate(MatMul_f64_3_2)"
 * // Short description: "DelegatedProducer(MatMul_f64_3_2)"
 * }</pre>
 *
 * <h2>Custom Metadata Extension</h2>
 *
 * <p>Subclasses can override {@code extendDescription()} for custom formatting:</p>
 *
 * <pre>{@code
 * public class MyDelegatedProducer<T> extends DelegatedProducer<T> {
 *     @Override
 *     protected String extendDescription(String description, boolean brief) {
 *         if (brief) {
 *             return "custom(" + description + ")";
 *         } else {
 *             return "Custom wrapper for " + description;
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h2>Process Tree Integration</h2>
 *
 * <p>Integrates with {@link Process} hierarchy:</p>
 *
 * <pre>{@code
 * DelegatedProducer<T> wrapper = new DelegatedProducer<>(op);
 *
 * // If op is a Process, it becomes a child
 * Collection<Process<?, ?>> children = wrapper.getChildren();
 * // Returns: [op] if op instanceof Process, else []
 * }</pre>
 *
 * <h2>Countable Delegation</h2>
 *
 * <p>Count properties are forwarded to the wrapped producer:</p>
 *
 * <pre>{@code
 * long count = wrapper.getCountLong();  // Delegates to Countable.countLong(op)
 * boolean fixed = wrapper.isFixedCount();  // Delegates to Countable.isFixedCount(op)
 * }</pre>
 *
 * <h2>Signature Generation</h2>
 *
 * <p>Generates signature based on delegation properties:</p>
 *
 * <pre>{@code
 * String signature = wrapper.signature();
 * // Returns: "delegate|1024|true"
 * //   - Count: 1024
 * //   - Fixed count: true
 * }</pre>
 *
 * <p><strong>Note:</strong> Returns {@code null} for aggregation targets to avoid
 * signature conflicts (see {@link org.almostrealism.hardware.mem.MemoryDataArgumentMap}).</p>
 *
 * <h2>Limitations</h2>
 *
 * <ul>
 *   <li><strong>getOutputSize():</strong> Throws {@link UnsupportedOperationException}</li>
 *   <li><strong>generate():</strong> Throws {@link UnsupportedOperationException}</li>
 *   <li><strong>isolate():</strong> Returns {@code this} (no isolation)</li>
 * </ul>
 *
 * @param <T> The type of value produced
 * @see Producer
 * @see Process
 * @see OperationMetadata
 */
public class DelegatedProducer<T> implements
		Process<Process<?, ?>, Evaluable<? extends T>>,
		Producer<T>, Countable, Signature,
		OperationInfo {
	/** The wrapped producer being delegated to. */
	protected Producer<T> op;

	/** Whether to expose the original evaluable directly or wrap it. */
	protected boolean direct;

	/** Operation metadata for profiling and display. */
	protected OperationMetadata metadata;

	/**
	 * Creates a delegated producer with direct delegation enabled.
	 *
	 * @param op the producer to wrap
	 */
	public DelegatedProducer(Producer<T> op) {
		this(op, true);
	}

	/**
	 * Creates a delegated producer with the specified delegation mode.
	 *
	 * @param op             the producer to wrap
	 * @param directDelegate true to return the original evaluable directly,
	 *                       false to wrap it in a lambda
	 */
	public DelegatedProducer(Producer<T> op, boolean directDelegate) {
		this.op = op;
		this.direct = directDelegate;
		prepareMetadata();
	}

	/**
	 * Extends the description with delegation prefix.
	 *
	 * @param description the original description to extend
	 * @param brief       true for brief format (e.g., "delegate(...)"),
	 *                    false for full format (e.g., "ClassName(...)")
	 * @return the extended description string
	 */
	protected String extendDescription(String description, boolean brief) {
		if (brief) {
			return "delegate(" + description + ")";
		} else {
			return getClass().getSimpleName() + "(" + description + ")";
		}
	}

	/**
	 * Initializes the operation metadata based on the wrapped producer.
	 *
	 * <p>If the wrapped producer implements {@link OperationInfo}, creates
	 * metadata with extended descriptions. Otherwise, creates generic
	 * delegation metadata.</p>
	 */
	protected void prepareMetadata() {
		if (op instanceof OperationInfo) {
			OperationMetadata child = ((OperationInfo) op).getMetadata();
			this.metadata = new OperationMetadata(
									extendDescription(child.getDisplayName(), true),
									extendDescription(child.getShortDescription(), false));
			this.metadata.setChildren(List.of(child));
		} else {
			this.metadata = new OperationMetadata("delegate",
									getClass().getSimpleName());
		}
	}

	/** Returns the operation metadata for this delegated producer. */
	@Override
	public OperationMetadata getMetadata() { return metadata; }

	/**
	 * Returns the child processes of this delegated producer.
	 *
	 * @return a list containing the wrapped producer if it is a Process, otherwise an empty list
	 */
	@Override
	public Collection<Process<?, ?>> getChildren() {
		return op instanceof Process ? List.of((Process<?, ?>) op) : Collections.emptyList();
	}

	/**
	 * Returns the evaluable from the wrapped producer.
	 *
	 * <p>In direct mode, returns the original evaluable directly. In indirect mode,
	 * wraps the original evaluable's evaluate method in a lambda to hide the
	 * underlying implementation details.</p>
	 *
	 * @return the evaluable for this producer
	 */
	@Override
	public Evaluable<T> get() {
		if (direct) {
			// Return the original Evaluable
			return op.get();
		} else {
			// Hide any information about the original Evaluable
			Evaluable<T> original = op.get();
			return original::evaluate;
		}
	}

	/**
	 * Returns the count of elements produced by the wrapped producer.
	 *
	 * @return the element count delegated from the wrapped producer
	 */
	@Override
	public long getCountLong() {
		return Countable.countLong(op);
	}

	/**
	 * Returns whether the wrapped producer has a fixed element count.
	 *
	 * @return true if the count is fixed, false otherwise
	 */
	@Override
	public boolean isFixedCount() {
		return Countable.isFixedCount(op);
	}

	/**
	 * Not supported for delegated producers.
	 *
	 * @return never returns normally
	 * @throws UnsupportedOperationException always
	 */
	@Override
	public long getOutputSize() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Not supported for delegated producers.
	 *
	 * @param children the child processes
	 * @return never returns normally
	 * @throws UnsupportedOperationException always
	 */
	@Override
	public Process<Process<?, ?>, Evaluable<? extends T>> generate(List<Process<?, ?>> children) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns this producer unchanged since delegation is already isolated.
	 *
	 * @return this delegated producer
	 */
	@Override
	public Process<Process<?, ?>, Evaluable<? extends T>> isolate() {
		return this;
	}

	/**
	 * Destroys the wrapped producer if it implements {@link Destroyable}.
	 */
	@Override
	public void destroy() { Destroyable.destroy(op); }

	/**
	 * Returns a signature string for this delegated producer.
	 *
	 * <p>Returns null for aggregation targets (see {@link MemoryDataArgumentMap#isAggregationTarget(Object)})
	 * since their signatures depend on additional context not available here.</p>
	 *
	 * @return a signature string in format "delegate|count|isFixedCount", or null for aggregation targets
	 */
	@Override
	public String signature() {
		if (MemoryDataArgumentMap.isAggregationTarget(op)) {
			// It should actually be possible to compute a valid signature
			// for this anyway, but because argument aggregation for
			// Computations depends on the other Computation arguments,
			// it requires more information than is available here
			return null;
		}

		return "delegate|" + getCountLong() + "|" + isFixedCount();
	}

	/**
	 * Returns a human-readable description of the wrapped producer.
	 *
	 * @return the description of the wrapped producer
	 */
	@Override
	public String describe() {
		return Describable.describe(op);
	}
}
