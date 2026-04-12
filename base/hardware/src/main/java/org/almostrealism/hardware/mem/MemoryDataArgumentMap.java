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

package org.almostrealism.hardware.mem;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.Memory;
import io.almostrealism.code.NameProvider;
import io.almostrealism.collect.CollectionScopeInputManager;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.ProviderAwareArgumentMap;
import org.almostrealism.hardware.jvm.JVMMemoryProvider;
import org.almostrealism.io.SystemUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Argument mapping for kernel compilation with automatic memory aggregation and provider adaptation.
 *
 * <p>{@link MemoryDataArgumentMap} manages the conversion of {@link MemoryData} instances into
 * kernel arguments, automatically aggregating multiple small memory objects into a single argument
 * to reduce kernel invocation overhead and cross-provider transfer costs.</p>
 *
 * <h2>Memory Aggregation</h2>
 *
 * <p>When multiple small {@link MemoryData} arguments would require individual transfers from
 * incompatible providers, they are automatically aggregated into a single contiguous buffer:</p>
 * <pre>{@code
 * // Without aggregation: 3 separate kernel arguments
 * kernel(cpuMem1, cpuMem2, cpuMem3)  // 3 CPU->GPU transfers
 *
 * // With aggregation: Single aggregated argument
 * aggregated = aggregate(cpuMem1, cpuMem2, cpuMem3)
 * kernel(aggregated)  // 1 CPU->GPU transfer
 * }</pre>
 *
 * <h2>Configuration</h2>
 *
 * <p>Aggregation behavior is controlled via environment variables:</p>
 * <ul>
 *   <li><b>AR_HARDWARE_ARGUMENT_AGGREGATION</b>: Enable/disable aggregation (default: true)</li>
 *   <li><b>AR_HARDWARE_OFF_HEAP_AGGREGATION</b>: Aggregate off-heap memory (default: false)</li>
 *   <li><b>AR_HARDWARE_AGGREGATE_MAX</b>: Max size for aggregation (default: 1MB)</li>
 * </ul>
 *
 * <h2>Root Delegate Handling</h2>
 *
 * <p>Arguments that share a root delegate are automatically de-duplicated to avoid redundant
 * kernel arguments for views into the same underlying memory.</p>
 *
 * @param <S> Scope type
 * @param <A> Argument type
 * @see MemoryDataReplacementMap
 * @see MemoryReplacementManager
 */
public class MemoryDataArgumentMap<S, A> extends ProviderAwareArgumentMap<S, A> {
	/** If true, automatically detect and configure destination memory arguments for output. */
	public static final boolean enableDestinationDetection = true;
	/** If true, emit warning messages when aggregation constraints cannot be satisfied. */
	public static boolean enableWarnings = false;

	/** If true, aggregate multiple small off-heap arguments into a single kernel argument. Controlled by {@code AR_HARDWARE_ARGUMENT_AGGREGATION}. */
	public static boolean enableArgumentAggregation = SystemUtils.isEnabled("AR_HARDWARE_ARGUMENT_AGGREGATION").orElse(true);
	/** If true, include non-JVM (off-heap native) memory in argument aggregation. Controlled by {@code AR_HARDWARE_OFF_HEAP_AGGREGATION}. */
	public static boolean enableOffHeapAggregation = SystemUtils.isEnabled("AR_HARDWARE_OFF_HEAP_AGGREGATION").orElse(false);
	/** Maximum element count for an argument to be eligible for aggregation. Controlled by {@code AR_HARDWARE_AGGREGATE_MAX}. */
	public static int maxAggregateLength = SystemUtils.getInt("AR_HARDWARE_AGGREGATE_MAX").orElse(1 * 1024 * 1024);

	/** The compute context providing language, memory, and kernel support for this argument map. */
	private final ComputeContext<MemoryData> context;
	/** Metadata for the operation being compiled, used for argument naming. */
	private final OperationMetadata metadata;

	/** Maps raw memory objects to the argument variables created for them. */
	private final Map<Memory, ArrayVariable<A>> mems;
	/** Maps memory data references to their offset position within the aggregate argument. */
	private final Map<MemoryDataRef, Integer> aggregatePositions;
	/** All root delegate provider suppliers created by this map, for lifecycle management. */
	private final List<RootDelegateProviderSupplier> rootDelegateSuppliers;
	/** True if this map is being used for kernel (parallel) evaluation; false for scalar evaluation. */
	private final boolean kernel;

	/** Manages pre/post-processing operations for memory replacement during aggregation. */
	private MemoryDataReplacementMap replacementMap;
	/** Factory function creating aggregate memory buffers of a given element count. */
	private IntFunction<MemoryData> aggregateGenerator;
	/** Total number of elements accumulated in the aggregate argument so far. */
	private int aggregateLength;

	/** Lazily created aggregate memory buffer that holds all aggregated argument data. */
	private MemoryData aggregateData;
	/** Producer that provides the aggregate buffer to the kernel. */
	private Producer<MemoryData> aggregateSupplier;
	/** Argument variable for the aggregate buffer, created once and shared. */
	private ArrayVariable<A> aggregateArgument;

	/**
	 * Creates an argument map without argument aggregation support.
	 *
	 * @param context Compute context providing language and memory services
	 * @param metadata Operation metadata for argument naming
	 */
	public MemoryDataArgumentMap(ComputeContext<MemoryData> context,
								 OperationMetadata metadata) { this(context, metadata, null); }

	/**
	 * Creates an argument map with aggregation enabled for kernel (parallel) evaluation.
	 *
	 * @param context Compute context providing language and memory services
	 * @param metadata Operation metadata for argument naming
	 * @param aggregateGenerator Factory function for creating aggregate memory buffers; may be null to disable
	 */
	public MemoryDataArgumentMap(ComputeContext<MemoryData> context, OperationMetadata metadata,
								 IntFunction<MemoryData> aggregateGenerator) {
		this(context, metadata, aggregateGenerator, true); }

	/**
	 * Creates a fully configured argument map.
	 *
	 * @param context Compute context providing language and memory services
	 * @param metadata Operation metadata for argument naming
	 * @param aggregateGenerator Factory function for creating aggregate memory buffers; may be null
	 * @param kernel True if used for kernel evaluation; false for scalar evaluation
	 */
	public MemoryDataArgumentMap(ComputeContext<MemoryData> context, OperationMetadata metadata, IntFunction<MemoryData> aggregateGenerator, boolean kernel) {
		this.context = context;
		this.metadata = metadata;

		this.mems = new HashMap<>();
		this.aggregatePositions = new HashMap<>();
		this.rootDelegateSuppliers = new ArrayList<>();
		this.kernel = kernel;

		this.aggregateGenerator = aggregateGenerator;

		if (enableArgumentAggregation) {
			replacementMap = new MemoryDataReplacementMap();
		}
	}

	/** Returns the operation list that copies data into the aggregate buffer before kernel execution. */
	public OperationList getPrepareData() { return replacementMap == null ? new OperationList() : replacementMap.getPreprocess(); }
	/** Returns the operation list that copies results out of the aggregate buffer after kernel execution. */
	public OperationList getPostprocessData() { return replacementMap == null ? new OperationList() : replacementMap.getPostprocess(); }

	/** Returns the memory replacement map tracking all argument aggregation replacements, or null if aggregation is disabled. */
	public MemoryDataReplacementMap getReplacementMap() { return replacementMap; }

	/** Returns true if any memory replacements have been registered (i.e., at least one argument was aggregated). */
	public boolean hasReplacements() { return replacementMap != null && !replacementMap.isEmpty(); }

	@Override
	public ArrayVariable<A> get(Supplier key, NameProvider p) {
		long start = System.nanoTime();

		try {
			ArrayVariable<A> arg = super.get(key, p);
			if (arg != null) return arg;

			MemoryData md;

			boolean generateArg = false;

			// A MemoryDataDestination carries information about how to produce
			// the data in that destination, along with the MemoryData itself.
			// There needs to be a way to return a delegated variable, as we do
			// below, but not lose the knowledge that we rely on during the
			// creation of required scopes, ie the knowledge of how that data
			// is populated. The root delegate will have no logical way to do
			// this because it may have many children produced in different
			// ways, so it probably has to be stored with the Delegated variable,
			// but it cannot be tracked using the delegate field because that
			// is already used to point at the root delegate MemoryData
			if (enableDestinationDetection && !kernel && key instanceof MemoryDataDestinationProducer) {
				Object dest = ((MemoryDataDestinationProducer) key).get().evaluate();
				if (dest != null && !(dest instanceof MemoryData)) {
					throw new RuntimeException();
				}

				md = (MemoryData) dest;
			} else {
				Object provider = key.get();
				if (!(provider instanceof Provider)) return null;
				if (!(((Provider) provider).get() instanceof MemoryData)) return null;

				md = (MemoryData) ((Provider) provider).get();

				// If the provider points to a MemoryData that is stored outside of device memory,
				// it is a candidate for argument aggregation below
				generateArg = md.getMem().getProvider() != context.getDataContext().getKernelMemoryProvider();
			}

			if (md == null) return null;
			if (md.getMem() == null) {
				throw new IllegalArgumentException();
			}

			if (mems.containsKey(md.getMem())) {
				// If the root delegate already had an argument produced for it,
				// return that
				return delegateProvider.getArgument(p, key, mems.get(md.getMem()), md.getOffset());
			} else {
				ArrayVariable var = null;

				if (generateArg) {
					// If aggregation is desired for this MemoryData, try to
					// generate the aggregate argument for the root delegate
					var = generateArgument(p, createDelegate(md), md.getRootDelegate());
				}

				if (var == null) {
					// Otherwise, just obtain the array variable for the root delegate
					var = delegateProvider.getArgument(p, createDelegate(md), null, -1);
				}

				// Record that this MemoryData has var as its root delegate
				mems.put(md.getMem(), var);

				// Return an ArrayVariable that delegates to the correct position of the root delegate
				return delegateProvider.getArgument(p, key, var, md.getOffset());
			}
		} finally {
			if (MemoryDataReplacementMap.profile != null) {
				MemoryDataReplacementMap.profile.recordDuration(null, metadata.appendShortDescription(" get"), System.nanoTime() - start);
			}
		}
	}

	/**
	 * Creates a {@link RootDelegateProviderSupplier} for the given memory data and registers it for cleanup.
	 *
	 * @param md The memory data to create a root delegate provider for
	 * @return New {@link RootDelegateProviderSupplier} wrapping the given memory data
	 */
	private RootDelegateProviderSupplier createDelegate(MemoryData md) {
		RootDelegateProviderSupplier d = new RootDelegateProviderSupplier(md);
		rootDelegateSuppliers.add(d);
		return d;
	}

	@Override
	public void destroy() {
		super.destroy();
		rootDelegateSuppliers.forEach(RootDelegateProviderSupplier::destroy);
		mems.forEach((k, v) -> v.destroy());
		mems.clear();
		aggregatePositions.clear();
		if (replacementMap != null) replacementMap.destroy();
		if (aggregateData != null) aggregateData.destroy();
	}

	/**
	 * Returns the aggregate memory buffer, creating it on first access via the {@link #aggregateGenerator}.
	 *
	 * @return The aggregate {@link MemoryData}, or null if no elements have been aggregated
	 */
	protected MemoryData getAggregateData() {
		if (aggregateLength > 0 && aggregateData == null) {
			aggregateData = aggregateGenerator.apply(aggregateLength);
		}

		return aggregateData;
	}

	/**
	 * Returns the producer that provides the aggregate buffer to the kernel, creating it on first access.
	 *
	 * @return Producer for the aggregate {@link MemoryData}
	 */
	protected Producer<MemoryData> getAggregateSupplier() {
		if (aggregateSupplier == null) {
			aggregateSupplier = new AggregateProducer();
		}

		return aggregateSupplier;
	}

	/**
	 * Returns the argument variable for the aggregate buffer, creating it on first access.
	 *
	 * @param p Name provider for generating the argument variable name
	 * @return Argument variable for the aggregate buffer
	 */
	protected ArrayVariable<A> getAggregateArgument(NameProvider p) {
		if (aggregateArgument == null) {
			aggregateArgument = delegateProvider.getArgument(p, (Supplier) getAggregateSupplier(), null, -1);
		}

		return aggregateArgument;
	}

	/**
	 * Generates an argument variable for the given memory data, or null if aggregation is not applicable.
	 *
	 * <p>If aggregation has already occurred for this memory data, returns a delegating argument
	 * pointing to the correct position in the aggregate. Otherwise, the first eligible memory data
	 * triggers creation of a new aggregate.</p>
	 *
	 * @param p   Name provider for generating variable names
	 * @param key Supplier used as the cache key for this argument
	 * @param md  Memory data to generate an argument for
	 * @return Argument variable, or null if aggregation is not applicable
	 */
	private ArrayVariable<A> generateArgument(NameProvider p, Supplier key, MemoryData md) {
		if (aggregateGenerator == null || !isAggregationTarget(md)) return null;

		if (md.getMem().getProvider() == context.getDataContext().getKernelMemoryProvider())
			return null;

		if (aggregatePositions.containsKey(new MemoryDataRef(md))) {
			// If aggregation has already occurred for this MemoryData,
			// then return an argument that delegates to the correct position
			// of the aggregated argument
			return delegateProvider.getArgument(p, key, getAggregateArgument(p),
						aggregatePositions.get(new MemoryDataRef(md)));
		}

		if (aggregateData != null) {
			throw new IllegalArgumentException("Cannot generate argument when aggregate data is already built");
		}

		int pos = aggregateLength;

		// Remember the position of this MemoryData in the aggregate argument
		// in case another attempt is made at aggregation for a different
		// provider of the same underlying MemoryData
		aggregatePositions.put(new MemoryDataRef(md), pos);

		// Update the pre/post operations to move the data into and out of the aggregate argument data
		replacementMap.addReplacement(md, getAggregateSupplier(), pos);

		long tot = pos + (long) md.getMemLength();
		if (tot > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Argument aggregate is too large");
		}

		// Expand the required length of the aggregate argument
		aggregateLength += md.getMemLength();

		// Return a delegate to the aggregate argument
		return delegateProvider.getArgument(p, key, getAggregateArgument(p), pos);
	}

	/**
	 * Internal {@link Producer} that provides the lazily created aggregate buffer to the kernel.
	 */
	private class AggregateProducer implements Producer<MemoryData> {
		@Override
		public Evaluable<MemoryData> get() {
			return new Provider<>(getAggregateData());
		}
	}

	/**
	 * Returns true if the value produced by the given producer is eligible for argument aggregation.
	 *
	 * @param p Producer to test
	 * @return True if the produced value is a {@link MemoryData} that can be aggregated
	 */
	public static boolean isAggregationTarget(Producer<?> p) {
		Evaluable<?> eval = p.get();
		if (!(eval instanceof Provider)) return false;

		Object v = ((Provider<?>) eval).get();
		return v instanceof MemoryData && isAggregationTarget((MemoryData) v);
	}

	/**
	 * Returns true if the given {@link MemoryData} is eligible for argument aggregation.
	 *
	 * <p>A memory data is eligible when aggregation is enabled, its size does not exceed
	 * {@link #maxAggregateLength}, and (if off-heap aggregation is disabled) it uses JVM heap memory.</p>
	 *
	 * @param md Memory data to test
	 * @return True if the memory data can be included in an aggregate argument
	 */
	public static boolean isAggregationTarget(MemoryData md) {
		if (!enableArgumentAggregation || md == null || md.getMem() == null)
			return false;

		if (md.getMemLength() > maxAggregateLength) {
			if (enableWarnings) {
				Hardware.console.features(MemoryDataArgumentMap.class)
						.log("Unable to aggregate " + md.getMem().getProvider().getName() +
							" argument (" + md.getMemLength() + " > " + maxAggregateLength + ")");
			}

			return false;
		}

		if (!enableOffHeapAggregation && !(md.getMem().getProvider() instanceof JVMMemoryProvider))
			return false;

		return true;
	}

	/**
	 * Creates and configures a {@link MemoryDataArgumentMap} with the appropriate delegate provider.
	 *
	 * @param context Compute context providing language and memory services
	 * @param metadata Operation metadata for argument naming
	 * @param aggregateGenerator Factory for creating aggregate buffers; may be null
	 * @param kernel True if the map is used for kernel evaluation
	 * @return Fully configured {@link MemoryDataArgumentMap}
	 */
	public static MemoryDataArgumentMap create(ComputeContext<MemoryData> context, OperationMetadata metadata, IntFunction<MemoryData> aggregateGenerator, boolean kernel) {
		MemoryDataArgumentMap map = new MemoryDataArgumentMap(context, metadata, aggregateGenerator, kernel);
		map.setDelegateProvider(CollectionScopeInputManager.getInstance(context.getLanguage()));
		return map;
	}
}
