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
import org.almostrealism.persist.assets.ValueEncoding;
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
		ValueEncoding encoding = reference.getEncoding();
		double[] out = new double[reference.getCount()];

		for (int i = 0; i < out.length; i++) {
			int at = (int) (reference.getValueOffset() - base) + i * encoding.getBytes();
			if (encoding == ValueEncoding.F64) {
				out[i] = buffer.getDouble(at);
			} else if (encoding == ValueEncoding.F32) {
				out[i] = buffer.getFloat(at);
			} else if (encoding == ValueEncoding.I32) {
				out[i] = buffer.getInt(at);
			} else {
				out[i] = buffer.getLong(at);
			}
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
	 * A collection with a zero-length axis carries no data field on the wire, and it resolves to
	 * {@code null} rather than to an addressable range: a {@link PackedCollection} cannot have zero
	 * size, so an empty tensor is a value the reader cannot hold, not a locatable one. This pins the
	 * deliberate contract — a would-be "fix" that returned a zero-count reference here would make the
	 * decoder throw while building the collection and drop the whole shard it appears in.
	 */
	@Test(timeout = 30000)
	public void anEmptyCollectionResolvesToNothing() {
		byte[] encoded = Collections.CollectionData.newBuilder()
				.setTraversalPolicy(Collections.TraversalPolicyData.newBuilder()
						.addDims(1).addDims(0).addDims(1).setTraversalAxis(0))
				.build()
				.toByteArray();

		Assert.assertNull(CollectionDataReference.of(message(encoded, 0)));
	}

	/**
	 * A collection with a zero-length axis resolves to nothing even when its data field is present
	 * on the wire with length zero. A canonical protobuf writer omits an empty packed field, but a
	 * writer that emits it explicitly produces equally valid protobuf; the reference must be decided
	 * by the shape, or a zero-count reference escapes into the decoder and drops the whole shard.
	 */
	@Test(timeout = 30000)
	public void anEmptyCollectionWithAnExplicitEmptyFieldResolvesToNothing() {
		byte[] withoutData = Collections.CollectionData.newBuilder()
				.setTraversalPolicy(Collections.TraversalPolicyData.newBuilder()
						.addDims(1).addDims(0).addDims(1).setTraversalAxis(0))
				.build()
				.toByteArray();

		byte[] emptyField = {
				(byte) ((CollectionDataReference.DATA_32_FIELD << 3) | 2), 0
		};

		byte[] encoded = new byte[withoutData.length + emptyField.length];
		System.arraycopy(withoutData, 0, encoded, 0, withoutData.length);
		System.arraycopy(emptyField, 0, encoded, withoutData.length, emptyField.length);

		EncodedMessage message = message(encoded, 0);
		Assert.assertTrue(message.has(CollectionDataReference.DATA_32_FIELD));
		Assert.assertEquals(0, message.lengthOf(CollectionDataReference.DATA_32_FIELD));
		Assert.assertNull(CollectionDataReference.of(message));
	}

	/**
	 * The destination overload of {@link CollectionEncoder#decode} resolves a zero-total-size
	 * collection to {@code null} rather than reaching {@link PackedCollection#range} with a
	 * zero-size shape. A {@code [1, 0, 1]} shape has three dimensions but zero elements, so the
	 * old {@code getDimensions() == 0} guard would have let it through; this pins the shape-based
	 * guard shared with the other {@code decode} overloads and the deferred reader.
	 */
	@Test(timeout = 30000)
	public void decodingAnEmptyCollectionIntoADestinationResolvesToNothing() {
		Collections.CollectionData empty = Collections.CollectionData.newBuilder()
				.setTraversalPolicy(Collections.TraversalPolicyData.newBuilder()
						.addDims(1).addDims(0).addDims(1).setTraversalAxis(0))
				.build();

		PackedCollection destination = new PackedCollection(4);
		Assert.assertNull(CollectionEncoder.decode(empty, destination, 0));
		destination.destroy();
	}

	/**
	 * A non-empty collection still decodes into the destination buffer at the requested offset,
	 * confirming the zero-total-size guard does not disturb the ordinary decode path.
	 */
	@Test(timeout = 30000)
	public void decodingANonEmptyCollectionWritesIntoTheDestination() {
		PackedCollection destination = new PackedCollection(expected().length + 2);
		PackedCollection decoded = CollectionEncoder.decode(data(Precision.FP64), destination, 2);

		Assert.assertNotNull(decoded);
		Assert.assertEquals(expected().length, decoded.getMemLength());

		for (int i = 0; i < expected().length; i++) {
			Assert.assertEquals(expected()[i], destination.toDouble(i + 2), 0.0);
		}

		destination.destroy();
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

	/**
	 * An {@code I32} payload reads back exactly through all three read paths — the located (mapped)
	 * range, the parsed collection, and a decode into a destination buffer. The extreme values are
	 * powers of two so they are held exactly by FP32 collection memory as well as FP64.
	 */
	@Test(timeout = 30000)
	public void anI32PayloadReadsBackThroughEveryPath() {
		assertIntegerPayloadReadsBack(
				CollectionEncoder.encode(PackedCollection.of(-7, 0, 100, 1 << 30, Integer.MIN_VALUE), ValueEncoding.I32),
				ValueEncoding.I32, new double[] { -7, 0, 100, 1 << 30, Integer.MIN_VALUE });
	}

	/**
	 * An {@code I64} payload, including values past 2<sup>31</sup> and up to &plusmn;2<sup>53</sup>,
	 * reads back exactly through all three read paths.
	 */
	@Test(timeout = 30000)
	public void anI64PayloadReadsBackThroughEveryPath() {
		assertIntegerPayloadReadsBack(
				CollectionEncoder.encode(PackedCollection.of(-(1L << 40), 1L << 35, 1L << 53, -(1L << 53)), ValueEncoding.I64),
				ValueEncoding.I64, new double[] { -(1L << 40), 1L << 35, 1L << 53, -(1L << 53) });
	}

	/**
	 * Asserts that the given integer collection data reads back as {@code values} exactly through the
	 * located (mapped) range, the parsed collection, and a decode into a destination buffer at an
	 * offset.
	 *
	 * @param data     collection data already encoded as {@code encoding}'s integer payload
	 * @param encoding the integer encoding the data is stored as
	 * @param values   the whole-number values expected back
	 */
	private void assertIntegerPayloadReadsBack(Collections.CollectionData data,
											   ValueEncoding encoding, double[] values) {
		byte[] bytes = data.toByteArray();
		CollectionDataReference reference = CollectionDataReference.of(message(bytes, 0));
		Assert.assertEquals(encoding, reference.getEncoding());
		Assert.assertEquals(values.length, reference.getCount());
		Assert.assertArrayEquals(values, read(reference, bytes, 0), 0.0);

		PackedCollection parsed = CollectionEncoder.decode(data, false);
		Assert.assertArrayEquals(values, parsed.toArray(), 0.0);

		PackedCollection destination = new PackedCollection(values.length + 2);
		PackedCollection decoded = CollectionEncoder.decode(data, destination, 1);
		Assert.assertNotNull(decoded);
		for (int i = 0; i < values.length; i++) {
			Assert.assertEquals(values[i], destination.toDouble(i + 1), 0.0);
		}
		destination.destroy();
	}

	/**
	 * A {@link Collections.CollectionData} carrying two payloads is rejected on read, through both
	 * the mapped path ({@link CollectionDataReference#of}) and the parsed path: exactly one payload
	 * is expected.
	 */
	@Test(timeout = 30000)
	public void aCollectionWithTwoPayloadsIsRejected() {
		Collections.CollectionData twoPayloads = Collections.CollectionData.newBuilder()
				.setTraversalPolicy(Collections.TraversalPolicyData.newBuilder()
						.addDims(2).setTraversalAxis(0))
				.addData32(1.0f).addData32(2.0f)
				.addDataI32(1).addDataI32(2)
				.build();

		byte[] encoded = twoPayloads.toByteArray();
		try {
			CollectionDataReference.of(message(encoded, 0));
			Assert.fail("A collection with two payloads should be rejected on the mapped path");
		} catch (IllegalArgumentException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("exactly one"));
		}

		try {
			CollectionEncoder.decode(twoPayloads, false);
			Assert.fail("A collection with two payloads should be rejected on the parsed path");
		} catch (IllegalArgumentException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("exactly one"));
		}
	}

	/** Encoding a value that is not a whole number as an integer payload is rejected, naming the index. */
	@Test(timeout = 30000)
	public void encodingANonIntegralValueAsIntegerThrows() {
		PackedCollection c = PackedCollection.of(1.0, 2.5, 3.0);
		try {
			CollectionEncoder.encode(c, ValueEncoding.I32);
			Assert.fail("A non-integral value should not be stored as an integer payload");
		} catch (IllegalArgumentException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("index 1"));
		} finally {
			c.destroy();
		}
	}

	/** Encoding a value out of range for the integer width is rejected, naming the index. */
	@Test(timeout = 30000)
	public void encodingAnOutOfRangeValueAsIntegerThrows() {
		PackedCollection c = PackedCollection.of(1.0, (double) Integer.MAX_VALUE + 1);
		try {
			CollectionEncoder.encode(c, ValueEncoding.I32);
			Assert.fail("A value out of range for I32 should be rejected");
		} catch (IllegalArgumentException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("outside the range"));
		} finally {
			c.destroy();
		}
	}

	/** An integer payload reports FP64 as its precision, since FP32 cannot hold every integer value exactly. */
	@Test(timeout = 30000)
	public void anIntegerPayloadNeedsDoublePrecision() {
		byte[] bytes = CollectionEncoder.encode(PackedCollection.of(1, 2, 3), ValueEncoding.I32).toByteArray();
		CollectionDataReference reference = CollectionDataReference.of(message(bytes, 0));
		Assert.assertEquals(ValueEncoding.I32, reference.getEncoding());
		Assert.assertEquals(Precision.FP64, reference.getPrecision());
	}

	/**
	 * Each encoding reports the narrowest precision that holds its values exactly: FP16 for
	 * {@code F16}, FP32 for {@code BF16} (whose exponent range exceeds FP16's) and {@code F32}, and
	 * FP64 for {@code F64} and both integer encodings.
	 */
	@Test(timeout = 30000)
	public void everyEncodingReportsTheNarrowestExactPrecision() {
		Assert.assertEquals(Precision.FP16, ValueEncoding.F16.getPrecision());
		Assert.assertEquals(Precision.FP32, ValueEncoding.BF16.getPrecision());
		Assert.assertEquals(Precision.FP32, ValueEncoding.F32.getPrecision());
		Assert.assertEquals(Precision.FP64, ValueEncoding.F64.getPrecision());
		Assert.assertEquals(Precision.FP64, ValueEncoding.I32.getPrecision());
		Assert.assertEquals(Precision.FP64, ValueEncoding.I64.getPrecision());
	}

	/**
	 * {@code Integer.MAX_VALUE} is stored exactly by eight-byte collection memory and so encodes as
	 * {@code I32}; narrower memory rounds it to 2<sup>31</sup>, and the rejection then says the value
	 * may have been rounded out of range by the memory rather than only that it is out of range.
	 */
	@Test(timeout = 30000)
	public void theLargestI32ValueIsRejectedOnlyWhenMemoryRoundsIt() {
		PackedCollection c = PackedCollection.of(Integer.MAX_VALUE);
		try {
			if (c.getMem().getProvider().getNumberSize() >= Long.BYTES) {
				assertIntegerPayloadReadsBack(CollectionEncoder.encode(c, ValueEncoding.I32),
						ValueEncoding.I32, new double[] { Integer.MAX_VALUE });
			} else {
				try {
					CollectionEncoder.encode(c, ValueEncoding.I32);
					Assert.fail("A value rounded past the I32 range by narrow memory should be rejected");
				} catch (IllegalArgumentException e) {
					Assert.assertTrue(e.getMessage(), e.getMessage().contains("outside the range"));
					Assert.assertTrue(e.getMessage(), e.getMessage().contains("rounded"));
				}
			}
		} finally {
			c.destroy();
		}
	}

	/**
	 * Encoding at a precision that has no collection data payload is rejected rather than silently
	 * stored at another width. Only {@code FP32} and {@code FP64} name a floating-point payload, so
	 * {@code FP16} is refused: without the guard it would fall through to the {@code FP32} payload
	 * and store the values at the wrong width.
	 */
	@Test(timeout = 30000)
	public void encodingAtAPrecisionWithoutAPayloadThrows() {
		PackedCollection c = PackedCollection.of(1.0, 2.0, 3.0);
		try {
			CollectionEncoder.encode(c, Precision.FP16);
			Assert.fail("A precision with no collection data payload should be rejected");
		} catch (IllegalArgumentException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("no collection data payload"));
		} finally {
			c.destroy();
		}
	}
}
