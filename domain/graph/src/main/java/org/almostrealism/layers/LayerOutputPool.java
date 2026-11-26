/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.layers;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.hardware.mem.ExecutionStep;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The memory that holds the outputs of the {@link DefaultCellularLayer}s of one model compiled
 * for inference, shared between layers whose outputs are never live at the same time, the way a
 * compiler assigns registers.
 *
 * <h2>Use</h2>
 * <p>A compile that pools its layer outputs pushes its cells inside {@link #use(Supplier)}. Every
 * layer whose output has not been created yet, and is not {@link DefaultCellularLayer#retainOutput()
 * retained}, receives from {@link #allocate(DefaultCellularLayer)} a view at offset 0 of a block of
 * its own, so that the optimized forward list sees each layer on distinct memory exactly as it would
 * without pooling. The compile then passes the {@link ExecutionStep}s of the optimized list to
 * {@link #assign(List)}, which works out the interval of steps over which each output is live and
 * moves outputs whose intervals do not overlap onto shared blocks, before anything is compiled.</p>
 *
 * <h2>Why the analysed list stays valid</h2>
 * <p>Outputs are moved by re-pointing the very view objects the operations hold, so the optimized
 * list is not rebuilt. Two outputs share a block only when no step touches both, and a step is the
 * largest unit whose internal order cannot be relied on (a fused kernel). Every hazard
 * {@link org.almostrealism.hardware.OperationList#subdivide()} looks for is between members of one
 * list that would run in the same kernel, which is one step; so no hazard appears after the move
 * that was not present, and cut, when the list was optimized. Steps that run one after another keep
 * their order, and an output's memory is reused only after the last step of its interval.</p>
 *
 * <h2>What is not pooled</h2>
 * <ul>
 *   <li>Every output, when a step does not expose the memory it touches
 *       ({@link ExecutionStep#isResolvable()}); the compile keeps one block per layer.</li>
 *   <li>An output first touched by a step that does not write it: it would be read before it is
 *       written in the pass, which is to say its value crosses from one pass to the next.</li>
 *   <li>An output no step touches.</li>
 * </ul>
 *
 * <h2>Block sizes</h2>
 * <p>A block is never smaller than a tenant. An output small enough to be folded into an aggregate
 * kernel argument ({@link MemoryDataArgumentMap#maxAggregateLength}) shares only blocks of exactly
 * its own length, because aggregation eligibility and the aggregate signature read the length of the
 * root memory; a larger one takes the smallest free block that fits, or grows the largest free one.
 * Either way a view is at offset 0 with its own length, so the signatures of the kernels that read it
 * are those it would have without pooling.</p>
 *
 * @see DefaultCellularLayer#getOutput()
 * @author Michael Murray
 */
public class LayerOutputPool implements Destroyable, ConsoleFeatures {
	/** The pool in use for the compile running on the current thread. */
	private static final ThreadLocal<LayerOutputPool> current = new ThreadLocal<>();

	/** The layers given an output by this pool, in the order they were first pushed. */
	private final List<Tenant> tenants;

	/** The tenants by the view each was given, compared by identity. */
	private final Map<MemoryData, Tenant> tenantsByView;

	/** The memory this pool owns and releases when it is destroyed. */
	private final List<Bytes> memory;

	/** The number of tenants that share a block with another tenant. */
	private int sharedCount;

	/** The steps the last call to {@link #assign(List)} analysed, in the order they run. */
	private List<ExecutionStep> steps;

	/** Why the last call to {@link #assign(List)} kept one block per layer, or {@code null}. */
	private String fallbackReason;

	/**
	 * Creates an empty pool.
	 */
	public LayerOutputPool() {
		this.tenants = new ArrayList<>();
		this.tenantsByView = new IdentityHashMap<>();
		this.memory = new ArrayList<>();
		this.steps = List.of();
	}

	/**
	 * Returns the pool in use on the current thread, or {@code null} when no compile that pools
	 * its layer outputs is pushing cells.
	 *
	 * @return the current pool, or {@code null}
	 */
	public static LayerOutputPool getCurrent() { return current.get(); }

	/**
	 * Runs the given work with this pool in use on the current thread, so that layers pushed by
	 * it obtain their outputs from this pool, and restores the previous pool afterwards.
	 *
	 * @param work the work to run, typically pushing a model's cells and optimizing the result
	 * @param <T>  the type of the result of the work
	 * @return the result of the work
	 */
	public <T> T use(Supplier<T> work) {
		LayerOutputPool previous = current.get();
		current.set(this);

		try {
			return work.get();
		} finally {
			if (previous == null) {
				current.remove();
			} else {
				current.set(previous);
			}
		}
	}

	/**
	 * Creates the output of the given layer: a view at offset 0 of a block of memory of the
	 * {@link PackedCollection#physicalLength(TraversalPolicy) physical length} of the layer's
	 * output shape, which this pool owns.
	 *
	 * @param layer the layer whose output is needed
	 * @return the output view
	 */
	PackedCollection allocate(DefaultCellularLayer layer) {
		TraversalPolicy shape = layer.getOutputShape();
		Bytes block = new Bytes(Math.toIntExact(PackedCollection.physicalLength(shape)));
		memory.add(block);

		PackedCollection view = new PackedCollection(shape, shape.getTraversalAxis(), block, 0);
		Tenant tenant = new Tenant(layer, view, block);
		tenants.add(tenant);
		tenantsByView.put(view, tenant);
		return view;
	}

	/**
	 * Works out the interval of steps over which each output created by this pool is live and
	 * moves outputs whose intervals do not overlap onto shared blocks. The interval of an output
	 * runs from the first step that touches it to the last, inclusive.
	 *
	 * <p>When a step does not expose the memory it touches, nothing is moved, and
	 * {@link #getFallbackReason()} says why. The result is logged with {@code layerOutputBytes=}
	 * (the total of the layers' output lengths) and {@code pooledBytes=} (the memory this pool
	 * holds afterwards).</p>
	 *
	 * @param steps the steps of the optimized forward pass, in the order they run
	 * @return true if any output now shares memory with another
	 */
	public boolean assign(List<ExecutionStep> steps) {
		this.steps = steps;
		fallbackReason = null;

		for (ExecutionStep step : steps) {
			if (!step.isResolvable()) {
				fallbackReason = "step " + step.describe() + " does not expose the memory it touches";
				warn("Layer buffer reuse disabled: " + fallbackReason);
				return false;
			}
		}

		for (int i = 0; i < steps.size(); i++) {
			Set<Tenant> written = tenantsOf(steps.get(i).getWritten().getRegions());

			for (Tenant t : tenantsOf(steps.get(i).getTouched().getRegions())) {
				if (t.start < 0) {
					t.start = i;
					t.crossPass = !written.contains(t);
				}

				t.end = i;
			}
		}

		List<Tenant> candidates = new ArrayList<>();
		for (Tenant t : tenants) {
			if (t.start >= 0 && !t.crossPass) candidates.add(t);
		}

		candidates.sort(Comparator.comparingInt(t -> t.start));

		List<Block> blocks = new ArrayList<>();
		for (Tenant t : candidates) {
			place(t, blocks);
		}

		for (Block b : blocks) {
			if (b.tenants.size() < 2) continue;

			Bytes shared = new Bytes(b.length);
			memory.add(shared);

			for (Tenant t : b.tenants) {
				t.view.setDelegate(shared, 0);
				memory.remove(t.block);
				t.block.destroy();
				t.block = shared;
				t.layer.markOutputShared();
				sharedCount++;
			}
		}

		long crossPass = tenants.stream().filter(t -> t.crossPass).count();
		long untouched = tenants.stream().filter(t -> t.start < 0).count();
		log("steps=" + steps.size() + " layers=" + tenants.size() + " sharedLayers=" + sharedCount +
				" crossPassLayers=" + crossPass + " untouchedLayers=" + untouched +
				" layerOutputBytes=" + getLayerOutputBytes() + " pooledBytes=" + getPooledBytes());
		return sharedCount > 0;
	}

	/**
	 * Checks that none of the steps the last {@link #assign(List)} analysed touches two outputs
	 * that share memory, which that call guarantees by construction; this is a diagnostic for
	 * that guarantee.
	 *
	 * @throws IllegalStateException if a step touches two outputs on the same block
	 */
	public void verify() {
		for (ExecutionStep step : steps) {
			Map<Bytes, Tenant> seen = new IdentityHashMap<>();

			for (Tenant t : tenantsOf(step.getTouched().getRegions())) {
				Tenant other = seen.put(t.block, t);
				if (other != null && other != t) {
					throw new IllegalStateException("Step " + step.describe() + " touches the outputs of " +
							other.layer.getName() + " and " + t.layer.getName() + ", which share memory");
				}
			}
		}
	}

	/**
	 * Returns the number of layers given an output by this pool.
	 *
	 * @return the number of layers
	 */
	public int getLayerCount() { return tenants.size(); }

	/**
	 * Returns the number of layers whose output shares memory with another layer's.
	 *
	 * @return the number of sharing layers
	 */
	public int getSharedLayerCount() { return sharedCount; }

	/**
	 * Returns why the last {@link #assign(List)} kept one block per layer, or {@code null} when
	 * it did not fall back.
	 *
	 * @return the reason, or {@code null}
	 */
	public String getFallbackReason() { return fallbackReason; }

	/**
	 * Returns the memory the outputs of this pool's layers would occupy with one buffer each.
	 *
	 * @return the total output size in bytes
	 */
	public long getLayerOutputBytes() {
		long total = 0;
		for (Tenant t : tenants) total += t.view.getDelegatedLength();
		return total * elementBytes();
	}

	/**
	 * Returns the memory this pool holds for its layers' outputs.
	 *
	 * @return the pooled size in bytes
	 */
	public long getPooledBytes() {
		long total = 0;
		for (Bytes b : memory) total += b.getMemLength();
		return total * elementBytes();
	}

	/**
	 * Returns the tenants the given regions belong to: a region belongs to a tenant when it is
	 * the tenant's view or a view, at any depth, of that view.
	 *
	 * @param regions the regions to look up
	 * @return the tenants, in the order first found
	 */
	private Set<Tenant> tenantsOf(List<MemoryData> regions) {
		Set<Tenant> result = new LinkedHashSet<>();

		for (MemoryData region : regions) {
			for (MemoryData m = region; m != null; m = m.getDelegate()) {
				Tenant t = tenantsByView.get(m);
				if (t != null) {
					result.add(t);
					break;
				}
			}
		}

		return result;
	}

	/**
	 * Places a tenant on a block that is free from the tenant's first step, growing or creating
	 * a block as required; see the class documentation for the rules.
	 *
	 * @param t      the tenant to place
	 * @param blocks the blocks placed so far, to which a new block is added
	 */
	private void place(Tenant t, List<Block> blocks) {
		int length = t.view.getDelegatedLength();
		boolean small = length <= MemoryDataArgumentMap.maxAggregateLength;

		Block fit = null;
		Block largest = null;

		for (Block b : blocks) {
			if (b.end >= t.start || b.small != small) continue;

			if (small) {
				if (b.length == length) {
					fit = b;
					break;
				}
			} else if (b.length >= length) {
				if (fit == null || b.length < fit.length) fit = b;
			} else if (largest == null || b.length > largest.length) {
				largest = b;
			}
		}

		if (fit == null && largest != null) {
			largest.length = length;
			fit = largest;
		}

		if (fit == null) {
			fit = new Block(length, small);
			blocks.add(fit);
		}

		fit.tenants.add(t);
		fit.end = t.end;
	}

	/**
	 * Returns the size of one element of device memory.
	 *
	 * @return the number of bytes per element
	 */
	private static long elementBytes() {
		return Hardware.getLocalHardware().getPrecision().bytes();
	}

	/**
	 * Releases the memory this pool holds. The outputs of its layers are unusable afterwards.
	 */
	@Override
	public void destroy() {
		memory.forEach(Bytes::destroy);
		memory.clear();
	}

	@Override
	public Console console() { return LayerFeatures.console; }

	/** A layer given an output by this pool, with the interval over which that output is live. */
	private static final class Tenant {
		/** The layer. */
		private final DefaultCellularLayer layer;
		/** The output view the layer was given. */
		private final PackedCollection view;
		/** The block the view currently delegates to. */
		private Bytes block;
		/** The first step that touches the output, or -1. */
		private int start;
		/** The last step that touches the output, or -1. */
		private int end;
		/** Whether the first step that touches the output does not write it. */
		private boolean crossPass;

		/**
		 * Creates a tenant with no interval yet.
		 *
		 * @param layer the layer
		 * @param view  the output view the layer was given
		 * @param block the block the view delegates to
		 */
		private Tenant(DefaultCellularLayer layer, PackedCollection view, Bytes block) {
			this.layer = layer;
			this.view = view;
			this.block = block;
			this.start = -1;
			this.end = -1;
		}
	}

	/** A block of memory being assigned to tenants whose intervals do not overlap. */
	private static final class Block {
		/** The tenants placed on this block, in order of their intervals. */
		private final List<Tenant> tenants;
		/** Whether this block holds outputs small enough to be aggregated. */
		private final boolean small;
		/** The length of the block, at least that of every tenant. */
		private int length;
		/** The last step of the interval of the most recent tenant. */
		private int end;

		/**
		 * Creates an empty block.
		 *
		 * @param length the length of the block
		 * @param small  whether the block holds outputs small enough to be aggregated
		 */
		private Block(int length, boolean small) {
			this.tenants = new ArrayList<>();
			this.length = length;
			this.small = small;
			this.end = -1;
		}
	}
}
