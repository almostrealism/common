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

import io.almostrealism.code.NumberSystem;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.protobuf.Collections;

import java.util.function.Predicate;

/**
 * A value-carrying field of the {@link Collections.CollectionData} protobuf message, bound to the
 * {@link NumberSystem} its values are stored as.
 *
 * <p>This is the wire binding for the protobuf format alone: it says which field number carries a
 * given number system's values and how to read and write that field. Everything about the number
 * system itself — its byte width, how a value decodes from its bytes, whether a value is an integer,
 * the egress range checks — belongs to {@link NumberSystem} and is delegated to it, so this holds no
 * knowledge of a format that {@code NumberSystem} does not already own.</p>
 *
 * <p>A {@link Collections.CollectionData} carries the values of exactly one payload. The two integer
 * payloads are fixed-width ({@code sfixed32}/{@code sfixed64}) so a value can be addressed at
 * {@code offset + index * width} inside the packed field, the same way the floating-point payloads
 * are, which is what lets a reader defer reading the values.</p>
 *
 * @see CollectionDataReference
 * @see NumberSystem
 */
public enum CollectionDataPayload {
	/** IEEE double precision, carried by the {@code data} field. */
	DATA(NumberSystem.F64, CollectionDataReference.DATA_FIELD) {
		@Override
		double valueAt(Collections.CollectionData data, int index) { return data.getData(index); }

		@Override
		int count(Collections.CollectionData data) { return data.getDataCount(); }

		@Override
		void append(Collections.CollectionData.Builder data, double value) { data.addData(value); }
	},
	/** IEEE single precision, carried by the {@code data_32} field. */
	DATA_32(NumberSystem.F32, CollectionDataReference.DATA_32_FIELD) {
		@Override
		double valueAt(Collections.CollectionData data, int index) { return data.getData32(index); }

		@Override
		int count(Collections.CollectionData data) { return data.getData32Count(); }

		@Override
		void append(Collections.CollectionData.Builder data, double value) { data.addData32((float) value); }
	},
	/** Signed 32-bit integer, carried by the {@code data_i32} field. */
	DATA_I32(NumberSystem.I32, CollectionDataReference.DATA_I32_FIELD) {
		@Override
		double valueAt(Collections.CollectionData data, int index) { return data.getDataI32(index); }

		@Override
		int count(Collections.CollectionData data) { return data.getDataI32Count(); }

		@Override
		void append(Collections.CollectionData.Builder data, double value) { data.addDataI32((int) value); }
	},
	/** Signed 64-bit integer, carried by the {@code data_i64} field. */
	DATA_I64(NumberSystem.I64, CollectionDataReference.DATA_I64_FIELD) {
		@Override
		double valueAt(Collections.CollectionData data, int index) {
			return NumberSystem.requireExactLong(data.getDataI64(index));
		}

		@Override
		int count(Collections.CollectionData data) { return data.getDataI64Count(); }

		@Override
		void append(Collections.CollectionData.Builder data, double value) { data.addDataI64((long) value); }
	};

	/** The number system the values of this payload are stored as. */
	private final NumberSystem system;

	/** The {@link Collections.CollectionData} field number carrying the values. */
	private final int field;

	/**
	 * Creates a payload binding.
	 *
	 * @param system the number system the values are stored as
	 * @param field  the {@link Collections.CollectionData} field number carrying them
	 */
	CollectionDataPayload(NumberSystem system, int field) {
		this.system = system;
		this.field = field;
	}

	/** Returns the number system the values of this payload are stored as. */
	public NumberSystem getSystem() { return system; }

	/** Returns the {@link Collections.CollectionData} field number carrying the values. */
	int getField() { return field; }

	/**
	 * Reads the value at the given index of a parsed collection, for the field this payload names.
	 *
	 * @param data  the parsed collection data
	 * @param index the value's position
	 * @return the value as a double
	 * @throws ArithmeticException if the value is an {@code int64} outside &plusmn;2<sup>53</sup>
	 */
	abstract double valueAt(Collections.CollectionData data, int index);

	/**
	 * Returns the number of values the field this payload names carries in the given parsed
	 * collection.
	 *
	 * @param data the parsed collection data
	 * @return the number of values
	 */
	abstract int count(Collections.CollectionData data);

	/**
	 * Appends one value to the field this payload names in the given builder, converted to the stored
	 * type.
	 *
	 * @param data  the builder to append to
	 * @param value the value, already validated for the stored type
	 */
	abstract void append(Collections.CollectionData.Builder data, double value);

	/**
	 * Writes a collection's values into the given builder as this payload. A floating-point payload
	 * streams the values straight through; an integer payload requires each value to be a whole
	 * number in range ({@link NumberSystem#toExactLong}) before storing it.
	 *
	 * @param c    the collection to write
	 * @param data the builder to write into
	 * @throws IllegalArgumentException if an integer payload is asked to store a value that is not a
	 *         whole number in range
	 */
	void write(PackedCollection c, Collections.CollectionData.Builder data) {
		if (system.isInteger()) {
			double[] values = c.doubleStream().toArray();
			int memoryBytes = values.length == 0 ? Long.BYTES : c.getMem().getProvider().getNumberSize();
			for (int i = 0; i < values.length; i++) {
				append(data, system.toExactLong(values[i], i, memoryBytes));
			}
		} else {
			c.doubleStream().forEach(value -> append(data, value));
		}
	}

	/**
	 * Returns the payload carrying values of the given number system, for writing a collection as
	 * that system's values.
	 *
	 * @param system the number system the values are stored as
	 * @return the payload
	 * @throws IllegalArgumentException if no protobuf payload carries the given system's values
	 */
	static CollectionDataPayload of(NumberSystem system) {
		for (CollectionDataPayload candidate : values()) {
			if (candidate.system == system) return candidate;
		}

		throw new IllegalArgumentException(system + " has no collection data payload");
	}

	/**
	 * Returns the single payload present in the given collection data, for a collection that has
	 * already been parsed.
	 *
	 * @param data the parsed collection data
	 * @return the present payload, or {@code null} if it carries no payload
	 * @throws IllegalArgumentException if more than one payload is present
	 */
	public static CollectionDataPayload of(Collections.CollectionData data) {
		return present(payload -> payload.count(data) > 0);
	}

	/**
	 * Returns the single payload present in the given encoded message, for a collection located in a
	 * file but not yet parsed: a payload is present when the message carries its {@link #getField()
	 * field}.
	 *
	 * @param message the encoded collection data
	 * @return the present payload, or {@code null} if it carries no payload
	 * @throws IllegalArgumentException if more than one payload is present
	 */
	static CollectionDataPayload of(EncodedMessage message) {
		return present(payload -> message.has(payload.field));
	}

	/**
	 * Returns the single payload for which {@code present} holds, failing when more than one does: a
	 * collection carries the values of exactly one payload. The two representations a collection is
	 * read from ({@link Collections.CollectionData}, {@link EncodedMessage}) differ only in how a
	 * payload's presence is tested, so each supplies that test and this makes the choice.
	 *
	 * @param present whether a candidate payload is present
	 * @return the one present payload, or {@code null} if none is
	 * @throws IllegalArgumentException if more than one payload is present
	 */
	private static CollectionDataPayload present(Predicate<CollectionDataPayload> present) {
		CollectionDataPayload found = null;
		for (CollectionDataPayload candidate : values()) {
			if (present.test(candidate)) {
				if (found != null) {
					throw new IllegalArgumentException("Collection data carries both a " + found.system
							+ " and a " + candidate.system + " payload, but exactly one is expected");
				}

				found = candidate;
			}
		}

		return found;
	}
}
