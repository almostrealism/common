package org.almostrealism.persist.assets.test;

import org.almostrealism.collect.PackedCollection;
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
		file.putShort(Float.floatToFloat16(0.5f));
		file.putShort(Float.floatToFloat16(-1.25f));

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
		byte[] header = "{\"ids\":{\"dtype\":\"I64\",\"shape\":[1],\"data_offsets\":[0,8]}}"
				.getBytes(StandardCharsets.UTF_8);
		ByteBuffer bytes = ByteBuffer.allocate(Long.BYTES + header.length + 8).order(ByteOrder.LITTLE_ENDIAN);
		bytes.putLong(header.length).put(header);

		Path file = Files.createTempFile("unsupported", ".safetensors");
		Files.write(file, bytes.array());

		try {
			SafetensorsReference.locate(file.toFile());
			Assert.fail("An I64 tensor should be rejected");
		} catch (IllegalArgumentException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("I64"));
		}
	}
}
