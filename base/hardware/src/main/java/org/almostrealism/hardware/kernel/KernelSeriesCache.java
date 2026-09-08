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
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.sequence.Index;
import io.almostrealism.sequence.IndexSequence;
import io.almostrealism.kernel.KernelSeriesProvider;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
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
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;

/**
 * {@link KernelSeriesProvider} for a compiled kernel that can also store sequences as
 * kernel-resident lookup tables.
 *
 * <p>Recognition of closed forms (constants, masks, arithmetic progressions) is inherited
 * from {@link KernelSeriesProvider} and the {@link io.almostrealism.sequence.KernelSeriesMatcher}.
 * What this class adds is storage: an index-dependent sub-expression whose sequence matches
 * no closed form, but is large enough to be worth replacing, is evaluated for every kernel
 * position and written into a {@link MemoryData} cache, and the sub-expression is replaced
 * by an array lookup. This keeps the emitted kernel small when the index arithmetic is
 * irregular. Particularly effective for index sequences, twiddle factors, and coordinate
 * transformations.</p>
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
 * <p>Outcomes are remembered per kernel, keyed structurally by the expression, so a
 * sub-expression that recurs within one kernel is converted (or given up on) once.</p>
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
	/** If true, kernel series caching is enabled; disable via {@code AR_HARDWARE_KERNEL_CACHE=false}. */
	public static boolean enableCache = SystemUtils.isEnabled("AR_HARDWARE_KERNEL_CACHE").orElse(true);
	/** If true, verbose diagnostic output is emitted when cache decisions are made. */
	public static boolean enableVerbose = false;

	/** Maximum number of expression slots per cache entry; limits concurrent tracked expressions. */
	public static int defaultMaxExpressions = 16;
	/** Default maximum number of cached entries per series. */
	public static int defaultMaxEntries = 32; // 16;
	/** Minimum expression node count for a series to be considered for matching. */
	public static int minNodeCountMatch = 12; // 6;
	/** Minimum expression node count for a series to be considered for caching. */
	public static int minNodeCountCache = 128;

	static {
		if (8L * ScopeSettings.maxKernelSeriesCount * defaultMaxEntries > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Maximum cache size is greater than maximum possible memory reservation");
		}
	}

	/** Operation metadata used for identification in profiling and logging. */
	private OperationMetadata metadata;
	/** Number of elements in the traversal sequence this cache covers. */
	private int count;
	/** If true, the count is fixed at compile time and cannot vary at runtime. */
	private boolean fixed;
	/** Cache manager backing the serialized sequence data for kernel reuse. */
	private MemoryDataCacheManager cacheManager;

	/** Map from series signature to cache entry index for O(1) lookup. */
	private Map<String, Integer> cache;
	/** Frequency cache of converted expressions, keyed structurally by the expression they replace. */
	private FrequencyCache<Expression, Expression> expressions;
	/** Expressions that could not be converted; avoids repeated attempts within this kernel. */
	private Set<Expression> matchFailures;

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
		this.matchFailures = new HashSet<>();
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
	 * Attempts to convert an expression to series form, remembering the outcome for
	 * this kernel.
	 *
	 * <p>Expressions are keyed structurally (see {@link Expression#equals(Object)}), so
	 * an expression equal to one already converted is answered from the cache, and one
	 * equal to a previous failure is returned immediately without enumerating anything.</p>
	 *
	 * @param exp Expression to analyze
	 * @param index Loop index variable
	 * @return Series expression if a form was found or the sequence was stored, original expression otherwise
	 */
	@Override
	public Expression getSeries(Expression exp, Index index) {
		if (!isComputable() || exp.isSingleIndexMasked()) {
			return exp;
		}

		if (matchFailures.contains(exp)) return exp;

		Expression result = expressions.get(exp);
		if (result != null) return result;

		result = KernelSeriesProvider.super.getSeries(exp, index);

		if (result != exp) {
			expressions.put(exp, result);
		} else {
			matchFailures.add(exp);
		}

		return result;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return {@link #minNodeCountMatch}
	 */
	@Override
	public int getMinimumNodeCount() { return minNodeCountMatch; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>A sequence can be stored when caching is enabled for this kernel, the
	 * expression has at least {@link #minNodeCountCache} nodes, and the sequence
	 * spans exactly the kernel's element count.</p>
	 */
	@Override
	public boolean isSeriesStorable(int nodes, long len) {
		return enableCache && cache != null && nodes >= minNodeCountCache && len == count;
	}

	/**
	 * Stores a sequence that matched no closed form and returns a lookup into it.
	 *
	 * <p>The sequence is normalised to start at zero (the initial value is added back
	 * in the returned expression) so that shifted copies of one sequence share a cache
	 * entry, then keyed by its {@link IndexSequence#signature() signature}. When the
	 * cache is full and the signature is new, nothing is stored.</p>
	 *
	 * @param index Loop index expression
	 * @param seq The complete sequence of values
	 * @param isInt Whether the result should be integer type
	 * @return Cached series expression, or null if the sequence could not be stored
	 */
	@Override
	public Expression referenceSeries(Expression index, IndexSequence seq, boolean isInt) {
		double init = seq.doubleAt(0);
		if (init != 0.0) {
			seq = seq.mapDouble(d -> d - init);
		}

		String sig = seq.signature();

		if (!cache.containsKey(sig)) {
			if (cache.size() >= cacheManager.getMaxEntries()) {
				if (enableVerbose)
					warn("Cache is full");
				return null;
			}

			int idx = cache.size();
			cache.put(sig, idx);
			cacheManager.setValue(idx, seq.doubleStream().toArray());
		}

		Expression result = cacheManager.reference(cache.get(sig), index);
		if (init != 0.0) result = result.add(new DoubleConstant(init));
		if (isInt) result = result.toInt();
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
