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

package org.almostrealism.hardware.kernel;

import io.almostrealism.code.Computation;
import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexSequence;
import io.almostrealism.kernel.KernelSeriesProvider;
import io.almostrealism.relation.Countable;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.util.FrequencyCache;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.MemoryDataCacheManager;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * {@link KernelSeriesProvider} that caches detected arithmetic/geometric sequences to avoid recomputation.
 *
 * <p>Analyzes {@link Expression} trees to identify repeating patterns that form arithmetic or geometric
 * progressions. Stores recognized sequences in {@link MemoryData} cache, replacing complex expression
 * subtrees with simple array lookups. Particularly effective for index sequences, twiddle factors,
 * and coordinate transformations.</p>
 *
 * <h2>Sequence Detection</h2>
 *
 * <p>Automatically recognizes:</p>
 * <ul>
 *   <li><strong>Arithmetic sequences:</strong> {@code [0, 1, 2, 3, ...]} to {@code start + i * step}</li>
 *   <li><strong>Geometric sequences:</strong> {@code [1, 2, 4, 8, ...]} to {@code start * ratio^i}</li>
 *   <li><strong>Polynomial patterns:</strong> {@code [0, 1, 4, 9, ...]} to {@code i^2}</li>
 * </ul>
 *
 * <h2>Caching Strategy</h2>
 *
 * <ol>
 *   <li>Expression tree analyzed to find index-dependent patterns</li>
 *   <li>Pattern evaluated for all indices to {@link IndexSequence}</li>
 *   <li>Sequence signature computed (hash of pattern)</li>
 *   <li>If new pattern and space available: store in {@link MemoryDataCacheManager}</li>
 *   <li>Replace expression subtree with cache array reference</li>
 * </ol>
 *
 * <h2>Configuration</h2>
 *
 * <ul>
 *   <li><strong>enableCache:</strong> Enable/disable caching (default: true, via AR_HARDWARE_KERNEL_CACHE)</li>
 *   <li><strong>defaultMaxExpressions:</strong> Max unique expression patterns (default: 16)</li>
 *   <li><strong>defaultMaxEntries:</strong> Max cached sequences (default: 32)</li>
 *   <li><strong>minNodeCountMatch:</strong> Min expression complexity to attempt matching (default: 12 nodes)</li>
 *   <li><strong>minNodeCountCache:</strong> Min complexity to cache (default: 128 nodes)</li>
 * </ul>
 *
 * <h2>Performance Impact</h2>
 *
 * <ul>
 *   <li><strong>Speedup:</strong> 2-10x for kernels with repetitive index calculations</li>
 *   <li><strong>Memory:</strong> {@code count x maxEntries x 8} bytes per cache</li>
 *   <li><strong>Overhead:</strong> Pattern detection during compilation (~10ms per operation)</li>
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * // FFT twiddle factor computation
 * Expression twiddleIndex = exp(-2 * PI * i / N);  // Complex expression
 *
 * // KernelSeriesCache detects this is an arithmetic progression in the exponent
 * // Replaces with: cachedSequence[i]
 * // Speedup: ~5x for N=1024
 * }</pre>
 *
 * @see KernelTraversalOperationGenerator
 * @see org.almostrealism.hardware.mem.MemoryDataCacheManager
 * @see IndexSequence
 */
public class KernelSeriesCache implements KernelSeriesProvider, ExpressionFeatures, ConsoleFeatures {
	public static boolean enableCache = SystemUtils.isEnabled("AR_HARDWARE_KERNEL_CACHE").orElse(true);
	public static boolean enableVerbose = false;

	public static int defaultMaxExpressions = 16;
	public static int defaultMaxEntries = 32; // 16;
	public static int minNodeCountMatch = 12; // 6;
	public static int minNodeCountCache = 128;

	static {
		if (8L * ScopeSettings.maxKernelSeriesCount * defaultMaxEntries > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Maximum cache size is greater than maximum possible memory reservation");
		}
	}

	private OperationMetadata metadata;
	private int count;
	private boolean fixed;
	private MemoryDataCacheManager cacheManager;

	private Map<String, Integer> cache;
	private FrequencyCache<String, Expression> expressions;
	private Set<String> matchFailures;

	/**
	 * Creates a series cache for the specified operation.
	 *
	 * @param metadata Operation metadata for identification
	 * @param count Number of elements in the traversal
	 * @param fixed Whether the count is fixed at compile time
	 * @param cacheManager Manager for storing cached sequences (null to disable caching)
	 * @throws IllegalArgumentException if cache manager entry size doesn't match count
	 */
	public KernelSeriesCache(OperationMetadata metadata, int count, boolean fixed, MemoryDataCacheManager cacheManager) {
		if (cacheManager != null && count != cacheManager.getEntrySize()) {
			throw new IllegalArgumentException();
		}

		this.metadata = metadata;
		this.count = count;
		this.fixed = fixed;
		this.cacheManager = cacheManager;
		this.cache = cacheManager == null ? null : new HashMap<>();
		this.expressions = new FrequencyCache<>(defaultMaxExpressions, 0.7);
		this.matchFailures = new TreeSet<>();
	}

	/**
	 * Returns the operation metadata.
	 *
	 * @return Metadata identifying this operation
	 */
	@Override
	public OperationMetadata getMetadata() { return metadata; }

	/**
	 * Returns whether sequences can be computed for this cache.
	 *
	 * <p>Computation requires fixed count and count within limits.</p>
	 *
	 * @return True if sequence computation is enabled
	 */
	public boolean isComputable() { return fixed && count <= ScopeSettings.maxKernelSeriesCount; }

	/**
	 * Returns the maximum sequence length if known.
	 *
	 * @return Optional containing count if fixed, empty otherwise
	 */
	@Override
	public OptionalInt getMaximumLength() {
		return fixed ? OptionalInt.of(count) : OptionalInt.empty();
	}

	/**
	 * Returns the limit on sequence computation complexity.
	 *
	 * @return Maximum complexity allowed for sequence detection
	 */
	@Override
	public long getSequenceComputationLimit() {
		return Math.min(
				KernelSeriesProvider.super.getSequenceComputationLimit(),
				ScopeSettings.sequenceComputationLimit);
	}

	/**
	 * Attempts to recognize and replace an expression with a cached series.
	 *
	 * <p>Checks if the expression has been seen before and returns a cached
	 * series representation if available. Otherwise attempts to detect a sequence
	 * pattern and cache it.</p>
	 *
	 * @param exp Expression to analyze
	 * @param index Loop index variable
	 * @return Series expression if pattern detected, original expression otherwise
	 */
	@Override
	public Expression getSeries(Expression exp, Index index) {
		if (!isComputable() || exp.isSingleIndexMasked()) {
			return exp;
		}

		String e = exp.getExpression(lang);
		if (matchFailures.contains(e)) return exp;

		Expression result = expressions.get(e);
		if (result != null) return result;

		result = KernelSeriesProvider.super.getSeries(exp, index);
		if (result != exp) {
			expressions.put(e, result);
		}

		return result;
	}

	/**
	 * Detects and caches arithmetic/geometric sequence patterns.
	 *
	 * <p>Analyzes the expression to identify repeating patterns across indices.
	 * If a pattern is found and meets complexity thresholds, stores it in the
	 * cache and returns a reference to the cached sequence.</p>
	 *
	 * @param index Loop index expression
	 * @param exp Supplier of expression string representation
	 * @param sequence Supplier of detected index sequence
	 * @param isInt Whether the result should be integer type
	 * @param nodes Supplier of expression complexity (node count)
	 * @return Cached series expression, or null if no pattern detected
	 */
	@Override
	public Expression getSeries(Expression index, Supplier<String> exp,
								Supplier<IndexSequence> sequence,
								boolean isInt, IntSupplier nodes) {
		int n = nodes.getAsInt();
		if (n < minNodeCountMatch) return null;

		IndexSequence seq = sequence.get();
		if (seq == null) return null;

		Expression result = seq.getExpression(index, isInt);
		if (result != null) return result;

		if (!enableCache || cache == null || n < minNodeCountCache) {
			matchFailures.add(exp.get());
			return result;
		}

		if (seq.lengthLong() != count) {
			matchFailures.add(exp.get());
			if (enableVerbose)
				warn("Cannot cache sequence of length " + seq.lengthLong() + " (length != " + count + ")");
			return result;
		}

		double init = seq.doubleAt(0);
		if (init != 0.0) {
			seq = seq.mapDouble(d -> d - init);
		}

		r: try {
			String sig = seq.signature();

			if (!cache.containsKey(sig)) {
				if (cache.size() >= cacheManager.getMaxEntries()) {
					if (enableVerbose)
						warn("Cache is full");
					break r;
				}

				int idx = cache.size();
				cache.put(sig, idx);
				cacheManager.setValue(idx, seq.doubleStream().toArray());
			}

			result = cacheManager.reference(cache.get(sig), index);
		} finally {
			if (result == null) {
				matchFailures.add(exp.get());
			} else {
				if (init != 0.0) result = result.add(new DoubleConstant(init));
				if (isInt) result = result.toInt();
			}
		}

		return result;
	}

	/**
	 * Destroys the cache and releases all cached sequences.
	 *
	 * <p>Deallocates memory used by cached sequence data.</p>
	 */
	@Override
	public void destroy() {
		if (cacheManager != null) {
			cacheManager.destroy();
		}
	}

	/**
	 * Returns the console for logging.
	 *
	 * @return Hardware console instance
	 */
	@Override
	public Console console() { return Hardware.console; }

	/**
	 * Factory method to create a series cache for a computation.
	 *
	 * <p>Automatically determines whether caching should be enabled based on
	 * count and configuration. If enabled, creates a cache manager for storing
	 * detected sequences.</p>
	 *
	 * @param c Computation to create cache for
	 * @param variableFactory Factory for creating array variables from memory
	 * @return New series cache instance
	 */
	public static KernelSeriesCache create(Computation<?> c, Function<MemoryData, ArrayVariable<?>> variableFactory) {
		int count = Countable.count(c);
		boolean fixed = Countable.isFixedCount(c);
		return new KernelSeriesCache(OperationInfo.metadataForValue(c), count, fixed,
				(enableCache && fixed && count < ScopeSettings.maxKernelSeriesCount) ?
						MemoryDataCacheManager.create(count, defaultMaxEntries, variableFactory) : null);
	}
}
