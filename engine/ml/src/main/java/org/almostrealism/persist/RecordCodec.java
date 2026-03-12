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

package org.almostrealism.persist;

/**
 * Serialization strategy for converting records to and from byte arrays
 * for storage in a {@link DiskStore}. Implementations must be stateless
 * and thread-safe.
 *
 * @param <T> the record type
 */
public interface RecordCodec<T> {

	/**
	 * Serialize a record to a byte array.
	 *
	 * @param record the record to encode
	 * @return the serialized bytes
	 */
	byte[] encode(T record);

	/**
	 * Deserialize a record from a byte array.
	 *
	 * @param data the serialized bytes
	 * @return the deserialized record
	 */
	T decode(byte[] data);

	/**
	 * Estimate the in-memory size of a record in bytes. Used for
	 * capacity planning and memory budget enforcement.
	 *
	 * @param record the record to estimate
	 * @return estimated size in bytes
	 */
	int estimateSize(T record);
}
