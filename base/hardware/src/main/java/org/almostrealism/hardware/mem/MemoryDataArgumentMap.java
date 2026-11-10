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
import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.DefaultScopeInputManager;
import io.almostrealism.code.Memory;
import io.almostrealism.code.SupplierArgumentMap;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.concurrent.OnceSubmittable;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.KernelBindingListener;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.io.SystemUtils;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
 *
 * <p>Small arguments may also be folded into a single aggregate buffer (see
 * {@link #enableArgumentAggregation}). Their data then moves around each dispatch, and both
 * directions are limited to what the compiled kernel requires:</p>
 * <ul>
 *   <li><b>Copy-back</b> &mdash; only a slice whose source can be written and that the kernel may
 *       write is copied back (see {@link #getPostprocessOperations(MemoryData)}).</li>
 *   <li><b>Copy-in</b> &mdash; a slice whose source is {@link MemoryData#isReadOnly() read-only}
 *       and that the kernel is known not to write (see {@link #setWrittenSlices(BitSet)}) is copied
 *       in by the first dispatch only: the source cannot change, the aggregate buffer belongs to
 *       this map for as long as it lives, and nothing writes that slice of it, so it still holds the
 *       copied value at every later dispatch. A slice the kernel is known not to read (see
 *       {@link #setReadSlices(BitSet)}) is not copied in at all by a dispatch that does not copy it
 *       back afterwards, since nothing would observe the staged value. Every other slice is copied
 *       in before each dispatch (see {@link #getPrepareOperations(boolean)}).</li>
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
	 * {@link #getPostprocessOperations(MemoryData)}). Under instruction reuse each reused operation
	 * gets its own aggregate buffer, and its aggregate layout is verified against the layout the
	 * shared kernel was compiled with — a mismatch is an instruction cache collision and throws
	 * (see {@code AcceleratedComputationOperation.rebindAggregateForReuse}). Set the env var to a
	 * disabled value to turn the collapse off (e.g. for debugging a kernel without aggregation).</p>
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
	/** Root delegate provider suppliers owned by this map, released when it is destroyed. */
	private final List<RootDelegateProviderSupplier> rootDelegateSuppliers;

	/** Factory creating the aggregate buffer of a given element count, or null to disable aggregation. */
	private final IntFunction<MemoryData> aggregateGenerator;
	/**
	 * The {@link ComputeContext} of the {@link org.almostrealism.hardware.AcceleratedOperation} this map
	 * prepares arguments for. The aggregate copy-in/copy-out run through its
	 * {@link ComputeContext#copy(Object, Object, io.almostrealism.streams.Semaphore) copy} (see
	 * {@link #ensureCopyOperations()}), so the context the kernel program runs on chooses how the copy
	 * is performed and how it is ordered.
	 */
	private final ComputeContext<MemoryData> context;
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
	 * The copies of every replacement's slice, one per replacement in replacement order, each
	 * carrying its copy-in and copy-out together with what decides which dispatches submit them.
	 * {@link #getPrepareOperations(boolean)} and {@link #getPostprocessOperations(MemoryData)}
	 * filter this one list. Built once on first use and reused across every {@code apply}.
	 * Null until {@link #ensureCopyOperations()} runs.
	 */
	private List<SliceCopy> sliceCopies;
	/**
	 * The replacements, by index, whose slice the kernel this map prepares arguments for may write,
	 * as {@link #getWrittenSlices(Scope) read off} that kernel's compiled {@link Scope}; null while
	 * the kernel is unknown, in which case every slice that can be written is copied back.
	 */
	private BitSet writtenSlices;
	/**
	 * The replacements, by index, whose slice the kernel this map prepares arguments for may read,
	 * as {@link #getReadSlices(Scope) read off} that kernel's compiled {@link Scope}; null while
	 * the kernel is unknown, in which case every slice is copied in.
	 */
	private BitSet readSlices;

	/**
	 * Creates an argument map bound to the {@link ComputeContext} of the operation it prepares
	 * arguments for.
	 *
	 * @param context the {@link ComputeContext} the aggregate copies run through
	 * @param delegateProvider the argument provider used to create new argument variables
	 * @param aggregateGenerator factory for the aggregate buffer, or null to disable aggregation
	 */
	public MemoryDataArgumentMap(ComputeContext<MemoryData> context, ArgumentProvider delegateProvider,
								 IntFunction<MemoryData> aggregateGenerator) {
		super(delegateProvider);
		this.mems = new HashMap<>();
		this.rootDelegateSuppliers = new ArrayList<>();
		this.context = context;
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

			// Kernel-owned memory is never aggregated and outlives this map (see KernelConstantProviderSupplier)
			boolean kernelOwned = key instanceof KernelConstantProviderSupplier;

			// If aggregation is enabled and this root is small enough, fold it into the
			// shared aggregate buffer instead of giving it its own kernel argument.
			if (aggregateGenerator != null && !kernelOwned && isAggregationTarget(md.getRootDelegate())) {
				var = aggregate(createDelegate(md), md.getRootDelegate());
			}

			if (var == null) {
				RootDelegateProviderSupplier root = kernelOwned
						? new RootDelegateProviderSupplier(md) : createDelegate(md);
				var = delegateProvider.getArgument(root, null, -1);
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
	 * <p>The root occupies its {@link MemoryData#getDelegatedLength() delegated length} in the
	 * aggregate: that is the memory it actually has, which is less than its logical
	 * {@link MemoryData#getMemLength() length} when an ordering stores fewer elements than it
	 * addresses (a compact or sparse collection). Kernels reach the elements through that
	 * ordering, so only the backing memory needs a place in the aggregate.</p>
	 *
	 * @param key  Supplier identifying the root delegate argument being created
	 * @param root The root delegate {@link MemoryData} to aggregate
	 * @return Argument variable delegating into the aggregate, or null if it cannot be aggregated
	 */
	private ArrayVariable aggregate(Supplier key, MemoryData root) {
		int pos = aggregateLength;
		int length = root.getDelegatedLength();

		long tot = pos + (long) length;
		if (tot > Integer.MAX_VALUE) {
			// The aggregate would overflow; leave this argument on its own
			return null;
		}

		// Record the root and its position so the data can be copied into and out of
		// the aggregate around kernel execution.
		replacements.add(new Replacement(root, pos));
		aggregateLength += length;

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

	/**
	 * Returns the replacements, by index, whose slice of the aggregate the code {@code kernel}
	 * generates may write.
	 *
	 * <p>Every argument this map folded is referenced in {@code kernel} as the aggregate argument
	 * at the replacement's position (a reference to a view is created as a reference to the
	 * storage it views), so whether a slice is written is a question about the elements of the
	 * aggregate the kernel {@link Scope#containsAssignment(ArrayVariable, long, long) contains an
	 * assignment to}. Nothing is declared by the computation the kernel was compiled from: the scope is the
	 * code that runs, so it is the only account of what the kernel writes, and a scope whose writes
	 * cannot be enumerated reports every slice.</p>
	 *
	 * @param kernel the compiled scope whose arguments this map prepared
	 * @return the indices of the replacements whose slice {@code kernel} may write
	 */
	public BitSet getWrittenSlices(Scope<?> kernel) {
		if (replacements.isEmpty()) return new BitSet();

		ArrayVariable<?> aggregate = getAggregateArgument();
		return getSlices((start, end) -> kernel.containsAssignment(aggregate, start, end));
	}

	/**
	 * Returns the replacements, by index, whose slice of the aggregate the code {@code kernel}
	 * generates may read.
	 *
	 * <p>This is the counterpart of {@link #getWrittenSlices(Scope)}, answered the same way: by
	 * the elements of the aggregate the kernel {@link Scope#containsRead(ArrayVariable, long, long)
	 * reads}, never by anything the computation declares. A slice that is both written and read is
	 * read, and a scope whose reads cannot be enumerated reports every slice.</p>
	 *
	 * <p>The reads of the aggregate are {@link Scope#getReadPositions gathered} in a single walk of the
	 * scope and every slice is then tested against them, so the cost of the walk, which visits
	 * every expression the kernel evaluates, does not grow with the number of slices.</p>
	 *
	 * @param kernel the compiled scope whose arguments this map prepared
	 * @return the indices of the replacements whose slice {@code kernel} may read
	 */
	public BitSet getReadSlices(Scope<?> kernel) {
		if (replacements.isEmpty()) return new BitSet();

		return getSlices(kernel.getReadPositions(getAggregateArgument())::contains);
	}

	/**
	 * Returns the replacements, by index, whose slice of the aggregate satisfies {@code test},
	 * which is given the half-open range of aggregate positions the slice occupies.
	 *
	 * @param test whether the slice spanning a range of aggregate positions is selected
	 * @return the indices of the selected replacements
	 */
	private BitSet getSlices(BiPredicate<Long, Long> test) {
		BitSet slices = new BitSet(replacements.size());

		for (int i = 0; i < replacements.size(); i++) {
			Replacement r = replacements.get(i);
			long start = r.getPosition();
			if (test.test(start, start + r.getRoot().getDelegatedLength())) {
				slices.set(i);
			}
		}

		return slices;
	}

	/**
	 * Limits the copy-back after each dispatch to the slices {@code kernel}, the scope compiled
	 * against this map, {@link #getWrittenSlices(Scope) may write}, leaving out the staged copies
	 * of arguments it only reads, which are unchanged and need no transfer. It must be called
	 * before the first dispatch, because the copy-back is built once.
	 *
	 * @param kernel the compiled scope whose arguments this map prepared
	 * @return the indices of the replacements whose slice {@code kernel} may write, for recording
	 *         alongside the compiled kernel so an operation reusing it can
	 *         {@link #setWrittenSlices(BitSet) limit its own copy-back} the same way
	 * @throws IllegalStateException if the copy-back has already been built
	 */
	public BitSet limitCopyBackTo(Scope<?> kernel) {
		BitSet written = getWrittenSlices(kernel);
		setWrittenSlices(written);
		return written;
	}

	/**
	 * Limits the copy-back after each dispatch to the given slices, for a map preparing arguments
	 * for a kernel that was compiled against a different map.
	 *
	 * <p>This map does not hold the compiled scope in that case, so it cannot read the written
	 * slices off it; {@code written} is what {@link #limitCopyBackTo(Scope)} reported when the
	 * kernel was compiled, whose replacement positions this map was verified to reproduce. It must
	 * be given before the first dispatch, because the copy-back is built once.</p>
	 *
	 * @param written the indices of the replacements whose slice the kernel may write
	 * @throws IllegalStateException if the copy-back has already been built
	 */
	public synchronized void setWrittenSlices(BitSet written) {
		if (sliceCopies != null) {
			throw new IllegalStateException("The copy-back has already been built without the written slices");
		}

		this.writtenSlices = written.get(0, written.length());
	}

	/**
	 * Limits the copy-in before each dispatch to the slices {@code kernel}, the scope compiled
	 * against this map, {@link #getReadSlices(Scope) may read}, as far as that is safe (see
	 * {@link #getPrepareOperations(boolean)}). It must be called before the first dispatch,
	 * because the copy-in is built once.
	 *
	 * @param kernel the compiled scope whose arguments this map prepared
	 * @return the indices of the replacements whose slice {@code kernel} may read, for recording
	 *         alongside the compiled kernel so an operation reusing it can
	 *         {@link #setReadSlices(BitSet) limit its own copy-in} the same way
	 * @throws IllegalStateException if the copy-in has already been built
	 */
	public BitSet limitCopyInTo(Scope<?> kernel) {
		BitSet read = getReadSlices(kernel);
		setReadSlices(read);
		return read;
	}

	/**
	 * Limits the copy-in before each dispatch to the given slices, for a map preparing arguments
	 * for a kernel that was compiled against a different map; {@code read} is what
	 * {@link #limitCopyInTo(Scope)} reported when the kernel was compiled, on the same terms as
	 * {@link #setWrittenSlices(BitSet)}. It must be given before the first dispatch, because the
	 * copy-in is built once.
	 *
	 * @param read the indices of the replacements whose slice the kernel may read
	 * @throws IllegalStateException if the copy-in has already been built
	 */
	public synchronized void setReadSlices(BitSet read) {
		if (sliceCopies != null) {
			throw new IllegalStateException("The copy-in has already been built without the read slices");
		}

		this.readSlices = read.get(0, read.length());
	}

	/**
	 * Limits both the copy-back and the copy-in to what {@code kernel}, the scope compiled against
	 * this map, may write and read (see {@link #limitCopyBackTo(Scope)} and
	 * {@link #limitCopyInTo(Scope)}). It must be called before the first dispatch.
	 *
	 * @param kernel the compiled scope whose arguments this map prepared
	 * @return the layout of the compiled kernel's aggregate, for recording alongside the compiled
	 *         kernel so an operation reusing it can {@link #limitCopiesTo(AggregateLayout) limit its
	 *         own copies} the same way
	 * @throws IllegalStateException if the copies have already been built
	 */
	public AggregateLayout limitCopiesTo(Scope<?> kernel) {
		return new AggregateLayout(describeAggregatePositions(),
				limitCopyBackTo(kernel), limitCopyInTo(kernel));
	}

	/**
	 * Limits both the copy-back and the copy-in to the slices a kernel compiled against a different
	 * map may write and read, as {@link #limitCopiesTo(Scope)} reported them when it was compiled
	 * (see {@link #setWrittenSlices(BitSet)} and {@link #setReadSlices(BitSet)}). This map must
	 * {@link #reproduces(AggregateLayout) reproduce} the layout's positions, and the slices must be
	 * given before the first dispatch.
	 *
	 * @param compiled the layout of the compiled kernel's aggregate
	 * @throws IllegalStateException if the copies have already been built
	 */
	public void limitCopiesTo(AggregateLayout compiled) {
		setWrittenSlices(compiled.getWrittenSlices());
		setReadSlices(compiled.getReadSlices());
	}

	/**
	 * Returns whether this map folded its arguments at exactly the replacement positions the
	 * kernel described by {@code compiled} addresses, which is what reusing that kernel requires
	 * (see {@link #describeAggregatePositions()}).
	 *
	 * @param compiled the layout of the compiled kernel's aggregate
	 * @return true if this map's replacement positions match the compiled kernel's
	 */
	public boolean reproduces(AggregateLayout compiled) {
		return describeAggregatePositions().equals(compiled.getPositions());
	}

	/** Returns the total number of elements folded into the aggregate buffer. */
	public int getAggregateLength() { return aggregateLength; }

	/** Returns the number of root delegates folded into the aggregate buffer. */
	public int getFoldedArgumentCount() { return replacements.size(); }

	/** Returns a compact description of the aggregate layout (per-replacement offset:length). */
	public String describeAggregate() {
		StringBuilder b = new StringBuilder("len=").append(aggregateLength).append(" [");
		for (Replacement r : replacements) {
			b.append(r.getPosition()).append(':').append(r.getRoot().getDelegatedLength()).append(',');
		}
		return b.append(']').toString();
	}

	/**
	 * Returns the sequence of replacement positions within the aggregate — the component of the
	 * aggregate layout that is baked into a compiled kernel.
	 *
	 * <p>Folded arguments do not occupy their own kernel parameters (reducing parameter count is
	 * the purpose of aggregation), so the generated source addresses each one at its fixed
	 * position within the single aggregate parameter. Those positions are therefore part of the
	 * compiled kernel's identity. Per-argument element counts are not: they travel with each
	 * dispatch (see the offset and size values marshaled per execution), which is what allows a
	 * deliberately size-generic kernel — such as a single-statement assignment dispatched over
	 * its count — to serve computations of different sizes. Two computations may reuse one
	 * compiled kernel only when their position sequences are identical; a final replacement of a
	 * different length shifts no baked position and is compatible, while any difference in an
	 * earlier length shifts the positions after it and is not.</p>
	 *
	 * @return The baked replacement positions, in fold order
	 */
	public String describeAggregatePositions() {
		StringBuilder b = new StringBuilder("[");
		for (Replacement r : replacements) {
			b.append(r.getPosition()).append(',');
		}
		return b.append(']').toString();
	}

	/**
	 * Returns the copy operations to submit before each dispatch, which move the aggregated roots
	 * into the aggregate buffer, selected from the {@link SliceCopy copy of every slice}. Submitting
	 * them with {@link Submittable#submit(List, Semaphore)} yields the completion the kernel is chained
	 * on.
	 *
	 * <p>Each copy runs through the serving {@link io.almostrealism.code.ComputeContext}'s
	 * {@link io.almostrealism.code.ComputeContext#copy(io.almostrealism.code.Memory, int, io.almostrealism.code.Memory, int, int) copy}
	 * (see {@link #copyOperation}), so the context chooses how to move the memory it owns (a direct
	 * {@code setMem} by default). The operations are built once and reused (see
	 * {@link #ensureCopyOperations()}).</p>
	 *
	 * <p>A slice that is {@link SliceCopy#isCopiedOnce() copied once} is not copied by every submission.
	 * Its copy-in is an {@link OnceSubmittable}, owned by its {@link SliceCopy} and so shared by
	 * every dispatch, which issues the copy the first time it is submitted and nothing on every
	 * later submission, while each submission, whichever dispatch makes it and on whichever thread,
	 * still returns a completion covering both its dependency and that copy until it is known to
	 * have completed. No kernel can therefore read the aggregate before the copied-once slices hold
	 * their values, even when dispatches are prepared concurrently.</p>
	 *
	 * <p>Such a copy is submitted {@link Submittable#unordered() unordered}, with no dependency. That
	 * is what lets a context that cannot move a read-only source itself (a Metal context reading a
	 * file-backed asset, for example) make the copy at once rather than complete it on the host
	 * after the work ahead of the dispatch, and it is safe for the reasons
	 * {@link MemoryReplacementManager} stages a read-only argument the same way: no work produces a
	 * read-only source, and no kernel uses the aggregate before the first dispatch, which is ordered
	 * after these copies.</p>
	 *
	 * <p>A slice the kernel is known not to read (see {@link #setReadSlices(BitSet)}) is left out
	 * when nothing would observe its staged value: always when it is not {@link SliceCopy#isCopiedBack()
	 * copied back}, and otherwise for a dispatch that does not copy back ({@code copyBack} false).
	 * A dispatch that copies back keeps the copy-in of every slice it copies back, because the
	 * kernel is not known to write every element of it (see {@link SliceCopy#isCopiedIn(boolean)}).</p>
	 *
	 * @param copyBack whether the dispatch submits the {@link #getPostprocessOperations(MemoryData)
	 *                 copy-back} after the kernel
	 * @return the pre-execution copy operations, in replacement order
	 */
	public List<Submittable> getPrepareOperations(boolean copyBack) {
		return ensureCopyOperations().stream()
				.filter(copy -> copy.isCopiedIn(copyBack))
				.map(SliceCopy::getCopyIn)
				.collect(Collectors.toUnmodifiableList());
	}

	/**
	 * Returns the copy operations to submit before a dispatch that copies back afterwards (see
	 * {@link #getPrepareOperations(boolean)}).
	 *
	 * @return the pre-execution copy operations for a dispatch that copies back
	 */
	public List<Submittable> getPrepareOperations() {
		return getPrepareOperations(true);
	}

	/**
	 * Returns the copy operations that move each aggregated slice back to its source after kernel
	 * execution, omitting any slice that must not be written.
	 *
	 * <p>Three kinds are omitted. A slice whose source shares memory with {@code skipOutput} is left
	 * out to avoid writing a stale read-copy over the buffer the kernel was told to use as its
	 * explicit output (the in-place {@code x = x + y} case, where the aggregated read copy of
	 * {@code x} must not overwrite the freshly written {@code x}). A slice whose source is read-only
	 * is left out because it cannot be written and has no result to carry — the kernel read it and
	 * nothing changed it. And a slice the kernel cannot write (see
	 * {@link #setWrittenSlices(BitSet)}) is left out for the same reason: its staged copy is still
	 * the source's own contents (see {@link SliceCopy#isCopiedBack()}).</p>
	 *
	 * @param skipOutput a buffer whose memory is excluded from copy-back, or null to exclude only
	 *                   what cannot be written
	 * @return the post-execution copy operations, in replacement order
	 */
	public List<Submittable> getPostprocessOperations(MemoryData skipOutput) {
		Memory skip = skipOutput == null ? null : skipOutput.getRootDelegate().getMem();
		return ensureCopyOperations().stream()
				.filter(copy -> copy.isCopiedBack() && !copy.sharesMemory(skip))
				.map(SliceCopy::getCopyOut)
				.collect(Collectors.toUnmodifiableList());
	}

	/**
	 * Builds the {@link SliceCopy} of every replacement, once, and returns them.
	 *
	 * <p>Each copy binds fixed memory — the replacement's root and a {@link Bytes} view of the
	 * aggregate at the replacement's offset — so a single built operation is valid for the lifetime
	 * of this map and is reused across every {@code apply} rather than rebuilt per dispatch.
	 * Neither whether a source is read-only nor which slices the kernel reads and writes can change
	 * once the copies are built, so each {@link SliceCopy} records them when it is created.</p>
	 *
	 * <p>A copy moves a root's backing memory, its {@link MemoryData#getDelegatedLength() delegated
	 * length}; a root whose logical length differs from it (smaller for a gather ordering,
	 * larger for a compact or sparse one) is copied through a view of exactly that memory,
	 * because a copy transfers the logical length of its source.</p>
	 *
	 * <p>A dispatch may be prepared on any thread (see
	 * {@link org.almostrealism.hardware.AcceleratedOperation#apply}), so the lists are built under
	 * this map's lock.</p>
	 *
	 * @return the copy of every replacement's slice, in replacement order
	 */
	private synchronized List<SliceCopy> ensureCopyOperations() {
		if (sliceCopies != null) {
			return sliceCopies;
		}

		List<SliceCopy> copies = new ArrayList<>(replacements.size());

		for (int i = 0; i < replacements.size(); i++) {
			Replacement r = replacements.get(i);
			MemoryData root = r.getRoot();
			int len = root.getDelegatedLength();
			MemoryData backing = len == root.getMemLength() ? root : new Bytes(len, root, 0);
			Bytes slice = new Bytes(len, getAggregateData(), r.getPosition());

			copies.add(new SliceCopy(root,
					copyOperation(backing, slice, false), copyOperation(slice, backing, true),
					readSlices == null || readSlices.get(i),
					writtenSlices == null || writtenSlices.get(i)));
		}

		sliceCopies = Collections.unmodifiableList(copies);
		return sliceCopies;
	}

	/**
	 * Builds a {@link Submittable} that copies all of {@code source} into {@code target} through the
	 * serving {@link #context}'s
	 * {@link ComputeContext#copy(Object, Object, io.almostrealism.streams.Semaphore) copy}, letting
	 * the context choose the mechanism and the sequencing: a batching context (e.g. Metal) queues the
	 * copy onto its command buffer, orders it after {@code dependsOn}, and returns a pending completion
	 * semaphore; any other context waits for {@code dependsOn}, performs a direct copy, and returns
	 * {@code null}. The submit's completion is that returned semaphore, so callers wait on the actual
	 * end of the copy.
	 *
	 * <p>The {@link MemoryData} regions (and therefore their offsets) are passed on every call, so a
	 * copy into one aggregate slice is never confused with a copy into another &mdash; the key
	 * difference from a compiled copy kernel, whose signature-keyed reuse could bind the wrong
	 * destination offset when one program served aggregate slices at different positions.</p>
	 *
	 * <p>Each copy that is issued is reported to the
	 * {@link org.almostrealism.hardware.AcceleratedOperation#bindingListener}, if there is one.</p>
	 *
	 * @param source   the memory to copy from
	 * @param target   the memory to copy into
	 * @param copyBack whether this is the copy from the aggregate back to the input
	 * @return a submittable copy operation
	 */
	private Submittable copyOperation(MemoryData source, MemoryData target, boolean copyBack) {
		return dependsOn -> {
			KernelBindingListener listener = AcceleratedOperation.bindingListener;
			if (listener != null) listener.aggregateCopied(copyBack, source.getMemLength());
			return context.copy(source, target, dependsOn);
		};
	}

	@Override
	public void destroy() {
		super.destroy();
		// The copies hold no resources of their own: they are views of the
		// roots and of the aggregate, which is destroyed below
		sliceCopies = null;
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
	 * semantic reason and break instruction-set reuse. Size eligibility is necessary but not
	 * sufficient: memory requested through {@link KernelConstantProviderSupplier} is owned by
	 * the compiled kernel itself and is never folded, whatever its size.</p>
	 *
	 * @param md Memory data to test
	 * @return True if the memory data can be folded into an aggregate argument
	 */
	public static boolean isAggregationTarget(MemoryData md) {
		return enableArgumentAggregation && md != null && md.getMem() != null
				&& md.getMemLength() <= maxAggregateLength;
	}

	/**
	 * Returns true if the argument supplied by the given producer would be folded into the
	 * aggregate argument of a kernel that accepts aggregation, applying the rule
	 * {@link #get(Supplier)} uses when the argument is mapped: the supplied {@link Provider}
	 * must hold {@link MemoryData} whose root memory is an
	 * {@link #isAggregationTarget(MemoryData) aggregation target}, and the producer must not be
	 * kernel-owned constant memory ({@link KernelConstantProviderSupplier}).
	 *
	 * <p>Unlike {@link #get(Supplier)}, which is called during compilation and may obtain the
	 * evaluable of any supplier, this method only inspects producers that declare themselves
	 * {@link io.almostrealism.relation.Computable#provider(Object) providers}
	 * (see {@link Provider#valueOf(Supplier, Class)}); any other producer yields {@code false}. It is
	 * meant to be called before compilation, for example from a signature, where obtaining the
	 * evaluable of an arbitrary producer could compile it. A supplier that returns a
	 * {@link Provider} without declaring itself one (such as a lambda) has no signature, so a
	 * computation that uses it is never matched against the instruction cache in the first
	 * place.</p>
	 *
	 * <p>Because the answer is fixed by the provided memory, it is known before compilation,
	 * so a computation whose compiled kernel depends on it (for example a size-generic
	 * assignment, whose signature otherwise omits its destination) can fold it into its
	 * signature.</p>
	 *
	 * @param producer the producer of the argument
	 * @return true if the argument is a provider whose memory would be folded into the aggregate
	 */
	public static boolean isAggregationTarget(Supplier<?> producer) {
		if (producer instanceof KernelConstantProviderSupplier) return false;

		MemoryData value = Provider.valueOf(producer, MemoryData.class);
		return value != null && isAggregationTarget(value.getRootDelegate());
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
	 * Creates and configures a {@link MemoryDataArgumentMap} with the appropriate delegate provider,
	 * bound to the {@link ComputeContext} of the operation it prepares arguments for.
	 *
	 * @param context the {@link ComputeContext} the aggregate copies run through
	 * @param aggregateGenerator factory for the aggregate buffer, or null to disable aggregation
	 * @return Fully configured {@link MemoryDataArgumentMap}
	 */
	public static MemoryDataArgumentMap create(ComputeContext<MemoryData> context,
											   IntFunction<MemoryData> aggregateGenerator) {
		return new MemoryDataArgumentMap(context,
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

	/**
	 * The copies that move one replacement's slice between its source and the aggregate, together
	 * with what decides which dispatches submit them: whether the source is read-only, and whether
	 * the kernel may read or write the slice. Both are fixed once the copies are built.
	 */
	private static class SliceCopy {
		/** The root delegate whose slice is copied. */
		private final MemoryData root;
		/**
		 * Copies the root into its slice of the aggregate; for a slice that is
		 * {@link #isCopiedOnce() copied once}, an {@link OnceSubmittable} submitted
		 * {@link Submittable#unordered() unordered}, so only its first submission copies.
		 */
		private final Submittable copyIn;
		/** Copies the slice of the aggregate back to the root. */
		private final Submittable copyOut;
		/** Whether the kernel may read the slice (true while that is unknown). */
		private final boolean read;
		/** Whether the kernel may write the slice (true while that is unknown). */
		private final boolean written;

		/**
		 * Creates the copies of a slice.
		 *
		 * @param root    the root delegate whose slice is copied
		 * @param copyIn  copies the root into its slice of the aggregate
		 * @param copyOut copies the slice of the aggregate back to the root
		 * @param read    whether the kernel may read the slice
		 * @param written whether the kernel may write the slice
		 */
		SliceCopy(MemoryData root, Submittable copyIn, Submittable copyOut,
				  boolean read, boolean written) {
			this.root = root;
			this.copyOut = copyOut;
			this.read = read;
			this.written = written;
			this.copyIn = isCopiedOnce()
					? new OnceSubmittable(List.of(copyIn)).unordered() : copyIn;
		}

		/** Returns the copy of the root into its slice of the aggregate. */
		Submittable getCopyIn() { return copyIn; }

		/** Returns the copy of the slice of the aggregate back to the root. */
		Submittable getCopyOut() { return copyOut; }

		/**
		 * Returns whether the slice must be staged in the aggregate before a dispatch: the kernel
		 * may read it, or it is {@link #isCopiedBack() copied back} after a dispatch that copies
		 * back.
		 *
		 * <p>A slice copied back needs its copy-in even when the kernel does not read it, because
		 * nothing shows that the kernel writes every element of it: the copy-back moves the whole
		 * slice, so an element the kernel left alone must still hold the source's own value, or the
		 * copy-back would overwrite the source with whatever the aggregate held before.</p>
		 *
		 * @param copyBack whether the dispatch copies the written slices back afterwards
		 * @return true if the slice is copied in
		 */
		boolean isCopiedIn(boolean copyBack) {
			return read || (copyBack && isCopiedBack());
		}

		/**
		 * Returns whether the slice is copied back after a dispatch: its source can be written,
		 * and the kernel may have written the slice.
		 *
		 * @return true if the slice is copied back
		 */
		boolean isCopiedBack() {
			return written && !root.isReadOnly();
		}

		/**
		 * Returns whether the slice is copied in by the first dispatch only, rather than before
		 * every dispatch: its source is read-only, so its contents cannot change between
		 * dispatches, and the kernel is known not to write the slice, so the aggregate keeps the
		 * copied value. A slice the kernel does not read is not copied in at all.
		 *
		 * @return true if the slice is copied in once
		 */
		boolean isCopiedOnce() {
			return read && !written && root.isReadOnly();
		}

		/**
		 * Returns whether the root shares the given memory, which a dispatch writing that memory as
		 * its explicit output must not copy back over.
		 *
		 * @param mem the memory in question, or null
		 * @return true if the root's storage is {@code mem}
		 */
		boolean sharesMemory(Memory mem) {
			return mem != null && root.getRootDelegate().getMem() == mem;
		}
	}

	/**
	 * {@link Producer} that provides the lazily created aggregate buffer to the kernel.
	 *
	 * <p>Requesting the buffer from a map that aggregated nothing is a contract violation:
	 * a kernel argument bound to a null buffer can never be satisfied, so the request
	 * throws instead of delivering null. Reaching this failure means an operation was bound
	 * to an aggregate it does not actually have — the symptom of an instruction cache
	 * collision that upstream verification failed to catch.</p>
	 */
	private class AggregateProducer implements Producer<MemoryData> {
		@Override
		public Evaluable<MemoryData> get() {
			MemoryData data = getAggregateData();
			if (data == null) {
				throw new HardwareException(
						"Aggregate buffer requested from an argument map that aggregated nothing (" +
						describeAggregate() + ")");
			}

			return new Provider<>(data);
		}
	}
}
