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

package org.almostrealism.graph;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;

/**
 * A {@link Receptor} that writes received {@link PackedCollection} values into
 * a destination collection, optionally at a position determined by a producer.
 *
 * <p>When a position producer is provided, the destination must be a 2D collection
 * and incoming data is written into the row at the index given by the position
 * producer. When no position is provided, data is written from offset zero.</p>
 *
 * <p>An optional {@link Runnable} callback is invoked after each write, which
 * can be used for signaling or flushing.</p>
 *
 * @see Receptor
 * @author Michael Murray
 */
public class CollectionReceptor implements Receptor<PackedCollection> {
	/** The collection to write incoming data into. */
	private final PackedCollection dest;

	/** Optional producer for the row position within a 2D destination. */
	private final Producer<PackedCollection> pos;

	/** Optional callback invoked after each write. */
	private final Runnable r;

	/**
	 * Creates a receptor that writes all data to the beginning of the destination.
	 *
	 * @param dest the destination collection
	 */
	public CollectionReceptor(PackedCollection dest) {
		this(dest, null);
	}

	/**
	 * Creates a receptor that writes data at a position-controlled row in the destination.
	 *
	 * @param dest the destination collection (must be 2D if pos is non-null)
	 * @param pos  producer for the row index, or null to write from offset zero
	 */
	public CollectionReceptor(PackedCollection dest, Producer<PackedCollection> pos) {
		this(dest, pos, null);
	}

	/**
	 * Creates a receptor with full control over destination, position, and callback.
	 *
	 * @param dest the destination collection (must be 2D if pos is non-null)
	 * @param pos  producer for the row index, or null to write from offset zero
	 * @param r    callback to invoke after each write, or null for no callback
	 * @throws IllegalArgumentException if pos is non-null and dest is not 2D
	 */
	public CollectionReceptor(PackedCollection dest, Producer<PackedCollection> pos, Runnable r) {
		if (pos != null && dest.getShape().getDimensions() != 2)
			throw new IllegalArgumentException();

		this.dest = dest;
		this.pos = pos;
		this.r = r;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Evaluates the incoming producer, determines the write offset from the
	 * position producer (or uses zero), copies the data into the destination,
	 * and invokes the callback if configured.</p>
	 *
	 * @param protein the data producer to write
	 * @return a supplier that performs the write when executed
	 */
	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> protein) {
		return () -> () -> {
			PackedCollection in = protein.get().evaluate();
			int p = pos == null ? 0 : (int) pos.get().evaluate().toDouble(0);

			int length = pos == null ? dest.getShape().getTotalSize() : dest.getShape().length(1);
			int offset = p * length;

			dest.setMem(offset, in, 0, length);
			if (r != null) r.run();
		};
	}
}
