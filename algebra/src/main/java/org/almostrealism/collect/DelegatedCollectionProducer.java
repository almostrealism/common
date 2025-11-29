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

package org.almostrealism.collect;

import io.almostrealism.collect.CollectionProducerBase;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.computations.DelegatedProducer;

/**
 * A wrapper that delegates collection producer operations to an underlying {@link CollectionProducer}.
 *
 * <p>
 * {@link DelegatedCollectionProducer} provides a lightweight delegation pattern for collection producers,
 * forwarding method calls to a wrapped producer while allowing subclasses to override specific behaviors.
 * This is useful for:
 * <ul>
 *   <li>Adding additional metadata or tracking to existing producers</li>
 *   <li>Modifying specific behaviors while preserving most functionality</li>
 *   <li>Creating proxy or adapter patterns for collection operations</li>
 * </ul>
 *
 * <h2>Direct vs Indirect Delegation</h2>
 * <p>
 * The {@code directDelegate} parameter controls how method calls are forwarded:
 * <ul>
 *   <li><b>Direct (true):</b> Methods are delegated directly to the wrapped producer</li>
 *   <li><b>Indirect (false):</b> Additional processing or filtering may occur before delegation</li>
 * </ul>
 *
 * <h2>Fixed Count Behavior</h2>
 * <p>
 * The {@code fixedCount} parameter affects how {@link #isFixedCount()} behaves:
 * <ul>
 *   <li><b>true:</b> Delegates to the wrapped producer's {@link #isFixedCount()}</li>
 *   <li><b>false:</b> Always returns false, indicating variable count</li>
 * </ul>
 * </p>
 *
 * @param <T>  the packed collection type
 * @author  Michael Murray
 * @see CollectionProducer
 * @see org.almostrealism.hardware.computations.DelegatedProducer
 */
public class DelegatedCollectionProducer<T extends PackedCollection>
						extends DelegatedProducer<PackedCollection>
						implements CollectionProducerBase<PackedCollection, CollectionProducer> {
	private final boolean fixedCount;

	/**
	 * Creates a delegated collection producer with direct delegation and fixed count.
	 *
	 * @param op  the collection producer to wrap
	 */
	public DelegatedCollectionProducer(CollectionProducer op) {
		this(op, true);
	}

	/**
	 * Creates a delegated collection producer with the specified delegation mode and fixed count.
	 *
	 * @param op  the collection producer to wrap
	 * @param directDelegate  true for direct delegation, false for indirect
	 */
	public DelegatedCollectionProducer(CollectionProducer op, boolean directDelegate) {
		this(op, directDelegate, true);
	}

	/**
	 * Creates a delegated collection producer with full configuration.
	 *
	 * @param op  the collection producer to wrap
	 * @param directDelegate  true for direct delegation, false for indirect
	 * @param fixedCount  true to delegate isFixedCount(), false to always return false
	 */
	public DelegatedCollectionProducer(CollectionProducer op, boolean directDelegate, boolean fixedCount) {
		super(op, directDelegate);
		this.fixedCount = fixedCount;
	}

	/**
	 * Returns the shape of the wrapped collection producer.
	 *
	 * @return the traversal policy from the wrapped producer
	 */
	@Override
	public TraversalPolicy getShape() {
		return ((CollectionProducer) op).getShape();
	}

	/**
	 * Traverse operation is not supported on delegated producers.
	 *
	 * @throws UnsupportedOperationException always thrown
	 */
	@Override
	public CollectionProducer traverse(int axis) { throw new UnsupportedOperationException(); }

	/**
	 * Reshape operation is not supported on delegated producers.
	 *
	 * @throws UnsupportedOperationException always thrown
	 */
	@Override
	public CollectionProducer reshape(TraversalPolicy shape) {
		throw new UnsupportedOperationException();
	}

	@Override
	public long getCountLong() {
		// TODO  This may not be necessary
		return CollectionProducerBase.super.getCountLong();
	}

	/**
	 * Returns whether this producer has a fixed element count.
	 *
	 * <p>
	 * Behavior depends on the {@code fixedCount} parameter:
	 * <ul>
	 *   <li>If true: delegates to the wrapped producer's isFixedCount()</li>
	 *   <li>If false: always returns false</li>
	 * </ul>
	 * </p>
	 *
	 * @return true if the count is fixed, false otherwise
	 */
	@Override
	public boolean isFixedCount() {
		return fixedCount && super.isFixedCount();
	}

	/**
	 * Returns the total size of the output collection.
	 *
	 * @return the total size from the wrapped producer's shape
	 */
	@Override
	public long getOutputSize() {
		return ((CollectionProducer) op).getShape().getTotalSize();
	}

	/**
	 * Returns a signature string for this delegated producer including shape information.
	 *
	 * @return signature string with shape details, or null if no signature available
	 */
	@Override
	public String signature() {
		String signature = super.signature();
		if (signature == null) return null;

		return signature + "|" + getShape().toStringDetail();
	}
}