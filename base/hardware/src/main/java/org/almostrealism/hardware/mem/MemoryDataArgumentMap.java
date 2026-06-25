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

import io.almostrealism.code.ArgumentProvider;
import io.almostrealism.code.DefaultScopeInputManager;
import io.almostrealism.code.Memory;
import io.almostrealism.code.SupplierArgumentMap;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.io.SystemUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Argument mapping for kernel compilation with provider adaptation.
 *
 * <p>{@link MemoryDataArgumentMap} manages the conversion of {@link MemoryData} instances into
 * kernel arguments, recognizing when different argument suppliers represent the same underlying
 * value so that a single kernel argument is shared:</p>
 * <ul>
 *   <li><b>PassThrough matching</b> &mdash; a {@link PassThroughProducer} key reuses an existing
 *       argument with the same referenced argument index.</li>
 *   <li><b>Provider value matching</b> &mdash; a {@link Provider} key reuses an existing argument
 *       that provides the same value instance.</li>
 *   <li><b>Root delegate handling</b> &mdash; views into the same underlying memory resolve to a
 *       single kernel argument that delegates to the appropriate offset.</li>
 * </ul>
 */
public class MemoryDataArgumentMap extends SupplierArgumentMap {
	/**
	 * If true, fold eligible small arguments into a single aggregate kernel argument. Controlled by
	 * {@code AR_HARDWARE_ARGUMENT_AGGREGATION}.
	 *
	 * <p>Defaults to {@code true}: the compile-time collapse keeps kernels under the compute
	 * context's buffer-argument limit (e.g. Metal's ~31, and the native compiler's parameter
	 * limit), which is required for fused kernels with many small inputs to evaluate at all. The
	 * copy plan copies aggregated inputs IN before the kernel and copies written slices back OUT
	 * afterward (skipping the slice that aliases an explicit {@code output}; see
	 * {@link #getPostprocessData(MemoryData)}), and instruction reuse is aggregation-safe (each
	 * reused operation gets its own aggregate buffer and the signature encodes the aggregate layout
	 * -- see {@code AcceleratedComputationOperation.rebindAggregateForReuse} and
	 * {@code CollectionProviderProducer.signature}). Set the env var to a disabled value to turn
	 * the collapse off (e.g. for debugging a kernel without aggregation).</p>
	 */
	public static boolean enableArgumentAggregation = SystemUtils.isEnabled("AR_HARDWARE_ARGUMENT_AGGREGATION").orElse(true);

	/**
	 * Maximum element count for an argument to be eligible for aggregation. Controlled by
	 * {@code AR_HARDWARE_AGGREGATE_MAX}. Eligibility depends only on this size -- a
	 * signature-stable structural property -- never on where the data currently lives.
	 */
	public static int maxAggregateLength = SystemUtils.getInt("AR_HARDWARE_AGGREGATE_MAX").orElse(1024);

	/**
	 * Controls whether aggregation copy-back runs when an operation is given an explicit output
	 * destination via {@link org.almostrealism.hardware.AcceleratedOperation#apply}. When false
	 * (default), supplying an explicit output suppresses aggregation copy-back entirely -- the
	 * caller is taken to be declaring that side-effects to other aggregated buffers need not be
	 * recorded. When true, copy-back runs even with an explicit output, except for the slice that
	 * aliases that output (so an in-place {@code x = x + y} is not overwritten by the stale
	 * read-copy of {@code x}). Controlled by {@code AR_HARDWARE_STRICT_SIDE_EFFECTS}.
	 */
	public static boolean enableStrictSideEffects = SystemUtils.isEnabled("AR_HARDWARE_STRICT_SIDE_EFFECTS").orElse(false);

	/** Maps raw memory objects to the argument variables created for them. */
	private final Map<Memory, ArrayVariable> mems;
	/** All root delegate provider suppliers created by this map, for lifecycle management. */
	private final List<RootDelegateProviderSupplier> rootDelegateSuppliers;

	/** Factory creating the aggregate buffer of a given element count, or null to disable aggregation. */
	private final IntFunction<MemoryData> aggregateGenerator;
	/** Root delegates folded into the aggregate, paired with their offset within it. */
	private final List<Replacement> replacements;
	/** Total number of elements accumulated in the aggregate argument so far. */
	private int aggregateLength;

	/** Lazily created aggregate buffer holding all aggregated argument data. */
	private MemoryData aggregateData;
	/** Producer that provides the aggregate buffer to the kernel. */
	private Producer<MemoryData> aggregateSupplier;
	/** Argument variable for the aggregate buffer, created once and shared. */
	private ArrayVariable aggregateArgument;

	/**
	 * Creates an argument map without aggregation support.
	 *
	 * @param delegateProvider the argument provider used to create new argument variables
	 */
	public MemoryDataArgumentMap(ArgumentProvider delegateProvider) {
		this(delegateProvider, null);
	}

	/**
	 * Creates an argument map.
	 *
	 * @param delegateProvider the argument provider used to create new argument variables
	 * @param aggregateGenerator factory for the aggregate buffer, or null to disable aggregation
	 */
	public MemoryDataArgumentMap(ArgumentProvider delegateProvider, IntFunction<MemoryData> aggregateGenerator) {
		super(delegateProvider);
		this.mems = new HashMap<>();
		this.rootDelegateSuppliers = new ArrayList<>();
		this.aggregateGenerator = aggregateGenerator;
		this.replacements = new ArrayList<>();
	}

	@Override
	public ArrayVariable get(Supplier key) {
		ArrayVariable arg = super.get(key);
		if (arg != null) return arg;

		// A PassThroughProducer key reuses an existing argument that passes through
		// the same referenced argument index.
		if (key instanceof Delegated<?> && ((Delegated) key).getDelegate() instanceof PassThroughProducer<?>) {
			PassThroughProducer param = (PassThroughProducer) ((Delegated) key).getDelegate();

			Optional<ArrayVariable> passThrough = get(v -> {
				if (!(v instanceof Delegated<?>)) return false;
				if (!(((Delegated) v).getDelegate() instanceof PassThroughProducer)) return false;
				return ((PassThroughProducer) ((Delegated) v).getDelegate()).getReferencedArgumentIndex() == param.getReferencedArgumentIndex();
			});

			if (passThrough.isPresent()) return passThrough.get();
		}

		Object provider = key.get();
		if (!(provider instanceof Provider)) return null;

		Object value = ((Provider) provider).get();

		// Reuse an existing argument that provides the same value instance.
		Optional<ArrayVariable> match = get(supplier -> {
			Object v = supplier.get();
			if (!(v instanceof Provider)) return false;
			return ((Provider) v).get() == value;
		});

		if (match.isPresent()) return match.get();

		// Otherwise register the MemoryData by its root delegate so that views into the
		// same underlying memory resolve to a single kernel argument.
		if (!(value instanceof MemoryData)) return null;

		MemoryData md = (MemoryData) value;
		if (md.getMem() == null) {
			throw new IllegalArgumentException();
		}

		if (mems.containsKey(md.getMem())) {
			// If the root delegate already had an argument produced for it,
			// return that
			return delegateProvider.getArgument(key, mems.get(md.getMem()), md.getOffset());
		} else {
			ArrayVariable var = null;

			// If aggregation is enabled and this root is small enough, fold it into the
			// shared aggregate buffer instead of giving it its own kernel argument.
			if (aggregateGenerator != null && isAggregationTarget(md.getRootDelegate())) {
				var = aggregate(createDelegate(md), md.getRootDelegate());
			}

			if (var == null) {
				// Otherwise obtain a standalone array variable for the root delegate
				var = delegateProvider.getArgument(createDelegate(md), null, -1);
			}

			// Record that this MemoryData has var as its root delegate
			mems.put(md.getMem(), var);

			// Return an ArrayVariable that delegates to the correct position of the root delegate
			return delegateProvider.getArgument(key, var, md.getOffset());
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

	/**
	 * Folds the given root delegate into the shared aggregate buffer, returning an argument
	 * variable that delegates to the aggregate at the assigned offset.
	 *
	 * @param key  Supplier identifying the root delegate argument being created
	 * @param root The root delegate {@link MemoryData} to aggregate
	 * @return Argument variable delegating into the aggregate, or null if it cannot be aggregated
	 */
	private ArrayVariable aggregate(Supplier key, MemoryData root) {
		int pos = aggregateLength;

		long tot = pos + (long) root.getMemLength();
		if (tot > Integer.MAX_VALUE) {
			// The aggregate would overflow; leave this argument on its own
			return null;
		}

		// Record the root and its position so the data can be copied into and out of
		// the aggregate around kernel execution.
		replacements.add(new Replacement(root, pos));
		aggregateLength += root.getMemLength();

		return delegateProvider.getArgument(key, getAggregateArgument(), pos);
	}

	/** Returns the argument variable for the aggregate buffer, creating it on first access. */
	private ArrayVariable getAggregateArgument() {
		if (aggregateArgument == null) {
			aggregateArgument = delegateProvider.getArgument((Supplier) getAggregateSupplier(), null, -1);
		}

		return aggregateArgument;
	}

	/**
	 * Returns the producer that provides this map's aggregate buffer to the kernel, creating it on
	 * first access.
	 *
	 * <p>Exposed so that a reused operation can bind the shared compiled scope's aggregate argument
	 * to its own per-operation aggregate buffer (see {@code AcceleratedComputationOperation.load}).</p>
	 *
	 * @return Producer of this map's aggregate buffer
	 */
	public Producer<MemoryData> getAggregateSupplier() {
		if (aggregateSupplier == null) {
			aggregateSupplier = new AggregateProducer();
		}

		return aggregateSupplier;
	}

	/** Returns the aggregate buffer, creating it on first access via the aggregate generator. */
	private MemoryData getAggregateData() {
		if (aggregateLength > 0 && aggregateData == null) {
			aggregateData = aggregateGenerator.apply(aggregateLength);
		}

		return aggregateData;
	}

	/** Returns true if at least one argument has been folded into the aggregate. */
	public boolean hasReplacements() { return !replacements.isEmpty(); }

	/** Returns the total number of elements folded into the aggregate buffer. */
	public int getAggregateLength() { return aggregateLength; }

	/** Returns a compact description of the aggregate layout (per-replacement offset:length). */
	public String describeAggregate() {
		StringBuilder b = new StringBuilder("len=").append(aggregateLength).append(" [");
		for (Replacement r : replacements) {
			b.append(r.getPosition()).append(':').append(r.getRoot().getMemLength()).append(',');
		}
		return b.append(']').toString();
	}

	/**
	 * Returns the operations that copy each aggregated root into the aggregate buffer before
	 * kernel execution.
	 *
	 * @return Pre-execution copy operations
	 */
	public OperationList getPrepareData() {
		OperationList prep = new OperationList("MemoryDataArgumentMap Preprocess");
		for (Replacement r : replacements) {
			MemoryData root = r.getRoot();
			int pos = r.getPosition();
			int len = root.getMemLength();
			prep.add(new MemoryDataCopy("Aggregate Prepare",
					() -> root, () -> new Bytes(len, getAggregateData(), pos), len));
		}
		return prep;
	}

	/**
	 * Returns the operations that copy every aggregated slice back to its source after kernel
	 * execution.
	 *
	 * @return Post-execution copy operations
	 */
	public OperationList getPostprocessData() {
		return getPostprocessData(null);
	}

	/**
	 * Returns the operations that copy each aggregated slice back to its source after kernel
	 * execution, omitting any slice whose source shares memory with {@code skipOutput}.
	 *
	 * <p>The skip is used to avoid writing a stale read-copy over the buffer the kernel was told
	 * to use as its explicit output (the in-place {@code x = x + y} case, where the aggregated
	 * read copy of {@code x} must not overwrite the freshly written {@code x}).</p>
	 *
	 * @param skipOutput a buffer whose memory is excluded from copy-back, or null to copy all
	 * @return Post-execution copy operations
	 */
	public OperationList getPostprocessData(MemoryData skipOutput) {
		Memory skip = skipOutput == null ? null : skipOutput.getRootDelegate().getMem();

		OperationList post = new OperationList("MemoryDataArgumentMap Postprocess");
		for (Replacement r : replacements) {
			MemoryData root = r.getRoot();
			if (skip != null && root.getRootDelegate().getMem() == skip) {
				continue;
			}
			int pos = r.getPosition();
			int len = root.getMemLength();
			post.add(new MemoryDataCopy("Aggregate Postprocess",
					() -> new Bytes(len, getAggregateData(), pos), () -> root, len));
		}
		return post;
	}

	@Override
	public void destroy() {
		super.destroy();
		rootDelegateSuppliers.forEach(RootDelegateProviderSupplier::destroy);
		mems.forEach((k, v) -> v.destroy());
		mems.clear();
		replacements.clear();
		if (aggregateData != null) {
			aggregateData.destroy();
			aggregateData = null;
		}
	}

	/**
	 * Returns true if the given {@link MemoryData} is eligible for aggregation.
	 *
	 * <p>Eligibility depends only on the argument's size -- a signature-stable structural
	 * property -- so the same computation always makes the same decision. It deliberately does
	 * <em>not</em> consider where the data currently lives (heap vs. device), because that is
	 * mutable runtime state that would make the decision (and the resulting kernel) vary for no
	 * semantic reason and break instruction-set reuse.</p>
	 *
	 * @param md Memory data to test
	 * @return True if the memory data can be folded into an aggregate argument
	 */
	public static boolean isAggregationTarget(MemoryData md) {
		return enableArgumentAggregation && md != null && md.getMem() != null
				&& md.getMemLength() <= maxAggregateLength;
	}

	/**
	 * Returns true if the given argument variable is the synthesized aggregate buffer argument
	 * produced by some {@link MemoryDataArgumentMap}.
	 *
	 * <p>The aggregate argument has no position in the {@link io.almostrealism.relation.Process}
	 * tree, so it is identified by its producer type. A reused operation uses this to locate the
	 * shared compiled scope's aggregate argument and rebind it to its own per-operation buffer.</p>
	 *
	 * @param arg The argument variable to test
	 * @return True if the argument provides an aggregate buffer
	 */
	public static boolean isAggregateArgument(ArrayVariable<?> arg) {
		Object producer = arg == null ? null : arg.getProducer();
		return producer instanceof AggregateProducer;
	}

	/**
	 * Creates and configures a {@link MemoryDataArgumentMap} without aggregation support.
	 *
	 * @return Fully configured {@link MemoryDataArgumentMap}
	 */
	public static MemoryDataArgumentMap create() {
		return create(null);
	}

	/**
	 * Creates and configures a {@link MemoryDataArgumentMap} with the appropriate delegate provider.
	 *
	 * @param aggregateGenerator factory for the aggregate buffer, or null to disable aggregation
	 * @return Fully configured {@link MemoryDataArgumentMap}
	 */
	public static MemoryDataArgumentMap create(IntFunction<MemoryData> aggregateGenerator) {
		return new MemoryDataArgumentMap(
				new DefaultScopeInputManager(
						(name, input) -> CollectionVariable.create(name, (Supplier) input)),
				aggregateGenerator);
	}

	/** Pairs an aggregated root delegate with its offset within the aggregate buffer. */
	private static class Replacement {
		/** The root delegate folded into the aggregate. */
		private final MemoryData root;
		/** The offset of this root within the aggregate buffer. */
		private final int position;

		/**
		 * Creates a replacement record.
		 *
		 * @param root     The root delegate folded into the aggregate
		 * @param position The offset of the root within the aggregate buffer
		 */
		public Replacement(MemoryData root, int position) {
			this.root = root;
			this.position = position;
		}

		/** Returns the root delegate folded into the aggregate. */
		public MemoryData getRoot() { return root; }

		/** Returns the offset of the root within the aggregate buffer. */
		public int getPosition() { return position; }
	}

	/** {@link Producer} that provides the lazily created aggregate buffer to the kernel. */
	private class AggregateProducer implements Producer<MemoryData> {
		@Override
		public Evaluable<MemoryData> get() {
			return new Provider<>(getAggregateData());
		}
	}
}
