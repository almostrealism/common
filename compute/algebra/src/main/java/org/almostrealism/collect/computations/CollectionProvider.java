/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.collect.computations;

import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;
import io.almostrealism.uml.Multiple;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.MemoryDataCopy;

import java.util.Objects;

/**
 * A specialized {@link Provider} for {@link PackedCollection}s that provides efficient
 * memory copying and destination buffer management.
 *
 * <p>This class extends {@link Provider} to handle {@link PackedCollection} values with
 * optimized memory operations. Unlike general providers, {@link CollectionProvider} uses
 * {@link MemoryDataCopy} for efficient hardware-accelerated copying of collection data
 * to destination buffers.</p>
 *
 * <h2>Purpose and Usage</h2>
 * <p>CollectionProvider serves as a constant source of {@link PackedCollection} data
 * in computational graphs, with the ability to efficiently copy its value into output
 * buffers. It's commonly used for:</p>
 * <ul>
 *   <li><strong>Constant Data Sources:</strong> Providing pre-computed or static collections</li>
 *   <li><strong>Parameter Storage:</strong> Holding model weights, biases, or configuration data</li>
 *   <li><strong>Initialization Values:</strong> Supplying initial states for iterative computations</li>
 *   <li><strong>Testing/Debugging:</strong> Providing known input data for validation</li>
 * </ul>
 *
 * <h2>Memory Efficiency</h2>
 * <p>The {@link #into(Object)} method creates a {@link MemoryDataCopy} operation that
 * performs direct memory transfer between the source collection and destination buffer.
 * This avoids unnecessary intermediate copies and enables hardware-accelerated data movement.</p>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Providing constant data to a computation:</strong></p>
 * <pre>{@code
 * PackedCollection weights = new PackedCollection(shape(10, 5));
 * weights.randFill();
 *
 * CollectionProvider<PackedCollection> weightsProvider = new CollectionProvider<>(weights);
 *
 * // Use in a computation graph
 * CollectionProducer<?> result = input.multiply(weightsProvider);
 * }</pre>
 *
 * <p><strong>Creating destination buffers:</strong></p>
 * <pre>{@code
 * CollectionProvider<PackedCollection> provider = new CollectionProvider<>(sourceData);
 * Multiple<PackedCollection> destination = provider.createDestination(batchSize);
 * // Destination has same shape as source data
 * }</pre>
 *
 * <p><strong>Efficient copying into existing buffer:</strong></p>
 * <pre>{@code
 * PackedCollection source = new PackedCollection(shape(100));
 * source.fill(pos -> pos[0]);
 *
 * CollectionProvider<PackedCollection> provider = new CollectionProvider<>(source);
 * PackedCollection destination = new PackedCollection(shape(100));
 *
 * Evaluable<PackedCollection> copyOp = provider.into(destination);
 * copyOp.evaluate();  // Efficiently copies source to destination
 * }</pre>
 *
 * <h2>Comparison with Related Classes</h2>
 * <ul>
 *   <li><strong>CollectionProvider:</strong> Provides existing collection with efficient copying</li>
 *   <li><strong>{@link CollectionProviderProducer}:</strong> Producer wrapper around CollectionProvider</li>
 *   <li><strong>{@link CollectionConstantComputation}:</strong> Generates constant values (not stored)</li>
 *   <li><strong>Provider:</strong> Generic provider for any value type</li>
 * </ul>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li><strong>Memory:</strong> Stores one copy of the source collection</li>
 *   <li><strong>Copy Operation:</strong> O(n) using {@link MemoryDataCopy} (hardware-accelerated)</li>
 *   <li><strong>Destination Creation:</strong> O(1) allocation (data copied on evaluation)</li>
 *   <li><strong>Thread Safety:</strong> Safe for concurrent reads if source collection is immutable</li>
 * </ul>
 *
 * @param <T> The type of {@link PackedCollection} this provider supplies
 *
 * @see Provider
 * @see CollectionProviderProducer
 * @see MemoryDataCopy
 * @see PackedCollection
 *
 * @author Michael Murray
 */
public class CollectionProvider<T extends PackedCollection> extends Provider<T> implements CollectionFeatures {
	/**
	 * Constructs a provider that supplies the specified {@link PackedCollection}.
	 *
	 * <p>The provided value is stored and will be returned by {@link #get()} or copied
	 * to destination buffers by {@link #into(Object)}. The value should typically be
	 * treated as immutable once provided to ensure thread-safe operation.</p>
	 *
	 * @param value The {@link PackedCollection} to provide
	 */
	public CollectionProvider(T value) {
		super(value);
	}

	/**
	 * Creates a destination buffer for receiving the provided collection data.
	 *
	 * <p>This method creates a new {@link PackedCollection} (wrapped in a {@link Multiple})
	 * with the same shape as the source collection. The size parameter is ignored, as the
	 * destination shape is always determined by the source collection's shape.</p>
	 *
	 * <p>The returned destination is an empty buffer ready to receive data via the
	 * {@link #into(Object)} method.</p>
	 *
	 * @param size The requested size (ignored - shape determined by source collection)
	 * @return A {@link Multiple} containing a new {@link PackedCollection} with matching shape
	 */
	@Override
	public Multiple<T> createDestination(int size) {
		return (Multiple<T>) new PackedCollection(shape(get()));
	}

	/**
	 * Creates an {@link Evaluable} that efficiently copies the provided collection into
	 * the specified destination buffer.
	 *
	 * <p>This method uses {@link MemoryDataCopy} to perform hardware-accelerated memory
	 * transfer from the source collection to the destination. The copy operation is
	 * created once and can be evaluated multiple times efficiently.</p>
	 *
	 * <p>The destination must be a {@link MemoryData} object (such as {@link PackedCollection})
	 * with sufficient capacity to hold the source collection's data. Because the copy moves
	 * backing memory, the number of elements transferred is the source policy's
	 * {@link TraversalPolicy#getTotalInputSize() total input size} — one element per input
	 * position — rather than its logical {@link TraversalPolicy#getTotalSize() total size};
	 * the two agree for a regular shape or a permutation, but a rated or repeated view has
	 * fewer input elements than it reports as its total size.</p>
	 *
	 * <p><strong>Performance:</strong> The copy operation uses direct memory transfer,
	 * avoiding intermediate buffers and enabling efficient data movement even for large
	 * collections.</p>
	 *
	 * <p><strong>The copy is flat.</strong> It moves the source's backing memory, so it reproduces
	 * the source's values only where the source reads its memory in order. A provider over a view —
	 * a collection whose indices map onto someone else's buffer, as a permuted shape, an explicit
	 * {@link io.almostrealism.collect.TraversalOrdering}, or an ordering inherited from a delegate
	 * does — is refused rather than copied, because a flat copy of such a source silently yields
	 * backing-memory order and loses the mapping. A regular shape can still carry such an ordering
	 * through its {@link MemoryData#getMemOrdering() memory ordering}, so that is inspected in
	 * addition to the shape. A destination carrying the identical mapping is accepted, since the
	 * same mapping applied to both sides preserves the correspondence; identity here means matching
	 * the traversal ordering and the memory ordering as well as the shape, because
	 * {@link TraversalPolicy#equals(Object)} compares the dimensions, dimension order, rates, and
	 * traversal axis, but not the traversal ordering.</p>
	 *
	 * <p>To move a view's values into regular memory, apply the reordering as a computation rather
	 * than referencing the view: the permutation then runs as a kernel that writes each destination
	 * position, which is what reorders the memory.</p>
	 *
	 * @param destination The destination {@link MemoryData} buffer (must be a {@link MemoryData} instance)
	 * @return An {@link Evaluable} that performs the copy and returns the destination
	 * @throws ClassCastException       if destination is not a {@link MemoryData} instance
	 * @throws IllegalArgumentException if the source is a view whose mapping the destination does
	 *                                  not share, so a flat copy would discard it
	 */
	@Override
	public Evaluable<T> into(Object destination) {
		T value = get();
		TraversalPolicy shape = shape(value);
		TraversalPolicy target = destination instanceof Shape ? ((Shape) destination).getShape() : null;

		// a regular shape can still inherit a memory ordering from a delegate; equals compares
		// neither that nor the traversal ordering, so both are matched explicitly
		TraversalOrdering sourceOrder = value.getMemOrdering();
		TraversalOrdering targetOrder = destination instanceof MemoryData ?
				((MemoryData) destination).getMemOrdering() : null;
		boolean sourceViewsMemory = !shape.isRegular() || sourceOrder != null;
		boolean sharesMapping = shape.equals(target) &&
				Objects.equals(shape.getOrder(), target.getOrder()) &&
				Objects.equals(sourceOrder, targetOrder);

		if (sourceViewsMemory && !sharesMapping) {
			throw new IllegalArgumentException("A provider of " + shape + " views other memory, so " +
					"copying it into a destination of a different layout would discard the mapping; " +
					"apply the reordering as a computation instead of referencing the view");
		}

		// the copy moves backing memory, so it transfers one element per input position
		Runnable copy = new MemoryDataCopy("CollectionProvider Evaluate Into",
				this::get, () -> (MemoryData) destination, shape.getTotalInputSize()).get();
		return args -> {
			copy.run();
			return (T) destination;
		};
	}

}
