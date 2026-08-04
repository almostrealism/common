/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.persist.assets;

import io.almostrealism.code.Precision;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.protobuf.Collections;

import java.nio.ByteBuffer;
import java.util.stream.IntStream;

/**
 * Utility class for encoding and decoding {@link PackedCollection} tensors to and from
 * the protobuf {@link Collections.CollectionData} wire format.
 *
 * <p>Encoding preserves the tensor's shape ({@link TraversalPolicy}) and numeric data at
 * either FP64 or FP32 precision. Decoding reconstructs a {@link PackedCollection} —
 * optionally writing into an existing destination buffer at a given offset.</p>
 *
 * @see PackedCollection
 */
public class CollectionEncoder {
	/**
	 * Encodes a {@link PackedCollection} to protobuf format at FP64 (double) precision.
	 *
	 * @param c The collection to encode; must not be {@code null}
	 * @return A {@link Collections.CollectionData} message representing the collection
	 */
	public static Collections.CollectionData encode(PackedCollection c) {
		return encode(c, Precision.FP64);
	}

	/**
	 * Encodes a {@link PackedCollection} to protobuf format at the specified precision.
	 *
	 * <p>If {@code c} is {@code null}, the default (empty) instance of
	 * {@link Collections.CollectionData} is returned.</p>
	 *
	 * @param c         The collection to encode
	 * @param precision {@link Precision#FP64} stores doubles; {@link Precision#FP32} stores floats
	 * @return A {@link Collections.CollectionData} message, or the default instance if {@code c} is null
	 * @throws IllegalArgumentException if {@code precision} is not FP32 or FP64
	 */
	public static Collections.CollectionData encode(PackedCollection c, Precision precision) {
		if (c == null) return Collections.CollectionData.getDefaultInstance();

		Collections.CollectionData.Builder data = Collections.CollectionData.newBuilder();
		data.setTraversalPolicy(encode(c.getShape()));

		if (precision == Precision.FP64) {
			c.doubleStream().forEach(data::addData);
		} else if (precision == Precision.FP32) {
			c.doubleStream().forEach(d -> data.addData32((float) d));
		} else {
			throw new IllegalArgumentException();
		}

		return data.build();
	}

	/**
	 * Encodes a {@link TraversalPolicy} shape to its protobuf representation.
	 *
	 * @param shape The traversal policy to encode
	 * @return A {@link Collections.TraversalPolicyData} message capturing dimensions and traversal axis
	 */
	public static Collections.TraversalPolicyData encode(TraversalPolicy shape) {
		Collections.TraversalPolicyData.Builder data = Collections.TraversalPolicyData.newBuilder();
		IntStream.range(0, shape.getDimensions()).forEach(i -> data.addDims(shape.length(i)));
		data.setTraversalAxis(shape.getTraversalAxis());
		return data.build();
	}

	/**
	 * Decodes a {@link Collections.CollectionData} message into a new {@link PackedCollection}.
	 *
	 * <p>Returns {@code null} if the encoded shape has zero dimensions (i.e., the collection was null
	 * when encoded).</p>
	 *
	 * @param data The protobuf message to decode
	 * @return A new {@link PackedCollection} with the encoded shape and values, or {@code null}
	 */
	public static PackedCollection decode(Collections.CollectionData data) {
		return decode(data, false);
	}

	/**
	 * Decodes a {@link Collections.CollectionData} message into a {@link PackedCollection},
	 * either deferred over the message itself or materialized into freshly
	 * allocated memory.
	 *
	 * <p>When {@code materialize} is {@code false}, the collection's root is a
	 * {@link org.almostrealism.hardware.mem.Bytes} over
	 * {@link CollectionDataMemoryProvider} memory: no host array is created and
	 * no device memory is allocated here — values are read from the message on
	 * demand, and the framework migrates the data to the compute device only
	 * when a kernel first requires it. Data that is only ever read on the host
	 * never reaches a device at all. Migration is one-way, so the returned
	 * collection must be treated as read-only until it has migrated.</p>
	 *
	 * <p>When {@code materialize} is {@code true}, a collection is allocated
	 * the normal way and the deferred collection is copied into it with
	 * {@link PackedCollection#setFrom}, producing ordinary writable storage.</p>
	 *
	 * @param data The protobuf message to decode
	 * @param materialize whether to copy the values into freshly allocated memory
	 * @return A collection with the encoded shape,
	 *         or {@code null} if the encoded shape has zero dimensions
	 */
	public static PackedCollection decode(Collections.CollectionData data, boolean materialize) {
		TraversalPolicy shape = decode(data.getTraversalPolicy());
		if (shape.getDimensions() == 0) return null;

		CollectionDataMemory mem = (CollectionDataMemory)
				CollectionDataMemoryProvider.getInstance().allocate(data);
		PackedCollection deferred = new PackedCollection(shape, shape.getTraversalAxis(),
				Bytes.of(mem, mem.getLength()), 0);
		if (!materialize) return deferred;

		PackedCollection result = new PackedCollection(shape);
		result.setFrom(0, deferred, 0, mem.getLength());
		deferred.getRootDelegate().destroy();
		return result;
	}

	/**
	 * Decodes a {@link Collections.CollectionData} message into an existing destination collection.
	 *
	 * @param data        The protobuf message to decode
	 * @param destination The collection to write decoded values into, starting at offset 0
	 * @return A range view of {@code destination} shaped according to the encoded shape,
	 *         or {@code null} if the encoded shape has zero dimensions
	 */
	public static PackedCollection decode(Collections.CollectionData data,
											 PackedCollection destination) {
		return decode(data, destination, 0);
	}

	/**
	 * Decodes a {@link Collections.CollectionData} message into an existing destination collection at the given offset.
	 *
	 * @param data              The protobuf message to decode
	 * @param destination       The collection to write decoded values into
	 * @param destinationOffset Element offset within {@code destination} at which writing begins
	 * @return A range view of {@code destination} shaped according to the encoded shape,
	 *         or {@code null} if the encoded shape has zero dimensions
	 */
	public static PackedCollection decode(Collections.CollectionData data,
											 PackedCollection destination,
											 int destinationOffset) {
		TraversalPolicy shape = decode(data.getTraversalPolicy());
		if (shape.getDimensions() == 0) return null;

		PackedCollection decoded = destination.range(shape, destinationOffset);

		ByteBuffer buffer = ByteBuffer.allocate(decoded.getMemLength() * Double.BYTES);

		if (data.getDataList().isEmpty()) {
			for (int i = 0; i < data.getData32Count(); i++) {
				buffer.putDouble(data.getData32(i));
			}
		} else {
			data.getDataList().forEach(buffer::putDouble);
		}

		buffer.flip();
		decoded.read(buffer);

		return decoded;
	}

	/**
	 * Decodes a {@link Collections.TraversalPolicyData} message back into a {@link TraversalPolicy}.
	 *
	 * @param data The protobuf shape message
	 * @return The reconstructed {@link TraversalPolicy}
	 */
	public static TraversalPolicy decode(Collections.TraversalPolicyData data) {
		return new TraversalPolicy(true,
				data.getDimsList().stream().mapToInt(i -> i).toArray())
				.traverse(data.getTraversalAxis());
	}
}
