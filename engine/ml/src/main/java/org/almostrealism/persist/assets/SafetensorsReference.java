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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.almostrealism.code.Precision;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.io.Bits;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tensor in a safetensors file, located but not read.
 *
 * <p>Safetensors is the format most published checkpoints are distributed in: an 8-byte
 * little-endian header length, a JSON header giving each tensor's element type, shape and byte
 * range, and then the tensors' raw little-endian bytes. Nothing in the header needs the values,
 * so a file is located by reading its header alone, and each tensor is read through a mapping
 * of the file when something reads it, exactly as {@link CollectionDataReference} does for
 * protobuf collection data. A checkpoint read this way costs neither the Java heap nor a device
 * until a kernel uses it.</p>
 *
 * <p>This is a way to read checkpoints, not a format the project writes: weights a program keeps
 * or shares are saved as protobuf, which other languages, services and message definitions can
 * use directly. A checkpoint read from safetensors can be saved that way with
 * {@code StateDictionary.save}.</p>
 */
public class SafetensorsReference extends CollectionDataReference {

	/** How the values of a tensor are stored, as the header's {@code dtype} names it. */
	public enum Encoding {
		/** Brain floating point: the upper 16 bits of an IEEE single-precision value. */
		BF16(Precision.FP16) {
			@Override
			double decode(ByteBuffer buffer, int at) {
				return Float.intBitsToFloat((buffer.getShort(at) & 0xFFFF) << 16);
			}
		},
		/** IEEE half precision. */
		F16(Precision.FP16) {
			@Override
			double decode(ByteBuffer buffer, int at) {
				return Bits.float16ToFloat(buffer.getShort(at));
			}
		},
		/** IEEE single precision. */
		F32(Precision.FP32) {
			@Override
			double decode(ByteBuffer buffer, int at) {
				return buffer.getFloat(at);
			}
		},
		/** IEEE double precision. */
		F64(Precision.FP64) {
			@Override
			double decode(ByteBuffer buffer, int at) {
				return buffer.getDouble(at);
			}
		};

		/** The precision with the same width, which sets how many bytes each value occupies. */
		private final Precision width;

		/**
		 * Creates an encoding whose values each occupy the width of the given precision.
		 *
		 * @param width the precision with the same width
		 */
		Encoding(Precision width) {
			this.width = width;
		}

		/** Returns the precision whose width matches this encoding. */
		public Precision getWidth() { return width; }

		/**
		 * Decodes the value stored at a byte position.
		 *
		 * @param buffer the file's bytes, in little-endian order
		 * @param at     the byte position of the value
		 * @return the value
		 */
		abstract double decode(ByteBuffer buffer, int at);
	}

	/** How this tensor's values are stored. */
	private final Encoding encoding;

	/**
	 * Creates a reference to a located tensor.
	 *
	 * @param shape       the tensor's shape
	 * @param encoding    how its values are stored
	 * @param valueOffset byte position of its first value within the file
	 * @param count       number of values
	 */
	protected SafetensorsReference(TraversalPolicy shape, Encoding encoding,
								   long valueOffset, int count) {
		super(shape, encoding.getWidth(), valueOffset, count);
		this.encoding = encoding;
	}

	/** Returns how this tensor's values are stored. */
	public Encoding getEncoding() { return encoding; }

	@Override
	protected double valueAt(ByteBuffer buffer, int index) {
		return encoding.decode(buffer, (int) (getValueOffset() + (long) index * getPrecision().bytes()));
	}

	/**
	 * Locates every tensor in a safetensors file by reading its header, without reading any
	 * values. A tensor with no values (an axis of length zero) is left out, as
	 * {@link CollectionDataReference#of} leaves one out of protobuf data; a tensor with no axes
	 * (a scalar) has shape {@code [1]}.
	 *
	 * @param file the safetensors file
	 * @return the tensors by name, in the order the header lists them
	 * @throws IOException if the file cannot be read
	 * @throws IllegalArgumentException if the header is malformed, names an element type other
	 *         than BF16, F16, F32 or F64, or gives a tensor a byte range that is reversed, lies
	 *         outside the file's tensor data, or does not match its shape
	 */
	public static Map<String, SafetensorsReference> locate(File file) throws IOException {
		JsonObject header;
		long dataStart;
		long dataLength;

		try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
			byte[] lengthBytes = new byte[Long.BYTES];
			in.readFully(lengthBytes);
			long headerLength = ByteBuffer.wrap(lengthBytes)
					.order(CollectionDataMemoryProvider.VALUE_ORDER).getLong();
			if (headerLength <= 0 || headerLength > in.length() - Long.BYTES) {
				throw new IllegalArgumentException(file + " is not a safetensors file: its header length "
						+ headerLength + " does not fit in its " + in.length() + " bytes");
			}

			byte[] headerBytes = new byte[(int) headerLength];
			in.readFully(headerBytes);
			header = JsonParser.parseString(new String(headerBytes, StandardCharsets.UTF_8)).getAsJsonObject();
			dataStart = Long.BYTES + headerLength;
			dataLength = in.length() - dataStart;
		}

		Map<String, SafetensorsReference> tensors = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : header.entrySet()) {
			if ("__metadata__".equals(entry.getKey())) continue;

			SafetensorsReference tensor = locateTensor(file, entry.getKey(),
					entry.getValue().getAsJsonObject(), dataStart, dataLength);
			if (tensor != null) tensors.put(entry.getKey(), tensor);
		}
		return tensors;
	}

	/**
	 * Locates one tensor from its header entry.
	 *
	 * @param file      the file, for error messages
	 * @param name      the tensor's name
	 * @param entry     its header entry: {@code dtype}, {@code shape} and {@code data_offsets}
	 * @param dataStart  byte position where the tensors' data begins
	 * @param dataLength number of bytes from {@code dataStart} to the end of the file
	 * @return the reference, or {@code null} if the tensor holds no values
	 */
	private static SafetensorsReference locateTensor(File file, String name, JsonObject entry,
													 long dataStart, long dataLength) {
		Encoding encoding;
		String dtype = entry.get("dtype").getAsString();
		try {
			encoding = Encoding.valueOf(dtype);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(name + " in " + file + " is stored as " + dtype
					+ "; only BF16, F16, F32 and F64 tensors can be read", e);
		}

		JsonArray dims = entry.getAsJsonArray("shape");
		int[] shape = new int[Math.max(1, dims.size())];
		shape[0] = 1;
		for (int i = 0; i < dims.size(); i++) {
			shape[i] = dims.get(i).getAsInt();
			if (shape[i] < 0) {
				throw new IllegalArgumentException(name + " in " + file + " has an axis of length " + shape[i]);
			}
			if (shape[i] == 0) return null;
		}
		TraversalPolicy policy = new TraversalPolicy(shape);

		JsonArray offsets = entry.getAsJsonArray("data_offsets");
		long begin = offsets.get(0).getAsLong();
		long end = offsets.get(1).getAsLong();
		if (begin < 0 || end < begin || end > dataLength) {
			throw new IllegalArgumentException(name + " in " + file + " occupies bytes " + begin + ".." + end
					+ ", which is not a range within the file's " + dataLength + " bytes of tensor data");
		}

		long expected = policy.getTotalSizeLong() * encoding.getWidth().bytes();
		if (end - begin != expected) {
			throw new IllegalArgumentException(name + " in " + file + " occupies " + (end - begin)
					+ " bytes, but " + policy + " values of " + dtype + " occupy " + expected);
		}

		return new SafetensorsReference(policy, encoding, dataStart + begin, policy.getTotalSize());
	}
}
