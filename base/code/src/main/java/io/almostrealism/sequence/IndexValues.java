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

package io.almostrealism.sequence;

import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelIndexChild;
import io.almostrealism.expression.Expression;

import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A container that maps named {@link Index} variables to their concrete integer values
 * for evaluating expressions at specific positions in a kernel.
 *
 * <p>{@code IndexValues} is used during sequence generation and expression evaluation to
 * substitute actual integer values for symbolic index variables. It tracks the kernel
 * (GPU thread) index separately from other named indices, and ensures consistency
 * when {@link io.almostrealism.kernel.KernelIndexChild} values are assigned.</p>
 *
 * @see Index
 * @see SequenceGenerator
 */
public class IndexValues {
	/** The value of the kernel (GPU thread) index, or {@code null} if not set. */
	private Integer kernelIndex;
	/** The values of named indices, keyed by index name. */
	private Map<String, Integer> values;

	/**
	 * Memoizes {@link Expression#value(IndexValues)} results for the duration of a single
	 * evaluation, keyed by expression node identity. Expression graphs are DAGs with shared
	 * subexpressions; without memoization a shared node is recomputed once per path to it,
	 * which is exponential in tree depth for deeply nested gradients. Because a fresh
	 * {@code IndexValues} is created per evaluation (and the same instance is passed down the
	 * recursion), this cache is automatically scoped to one evaluation and confined to one
	 * thread. Allocated lazily so leaf-only evaluations pay nothing.
	 *
	 * <p>Populated only when {@link io.almostrealism.scope.ScopeSettings#enableValueMemoization}
	 * is {@code true}; it is {@code false} by default because the deep-DAG case it targeted is
	 * now covered by the gather-analysis gate, and the per-node bookkeeping otherwise slowed the
	 * common shallow case.</p>
	 */
	private Map<Expression, Number> valueCache;

	/**
	 * Creates an empty index values container with no kernel index.
	 */
	public IndexValues() { this((Integer) null); }

	/**
	 * Creates a copy of the given index values container.
	 *
	 * @param from the source container to copy from
	 */
	public IndexValues(IndexValues from) {
		this.kernelIndex = from.kernelIndex;
		this.values = new HashMap<>(from.values);
	}

	/**
	 * Creates an index values container with the specified kernel index.
	 *
	 * @param kernelIndex the initial kernel index value, or {@code null}
	 */
	public IndexValues(Integer kernelIndex) {
		this.kernelIndex = kernelIndex;
		this.values = new HashMap<>();
	}

	/**
	 * Returns the kernel (GPU thread) index value.
	 *
	 * @return the kernel index, or {@code null} if not set
	 */
	public Integer getKernelIndex() { return kernelIndex; }

	/**
	 * Returns the integer value for the named index.
	 *
	 * @param name the name of the index to retrieve
	 * @return the index value, or {@code null} if not set
	 */
	public Integer getIndex(String name) {
		return values.get(name);
	}

	/**
	 * Returns whether the named index has been assigned a value.
	 *
	 * @param name the name of the index to check
	 * @return {@code true} if the index has a value
	 */
	public boolean containsIndex(String name) {
		return values.containsKey(name);
	}

	/**
	 * Returns the memoized value previously computed for the given expression node during
	 * this evaluation, or {@code null} if it has not yet been computed.
	 *
	 * @param key the expression node (compared by identity)
	 * @return the cached value, or {@code null} if absent
	 */
	public Number getCachedValue(Expression key) {
		return valueCache == null ? null : valueCache.get(key);
	}

	/**
	 * Memoizes the value computed for the given expression node so that revisits of the same
	 * node within this evaluation reuse it rather than recomputing. The backing map is
	 * allocated on first use.
	 *
	 * @param key the expression node (compared by identity)
	 * @param value the computed value to cache
	 */
	public void putCachedValue(Expression key, Number value) {
		if (valueCache == null) valueCache = new IdentityHashMap<>();
		valueCache.put(key, value);
	}

	/**
	 * Associates the given name with the given integer index value.
	 *
	 * @param name the name of the index
	 * @param index the integer value to assign
	 * @return this container (for chaining)
	 */
	public IndexValues addIndex(String name, Integer index) {
		values.put(name, index);
		valueCache = null;
		return this;
	}

	/**
	 * Assigns a value to the given index, updating the kernel index if necessary.
	 *
	 * <p>If {@code idx} is a {@link io.almostrealism.kernel.KernelIndex}, the kernel index is
	 * updated directly. If it is a {@link io.almostrealism.kernel.KernelIndexChild}, both the
	 * named index and the derived kernel index are updated.
	 *
	 * @param idx the index to assign
	 * @param value the integer value to assign
	 * @return this container (for chaining)
	 * @throws IllegalArgumentException if the kernel index conflicts with a previously set value
	 */
	public IndexValues put(Index idx, Integer value) {
		valueCache = null;
		if (idx instanceof KernelIndex) {
			kernelIndex = value;
		} else {
			values.put(idx.getName(), value);

			if (idx instanceof KernelIndexChild) {
				int ki = ((KernelIndexChild) idx).kernelIndex(value.intValue());

				if (kernelIndex == null) {
					kernelIndex = ki;
				} else if (kernelIndex != ki) {
					throw new IllegalArgumentException("Kernel index mismatch");
				}
			}
		}

		return this;
	}

	/**
	 * Substitutes all index values in this container into the given expression.
	 *
	 * <p>Each named index value is applied via {@link io.almostrealism.expression.Expression#withValue},
	 * and the kernel index (if set) is applied via {@link io.almostrealism.expression.Expression#withIndex}.
	 *
	 * @param exp the expression to substitute into
	 * @return a new expression with all index values substituted
	 */
	public Expression apply(Expression exp) {
		for (Map.Entry<String, Integer> entry : values.entrySet()) {
			exp = exp.withValue(entry.getKey(), entry.getValue());
		}

		if (kernelIndex != null) {
			exp = exp.withIndex(new KernelIndex(), kernelIndex);
		}

		return exp;
	}

	/**
	 * Creates an index values container with all given indices set to zero.
	 *
	 * @param indices the indices to include (all set to 0)
	 * @return a new index values container
	 */
	public static IndexValues of(Index... indices) {
		return of(Stream.of(indices));
	}

	/**
	 * Creates an index values container with all given indices set to zero.
	 *
	 * @param indices the collection of indices to include (all set to 0)
	 * @return a new index values container
	 */
	public static IndexValues of(Collection<Index> indices) {
		return of(indices.stream());
	}

	/**
	 * Creates an index values container with all indices from the stream set to zero.
	 *
	 * @param indices a stream of indices to include (all set to 0)
	 * @return a new index values container
	 */
	public static IndexValues of(Stream<Index> indices) {
		IndexValues values = new IndexValues();
		indices.forEach(idx -> values.put(idx, 0));
		return values;
	}
}
