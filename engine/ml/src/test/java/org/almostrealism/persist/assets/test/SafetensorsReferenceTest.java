package org.almostrealism.persist.assets.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Bits;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.persist.assets.SafetensorsReference;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Tests for reading safetensors checkpoints: a small file is written byte by byte in the
 * format's layout (header length, JSON header, little-endian values), next to a configuration
 * file of the kind a published checkpoint directory holds, and read back through
 * {@link StateDictionary}. Every value is chosen to be exactly representable in the element type
 * it is stored as, so the values read back must equal the values written.
 */
public class SafetensorsReferenceTest extends TestSuiteBase {

	/** The header of the test checkpoint: one tensor of each element type, and a scalar. */
	private static final String HEADER = "{"
			+ "\"__metadata__\":{\"format\":\"pt\"},"
			+ "\"model.layers.0.weight\":{\"dtype\":\"BF16\",\"shape\":[2,2],\"data_offsets\":[0,8]},"
			+ "\"model.layers.0.bias\":{\"dtype\":\"F16\",\"shape\":[2],\"data_offsets\":[8,12]},"
			+ "\"model.norm.weight\":{\"dtype\":\"F32\",\"shape\":[3],\"data_offsets\":[12,24]},"
			+ "\"model.scale\":{\"dtype\":\"F32\",\"shape\":[],\"data_offsets\":[24,28]}"
			+ "}";

	/**
	 * Writes the test checkpoint into a new directory, together with a {@code config.json} that
	 * must not be read as weights.
	 *
	 * @return the checkpoint directory
	 */
	private Path writeCheckpoint() throws IOException {
		byte[] header = HEADER.getBytes(StandardCharsets.UTF_8);
		ByteBuffer file = ByteBuffer.allocate(Long.BYTES + header.length + 28).order(ByteOrder.LITTLE_ENDIAN);
		file.putLong(header.length);
		file.put(header);

		// BF16 [[1.5, -2.0], [0.25, 3.0]]: the upper halves of the FP32 bit patterns
		for (float value : new float[] { 1.5f, -2.0f, 0.25f, 3.0f }) {
			file.putShort((short) (Float.floatToIntBits(value) >>> 16));
		}

		// F16 [0.5, -1.25]
		file.putShort(Bits.floatToFloat16(0.5f));
		file.putShort(Bits.floatToFloat16(-1.25f));

		// F32 [0.1, 0.2, 0.3] and the scalar 7.0
		file.putFloat(0.1f).putFloat(0.2f).putFloat(0.3f);
		file.putFloat(7.0f);

		Path directory = Files.createTempDirectory("safetensors");
		Files.write(directory.resolve("model.safetensors"), file.array());
		Files.writeString(directory.resolve("config.json"), "{\"hidden_size\": 2}");
		return directory;
	}

	/** Every tensor is read with its shape and its values, whatever element type it is stored as. */
	@Test(timeout = 60000)
	public void readsEveryElementType() throws IOException {
		StateDictionary weights = new StateDictionary(writeCheckpoint().toString());
		Assert.assertEquals(4, weights.size());

		PackedCollection bf16 = weights.get("model.layers.0.weight");
		Assert.assertEquals(2, bf16.getShape().length(0));
		Assert.assertEquals(2, bf16.getShape().length(1));
		Assert.assertArrayEquals(new double[] { 1.5, -2.0, 0.25, 3.0 }, bf16.toArray(), 0.0);

		Assert.assertArrayEquals(new double[] { 0.5, -1.25 },
				weights.get("model.layers.0.bias").toArray(), 0.0);
		Assert.assertArrayEquals(new double[] { 0.1f, 0.2f, 0.3f },
				weights.get("model.norm.weight").toArray(), 0.0);
		Assert.assertArrayEquals(new double[] { 7.0 }, weights.get("model.scale").toArray(), 0.0);
	}

	/** Copying the values into allocated memory gives the same values as reading the file. */
	@Test(timeout = 60000)
	public void materializedValuesMatchMapped() throws IOException {
		Path directory = writeCheckpoint();
		boolean previous = StateDictionary.enableMaterializeWeights;
		try {
			StateDictionary.enableMaterializeWeights = true;
			StateDictionary weights = new StateDictionary(directory.toString());
			Assert.assertArrayEquals(new double[] { 1.5, -2.0, 0.25, 3.0 },
					weights.get("model.layers.0.weight").toArray(), 0.0);
		} finally {
			StateDictionary.enableMaterializeWeights = previous;
		}
	}

	/** The header is read in its own order, and each tensor records how it is stored. */
	@Test(timeout = 60000)
	public void locatesTensorsInHeaderOrder() throws IOException {
		File file = writeCheckpoint().resolve("model.safetensors").toFile();
		Map<String, SafetensorsReference> tensors = SafetensorsReference.locate(file);

		Assert.assertArrayEquals(new String[] { "model.layers.0.weight", "model.layers.0.bias",
						"model.norm.weight", "model.scale" },
				tensors.keySet().toArray(new String[0]));
		Assert.assertEquals(SafetensorsReference.Encoding.BF16,
				tensors.get("model.layers.0.weight").getEncoding());
		Assert.assertEquals(SafetensorsReference.Encoding.F16,
				tensors.get("model.layers.0.bias").getEncoding());
	}

	/** An element type the reader cannot decode is named in the failure. */
	@Test(timeout = 60000)
	public void rejectsUnsupportedElementType() throws IOException {
		assertRejected("{\"ids\":{\"dtype\":\"I64\",\"shape\":[1],\"data_offsets\":[0,8]}}", 8, "I64");
	}

	/** A byte range past the end of the file is rejected when the header is read, not when the tensor is. */
	@Test(timeout = 60000)
	public void rejectsRangeOutsideData() throws IOException {
		assertRejected("{\"w\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[100,104]}}", 4, "100..104");
	}

	/** A byte range that ends one byte past the data is rejected; one that ends exactly at the end is not. */
	@Test(timeout = 60000)
	public void rejectsRangeOneBytePastData() throws IOException {
		assertRejected("{\"w\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[1,5]}}", 4, "1..5");

		Map<String, SafetensorsReference> tensors = SafetensorsReference.locate(
				writeFile("{\"w\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]}}", 4));
		Assert.assertEquals(1, tensors.size());
		Assert.assertEquals(1, tensors.get("w").getCount());
	}

	/** A byte range whose end precedes its beginning is rejected. */
	@Test(timeout = 60000)
	public void rejectsReversedRange() throws IOException {
		assertRejected("{\"w\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[8,4]}}", 8, "8..4");
	}

	/** A byte range starting before the tensor data is rejected. */
	@Test(timeout = 60000)
	public void rejectsNegativeRange() throws IOException {
		assertRejected("{\"w\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[-4,0]}}", 4, "-4..0");
	}

	/** A byte range whose length does not match the tensor's shape and element type is rejected. */
	@Test(timeout = 60000)
	public void rejectsRangeNotMatchingShape() throws IOException {
		assertRejected("{\"w\":{\"dtype\":\"F32\",\"shape\":[2],\"data_offsets\":[0,4]}}", 8, "occupy 8");
	}

	/** A header length larger than the file is rejected before any of the header is parsed. */
	@Test(timeout = 60000)
	public void rejectsHeaderLongerThanFile() throws IOException {
		ByteBuffer bytes = ByteBuffer.allocate(Long.BYTES + 4).order(ByteOrder.LITTLE_ENDIAN);
		bytes.putLong(1000);
		Path file = Files.createTempFile("truncated", ".safetensors");
		Files.write(file, bytes.array());

		try {
			SafetensorsReference.locate(file.toFile());
			Assert.fail("A header longer than the file should be rejected");
		} catch (IllegalArgumentException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("not a safetensors file"));
		}
	}

	/** A tensor with an axis of length zero holds no values and is left out; the others are kept. */
	@Test(timeout = 60000)
	public void omitsEmptyTensor() throws IOException {
		Map<String, SafetensorsReference> tensors = SafetensorsReference.locate(writeFile("{"
				+ "\"empty\":{\"dtype\":\"F32\",\"shape\":[0,3],\"data_offsets\":[0,0]},"
				+ "\"w\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]}}", 4));
		Assert.assertEquals(List.of("w"), List.copyOf(tensors.keySet()));
	}

	/** An axis of negative length is rejected. */
	@Test(timeout = 60000)
	public void rejectsNegativeAxis() throws IOException {
		assertRejected("{\"w\":{\"dtype\":\"F32\",\"shape\":[-1,2],\"data_offsets\":[0,0]}}", 4, "length -1");
	}

	/**
	 * An empty tensor whose range is not itself empty is rejected rather than silently omitted:
	 * a zero-length axis does not excuse a malformed {@code data_offsets}.
	 */
	@Test(timeout = 60000)
	public void rejectsEmptyTensorWithNonEmptyRange() throws IOException {
		assertRejected("{\"empty\":{\"dtype\":\"F32\",\"shape\":[0,3],\"data_offsets\":[0,4]}}", 4,
				"holds no values");
	}

	/** An empty tensor whose range falls outside the data region is rejected, not omitted. */
	@Test(timeout = 60000)
	public void rejectsEmptyTensorWithOutOfRangeOffsets() throws IOException {
		assertRejected("{\"empty\":{\"dtype\":\"F32\",\"shape\":[0,3],\"data_offsets\":[100,100]}}", 4,
				"100..100");
	}

	/** A tensor entry missing its {@code dtype} is rejected with a message naming the field. */
	@Test(timeout = 60000)
	public void rejectsMissingDtype() throws IOException {
		assertRejected("{\"w\":{\"shape\":[1],\"data_offsets\":[0,4]}}", 4, "no dtype");
	}

	/** A tensor entry missing its {@code shape} is rejected with a message naming the field. */
	@Test(timeout = 60000)
	public void rejectsMissingShape() throws IOException {
		assertRejected("{\"w\":{\"dtype\":\"F32\",\"data_offsets\":[0,4]}}", 4, "no shape");
	}

	/** A tensor entry missing its {@code data_offsets} is rejected with a message naming the field. */
	@Test(timeout = 60000)
	public void rejectsMissingDataOffsets() throws IOException {
		assertRejected("{\"w\":{\"dtype\":\"F32\",\"shape\":[1]}}", 4, "no data_offsets");
	}

	/** A {@code data_offsets} that is not a two-element [begin, end] range is rejected. */
	@Test(timeout = 60000)
	public void rejectsDataOffsetsWrongLength() throws IOException {
		assertRejected("{\"w\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4,8]}}", 8,
				"3 data offsets");
	}

	/** A header that is not a JSON object is rejected as not a safetensors file. */
	@Test(timeout = 60000)
	public void rejectsNonObjectHeader() throws IOException {
		assertRejected("[1,2,3]", 4, "not a JSON object");
	}

	/** Syntactically invalid JSON in the header is rejected as not a safetensors file. */
	@Test(timeout = 60000)
	public void rejectsMalformedJsonHeader() throws IOException {
		assertRejected("{not json", 4, "not a JSON object");
	}

	/** A tensor described by something other than a JSON object is rejected, naming the tensor. */
	@Test(timeout = 60000)
	public void rejectsNonObjectTensorEntry() throws IOException {
		assertRejected("{\"w\":42}", 4, "is not described by a JSON object");
	}

	/** A {@code dtype} that is not a JSON string is rejected, naming the field. */
	@Test(timeout = 60000)
	public void rejectsNonStringDtype() throws IOException {
		assertRejected("{\"w\":{\"dtype\":{\"x\":1},\"shape\":[1],\"data_offsets\":[0,4]}}", 4,
				"malformed dtype");
		assertRejected("{\"w\":{\"dtype\":[\"F32\"],\"shape\":[1],\"data_offsets\":[0,4]}}", 4,
				"malformed dtype");
	}

	/** A {@code shape} that is a scalar rather than an array is rejected, naming the field. */
	@Test(timeout = 60000)
	public void rejectsScalarShape() throws IOException {
		assertRejected("{\"w\":{\"dtype\":\"F32\",\"shape\":1,\"data_offsets\":[0,4]}}", 4,
				"malformed shape");
	}

	/** A {@code shape} whose axes are not integers is rejected, naming the field. */
	@Test(timeout = 60000)
	public void rejectsNonIntegerAxis() throws IOException {
		assertRejected("{\"w\":{\"dtype\":\"F32\",\"shape\":[\"a\"],\"data_offsets\":[0,4]}}", 4,
				"malformed shape");
		assertRejected("{\"w\":{\"dtype\":\"F32\",\"shape\":[1.5],\"data_offsets\":[0,4]}}", 4,
				"malformed shape");
		assertRejected("{\"w\":{\"dtype\":\"F32\",\"shape\":[[1]],\"data_offsets\":[0,4]}}", 4,
				"malformed shape");
	}

	/** An axis longer than any collection can be is rejected rather than truncated. */
	@Test(timeout = 60000)
	public void rejectsAxisBeyondIntRange() throws IOException {
		assertRejected("{\"w\":{\"dtype\":\"F32\",\"shape\":[4294967297],\"data_offsets\":[0,4]}}", 4,
				"length 4294967297");
	}

	/** A {@code data_offsets} that is a scalar rather than an array is rejected, naming the field. */
	@Test(timeout = 60000)
	public void rejectsScalarDataOffsets() throws IOException {
		assertRejected("{\"w\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":4}}", 4,
				"malformed data_offsets");
	}

	/** A {@code data_offsets} holding something other than integers is rejected, naming the field. */
	@Test(timeout = 60000)
	public void rejectsNonIntegerDataOffsets() throws IOException {
		assertRejected("{\"w\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,null]}}", 4,
				"malformed data_offsets");
		assertRejected("{\"w\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,3.5]}}", 4,
				"malformed data_offsets");
	}

	/**
	 * Writes a safetensors file with the given header followed by {@code dataBytes} zero bytes of
	 * tensor data.
	 *
	 * @param header    the JSON header
	 * @param dataBytes number of bytes of tensor data after the header
	 * @return the file
	 */
	private File writeFile(String header, int dataBytes) throws IOException {
		byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
		ByteBuffer bytes = ByteBuffer.allocate(Long.BYTES + headerBytes.length + dataBytes)
				.order(ByteOrder.LITTLE_ENDIAN);
		bytes.putLong(headerBytes.length).put(headerBytes);

		Path file = Files.createTempFile("tensors", ".safetensors");
		Files.write(file, bytes.array());
		return file.toFile();
	}

	/**
	 * Asserts that locating the tensors of a file with the given header fails, and that the
	 * failure mentions {@code expected}.
	 *
	 * @param header    the JSON header
	 * @param dataBytes number of bytes of tensor data after the header
	 * @param expected  text the failure message must contain
	 */
	private void assertRejected(String header, int dataBytes, String expected) throws IOException {
		File file = writeFile(header, dataBytes);
		try {
			SafetensorsReference.locate(file);
			Assert.fail("The header " + header + " should be rejected");
		} catch (IllegalArgumentException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains(expected));
		}
	}
}
