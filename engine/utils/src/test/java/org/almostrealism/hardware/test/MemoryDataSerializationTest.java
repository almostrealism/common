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

package org.almostrealism.hardware.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Tests for the serialization surface of
 * {@link org.almostrealism.hardware.MemoryData}: {@code persist()} round trips
 * through {@code read(ByteBuffer)} — the primary ingest form for serialized
 * values — and through the byte array and stream adapters that delegate to it.
 */
public class MemoryDataSerializationTest extends TestSuiteBase {

	/** Number of elements in the test collections. */
	private static final int SIZE = 64;

	/** Produces a collection whose values are a fixed function of the index. */
	private PackedCollection testValues() {
		PackedCollection c = new PackedCollection(shape(SIZE));
		integers(0, SIZE).multiply(0.75).add(-4.0)
				.into(c.traverseEach()).evaluate();
		return c;
	}

	/**
	 * persist() output read back through the {@link ByteBuffer} form must
	 * reproduce the original values.
	 */
	@Test(timeout = 60000)
	public void persistRoundTripThroughByteBuffer() {
		PackedCollection source = testValues();

		PackedCollection restored = new PackedCollection(shape(SIZE));
		restored.read(ByteBuffer.wrap(source.persist()));

		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i,
					source.toDouble(i), restored.toDouble(i), 0.0);
		}
	}

	/**
	 * The byte array and stream adapters delegate to the buffer form and
	 * reproduce the same values.
	 */
	@Test(timeout = 60000)
	public void persistRoundTripThroughAdapters() throws IOException {
		PackedCollection source = testValues();
		byte[] bytes = source.persist();

		PackedCollection fromArray = new PackedCollection(shape(SIZE));
		fromArray.read(bytes);

		PackedCollection fromStream = new PackedCollection(shape(SIZE));
		fromStream.read(new ByteArrayInputStream(bytes));

		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("array element " + i,
					source.toDouble(i), fromArray.toDouble(i), 0.0);
			Assert.assertEquals("stream element " + i,
					source.toDouble(i), fromStream.toDouble(i), 0.0);
		}
	}

	/**
	 * A direct buffer works as a read source — the flexibility the
	 * {@link ByteBuffer} form exists to provide.
	 */
	@Test(timeout = 60000)
	public void readFromDirectBuffer() {
		PackedCollection source = testValues();

		ByteBuffer direct = ByteBuffer.allocateDirect(8 * SIZE);
		direct.put(source.persist());
		direct.position(0);

		PackedCollection restored = new PackedCollection(shape(SIZE));
		restored.read(direct);

		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i,
					source.toDouble(i), restored.toDouble(i), 0.0);
		}
	}
}
