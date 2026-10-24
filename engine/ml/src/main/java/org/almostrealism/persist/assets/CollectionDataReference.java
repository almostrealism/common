/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.persist.assets;

import io.almostrealism.code.Precision;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.protobuf.Collections;

import java.nio.ByteBuffer;

/**
 * Collection data that is known to be in a file, and has not been read.
 *
 * <p>A {@link Collections.CollectionData} can be had by parsing one, which
 * puts every value on the Java heap in order to hand them to a device that may
 * never ask for them. This names the same data by where it is instead: the
 * byte range its values occupy, and the {@link ValueEncoding} they are stored
 * at. Nothing is read until something reads it.</p>
 *
 * <p>The data need not be the whole of the file. A reference is resolved by
 * descending a path of field numbers, so collection data nested inside a
 * larger message — a library of weights, a record in a store — is addressed
 * the same way as collection data written on its own. The file stays an
 * ordinary protobuf asset either way, which is the point: reading it cheaply
 * must not cost anyone the ability to read it with an ordinary protobuf
 * library.</p>
 *
 * <p>Only the shape is parsed on resolution. It is a handful of integers, and
 * the extent of the values cannot be known without it.</p>
 *
 * @see CollectionDataMemoryProvider
 * @see EncodedMessage
 */
public class CollectionDataReference {
	/** Field number of {@code traversal_policy} within {@code CollectionData}. */
	public static final int TRAVERSAL_POLICY_FIELD = 1;

	/** Field number of the FP64 {@code data} within {@code CollectionData}. */
	public static final int DATA_FIELD = 2;

	/** Field number of the FP32 {@code data_32} within {@code CollectionData}. */
	public static final int DATA_32_FIELD = 3;

	/** Field number of the signed 32-bit integer {@code data_i32} within {@code CollectionData}. */
	public static final int DATA_I32_FIELD = 4;

	/** Field number of the signed 64-bit integer {@code data_i64} within {@code CollectionData}. */
	public static final int DATA_I64_FIELD = 5;

	/** Shape of the collection the values describe. */
	private final TraversalPolicy shape;

	/** How the values are stored and decoded. */
	private final ValueEncoding encoding;

	/** Byte position of the first value within the file. */
	private final long valueOffset;

	/** Number of values. */
	private final int count;

	/**
	 * Creates a reference to values already located.
	 *
	 * @param shape       shape of the collection
	 * @param encoding    how the values are stored and decoded
	 * @param valueOffset byte position of the first value within the file
	 * @param count       number of values
	 */
	protected CollectionDataReference(TraversalPolicy shape, ValueEncoding encoding,
									  long valueOffset, int count) {
		this.shape = shape;
		this.encoding = encoding;
		this.valueOffset = valueOffset;
		this.count = count;
	}

	/** Returns the shape of the collection the values describe. */
	public TraversalPolicy getShape() { return shape; }

	/** Returns how the values are stored and decoded. */
	public ValueEncoding getEncoding() { return encoding; }

	/**
	 * Returns the narrowest precision that holds every value exactly.
	 *
	 * @see ValueEncoding#getPrecision()
	 */
	public Precision getPrecision() { return encoding.getPrecision(); }

	/** Returns the byte position of the first value within the file. */
	public long getValueOffset() { return valueOffset; }

	/** Returns the number of values. */
	public int getCount() { return count; }

	/** Returns how many bytes the values occupy. */
	public long getValueLength() { return (long) count * encoding.getBytes(); }

	/**
	 * Reads one value from a buffer holding the whole file this reference was resolved
	 * against, decoding it from the form it is stored in.
	 *
	 * <p>The {@link ValueEncoding} decodes each value from its little-endian bytes, so a format
	 * that stores values in any of the readable forms is served without this needing to know
	 * which.</p>
	 *
	 * @param buffer the file's bytes, in the order the values were written
	 * @param index  the position of the value among this reference's values
	 * @return the value
	 */
	protected double valueAt(ByteBuffer buffer, int index) {
		return encoding.decode(buffer, (int) (valueOffset + (long) index * encoding.getBytes()));
	}

	/**
	 * Locates collection data within a message, descending the given path of
	 * field numbers to reach it.
	 *
	 * <p>An empty path addresses the message itself. A path of {@code 1, 2}
	 * addresses the collection data at field 2 of the message at field 1, so
	 * data nested at any depth is reachable.</p>
	 *
	 * @param message the containing message
	 * @param path    field numbers to descend, outermost first
	 * @return a reference to the values, or {@code null} if the path leads
	 *         nowhere or the data holds no values
	 */
	public static CollectionDataReference within(EncodedMessage message, int... path) {
		EncodedMessage data = message.descend(path);
		return data == null ? null : of(data);
	}

	/**
	 * Locates the values of the given collection data.
	 *
	 * <p>A collection whose shape has zero total size (an axis of length zero)
	 * holds no values. It resolves to {@code null} rather than to an empty
	 * reference because a {@link PackedCollection} cannot have zero size —
	 * reading it back is not a loss of an addressable range, it is a value the
	 * reader cannot hold at all. This is decided by the shape, not by whether a
	 * data field is present: a canonical writer omits an empty packed field, but
	 * one that writes it explicitly with length zero is equally valid protobuf
	 * and must resolve the same way. The {@code CollectionData} it was written
	 * from remains readable by an ordinary protobuf parse.</p>
	 *
	 * @param data the collection data message
	 * @return a reference to its values, or {@code null} if it holds none
	 */
	public static CollectionDataReference of(EncodedMessage data) {
		EncodedMessage policy = data.field(TRAVERSAL_POLICY_FIELD);
		if (policy == null) return null;

		TraversalPolicy shape = CollectionEncoder.decode(
				policy.parse(Collections.TraversalPolicyData.parser()));
		if (shape == null || shape.getTotalSizeLong() == 0) return null;

		ValueEncoding encoding = ValueEncoding.of(data);
		if (encoding == null) return null;

		long values = data.positionOf(encoding.getField());
		if (values < 0) return null;

		int count = encoding.valuesIn("the value payload at byte " + values,
				data.lengthOf(encoding.getField()));

		return new CollectionDataReference(shape, encoding, values, count);
	}
}
