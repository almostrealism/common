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

package org.almostrealism.persist.assets.test;

import io.almostrealism.code.Precision;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.persist.assets.CollectionDataReference;
import org.almostrealism.persist.assets.CollectionEncoder;
import org.almostrealism.persist.assets.EncodedMessage;
import org.almostrealism.protobuf.Collections;
import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Pins that collection data can be found in an encoded message without the
 * message being parsed.
 *
 * <p>What makes this worth doing is that the file stays an ordinary protobuf
 * asset: anything able to read protobuf can still read it, and the values are
 * addressable as a range for anything that would rather not build objects out
 * of them. So these check both halves — that the located range is where the
 * values actually are, and that the message a producer wrote is what a
 * consumer still sees.</p>
 */
public class CollectionDataReferenceTest {
	/** Values encoded by these tests. */
	private PackedCollection values() {
		return PackedCollection.of(1.5, -2.25, 0.0, 1024.75);
	}

	/** The values these tests expect to read back. */
	private double[] expected() {
		PackedCollection values = values();
		double[] out = new double[values.getMemLength()];

		for (int i = 0; i < out.length; i++) {
			out[i] = values.toDouble(i);
		}

		return out;
	}

	/**
	 * Encodes the values as collection data at the given precision.
	 *
	 * @param precision precision to encode at
	 * @return the encoded message
	 */
	private Collections.CollectionData data(Precision precision) {
		return CollectionEncoder.encode(values(), precision);
	}

	/**
	 * Wraps the given bytes as a message positioned at the given offset.
	 *
	 * @param encoded the encoded bytes
	 * @param offset  position to report the bytes as occupying
	 * @return the message
	 */
	private EncodedMessage message(byte[] encoded, long offset) {
		return new EncodedMessage(ByteBuffer.wrap(encoded), offset);
	}

	/**
	 * Reads the values a reference points at, out of the bytes they were
	 * located in.
	 *
	 * @param reference the located values
	 * @param encoded   the bytes containing them
	 * @param base      position the bytes were reported as occupying
	 * @return the values
	 */
	private double[] read(CollectionDataReference reference, byte[] encoded, long base) {
		ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
		double[] out = new double[reference.getCount()];

		for (int i = 0; i < out.length; i++) {
			int at = (int) (reference.getValueOffset() - base)
					+ i * reference.getPrecision().bytes();
			out[i] = reference.getPrecision() == Precision.FP64 ?
					buffer.getDouble(at) : buffer.getFloat(at);
		}

		return out;
	}

	/** The located range holds the values, at full precision. */
	@Test(timeout = 30000)
	public void theLocatedRangeHoldsTheValues() {
		byte[] encoded = data(Precision.FP64).toByteArray();
		CollectionDataReference reference =
				CollectionDataReference.of(message(encoded, 0));

		Assert.assertEquals(Precision.FP64, reference.getPrecision());
		Assert.assertEquals(expected().length, reference.getCount());
		Assert.assertArrayEquals(expected(), read(reference, encoded, 0), 0.0);
	}

	/** The located range holds the values, at half precision. */
	@Test(timeout = 30000)
	public void theLocatedRangeHoldsHalfPrecisionValues() {
		byte[] encoded = data(Precision.FP32).toByteArray();
		CollectionDataReference reference =
				CollectionDataReference.of(message(encoded, 0));

		Assert.assertEquals(Precision.FP32, reference.getPrecision());
		Assert.assertEquals(expected().length, reference.getCount());
		Assert.assertArrayEquals(expected(), read(reference, encoded, 0), 1e-6);
	}

	/** The shape is recovered without the values being read. */
	@Test(timeout = 30000)
	public void theShapeIsRecovered() {
		byte[] encoded = data(Precision.FP64).toByteArray();
		CollectionDataReference reference =
				CollectionDataReference.of(message(encoded, 0));

		Assert.assertEquals(1, reference.getShape().getDimensions());
		Assert.assertEquals(expected().length, reference.getShape().getTotalSize());
	}

	/**
	 * Collection data nested inside a larger message is addressable.
	 *
	 * <p>This is the shape a library of weights is written in — entries within
	 * a library, collection data within an entry — and the reason a reference
	 * takes a path rather than assuming it is looking at the whole file.</p>
	 */
	@Test(timeout = 30000)
	public void nestedCollectionDataIsAddressable() {
		Collections.CollectionLibraryData library = Collections.CollectionLibraryData
				.newBuilder()
				.addCollections(Collections.CollectionLibraryEntry.newBuilder()
						.setKey("weight")
						.setCollection(data(Precision.FP64)))
				.build();

		byte[] encoded = library.toByteArray();
		CollectionDataReference reference = CollectionDataReference.within(
				message(encoded, 0), 1, 2);

		Assert.assertNotNull(reference);
		Assert.assertEquals(expected().length, reference.getCount());
		Assert.assertArrayEquals(expected(), read(reference, encoded, 0), 0.0);
	}

	/**
	 * Positions are reported in the file's terms, not the message's.
	 *
	 * <p>A message read from part of a larger file has to report where its
	 * fields are in that file, since that is where they will be read from.</p>
	 */
	@Test(timeout = 30000)
	public void positionsAreReportedWithinTheFile() {
		byte[] encoded = data(Precision.FP64).toByteArray();

		CollectionDataReference at = CollectionDataReference.of(message(encoded, 0));
		CollectionDataReference moved =
				CollectionDataReference.of(message(encoded, 4096));

		Assert.assertEquals(at.getValueOffset() + 4096, moved.getValueOffset());
		Assert.assertArrayEquals(expected(), read(moved, encoded, 4096), 0.0);
	}

	/** A path that leads nowhere yields nothing rather than something wrong. */
	@Test(timeout = 30000)
	public void aPathThatLeadsNowhereYieldsNothing() {
		byte[] encoded = data(Precision.FP64).toByteArray();

		Assert.assertNull(CollectionDataReference.within(message(encoded, 0), 7));
	}

	/**
	 * The values a reference locates are the values an ordinary parse finds.
	 *
	 * <p>Locating must not become a second, divergent reading of the format.
	 * If these ever disagree, the file is no longer portable, which is the
	 * whole reason it is protobuf.</p>
	 */
	@Test(timeout = 30000)
	public void locatingAgreesWithParsing() {
		Collections.CollectionData encoded = data(Precision.FP64);
		byte[] bytes = encoded.toByteArray();

		CollectionDataReference reference =
				CollectionDataReference.of(message(bytes, 0));
		double[] located = read(reference, bytes, 0);

		Assert.assertEquals(encoded.getDataCount(), located.length);

		for (int i = 0; i < located.length; i++) {
			Assert.assertEquals(encoded.getData(i), located[i], 0.0);
		}
	}
}
