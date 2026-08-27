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
import org.almostrealism.protobuf.Collections;

/**
 * Collection data that is known to be in a file, and has not been read.
 *
 * <p>A {@link Collections.CollectionData} can be had by parsing one, which
 * puts every value on the Java heap in order to hand them to a device that may
 * never ask for them. This names the same data by where it is instead: the
 * byte range its values occupy, and the precision they are stored at. Nothing
 * is read until something reads it.</p>
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

	/** Shape of the collection the values describe. */
	private final TraversalPolicy shape;

	/** Precision the values are stored at. */
	private final Precision precision;

	/** Byte position of the first value within the file. */
	private final long valueOffset;

	/** Number of values. */
	private final int count;

	/**
	 * Creates a reference to values already located.
	 *
	 * @param shape       shape of the collection
	 * @param precision   precision the values are stored at
	 * @param valueOffset byte position of the first value within the file
	 * @param count       number of values
	 */
	protected CollectionDataReference(TraversalPolicy shape, Precision precision,
									  long valueOffset, int count) {
		this.shape = shape;
		this.precision = precision;
		this.valueOffset = valueOffset;
		this.count = count;
	}

	/** Returns the shape of the collection the values describe. */
	public TraversalPolicy getShape() { return shape; }

	/** Returns the precision the values are stored at. */
	public Precision getPrecision() { return precision; }

	/** Returns the byte position of the first value within the file. */
	public long getValueOffset() { return valueOffset; }

	/** Returns the number of values. */
	public int getCount() { return count; }

	/** Returns how many bytes the values occupy. */
	public long getValueLength() { return (long) count * precision.bytes(); }

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
	 * @param data the collection data message
	 * @return a reference to its values, or {@code null} if it holds none
	 */
	public static CollectionDataReference of(EncodedMessage data) {
		EncodedMessage policy = data.field(TRAVERSAL_POLICY_FIELD);
		if (policy == null) return null;

		TraversalPolicy shape = CollectionEncoder.decode(
				policy.parse(Collections.TraversalPolicyData.parser()));
		if (shape == null || shape.getDimensions() == 0) return null;

		Precision precision = data.has(DATA_FIELD) ? Precision.FP64 : Precision.FP32;
		int field = precision == Precision.FP64 ? DATA_FIELD : DATA_32_FIELD;

		long values = data.positionOf(field);
		if (values < 0) return null;

		int bytes = data.lengthOf(field);

		if (bytes % precision.bytes() != 0) {
			throw new IllegalArgumentException("Values at " + values +
					" occupy " + bytes + " bytes, which is not a whole number " +
					"of " + precision + " values. A producer that writes this " +
					"field unpacked leaves the values discontiguous, and they " +
					"cannot be addressed as a range.");
		}

		return new CollectionDataReference(shape, precision, values,
				bytes / precision.bytes());
	}
}
