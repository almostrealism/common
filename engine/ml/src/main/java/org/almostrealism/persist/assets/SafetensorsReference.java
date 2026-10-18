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
import com.google.gson.JsonPrimitive;
import io.almostrealism.code.Precision;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.io.Bits;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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

	/**
	 * The longest header that can be read: the header is decoded from a single array, which
	 * cannot be longer than this. This is the format's technical ceiling, not a limit a genuine
	 * checkpoint approaches; {@link #MAX_SAFE_HEADER_LENGTH} is the much smaller length this reader
	 * is willing to allocate for an untrusted file.
	 */
	public static final long MAX_HEADER_LENGTH = Integer.MAX_VALUE - 8;

	/**
	 * The largest header this reader will allocate a buffer for. The declared header length is read
	 * from the file before any of the header is parsed, so an untrusted file can name a length of
	 * nearly {@link #MAX_HEADER_LENGTH} and, if the file is long enough (a sparse file costs almost
	 * no disk), force a multi-gigabyte allocation that exhausts the heap before a single byte is
	 * validated. A header is a small JSON object naming each tensor; no genuine checkpoint comes
	 * near this cap, which matches the reference safetensors implementation's 100&nbsp;MB limit.
	 */
	public static final long MAX_SAFE_HEADER_LENGTH = 100_000_000L;

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
	 * @throws IllegalArgumentException if the file is too short to hold a header, the header is
	 *         longer than {@link #MAX_HEADER_LENGTH} or than the {@link #MAX_SAFE_HEADER_LENGTH}
	 *         this reader will allocate, the header is malformed, names an element type other
	 *         than BF16, F16, F32 or F64, gives a tensor a byte range that is reversed, lies
	 *         outside the file's tensor data, or does not match its shape, or the tensors' byte
	 *         ranges do not tile the tensor data exactly (see {@link #requireTiled})
	 */
	public static Map<String, SafetensorsReference> locate(File file) throws IOException {
		JsonObject header;
		long dataStart;
		long dataLength;

		try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
			long fileLength = in.length();
			if (fileLength < Long.BYTES) {
				throw new IllegalArgumentException(file + " is not a safetensors file: its " + fileLength
						+ " bytes cannot hold the " + Long.BYTES + "-byte header length");
			}

			byte[] lengthBytes = new byte[Long.BYTES];
			in.readFully(lengthBytes);
			long headerLength = ByteBuffer.wrap(lengthBytes)
					.order(CollectionDataMemoryProvider.VALUE_ORDER).getLong();
			if (headerLength <= 0 || headerLength > fileLength - Long.BYTES) {
				throw new IllegalArgumentException(file + " is not a safetensors file: its header length "
						+ headerLength + " does not fit in its " + fileLength + " bytes");
			} else if (headerLength > MAX_HEADER_LENGTH) {
				throw new IllegalArgumentException(file + " has a header of " + headerLength
						+ " bytes, but a header can be at most " + MAX_HEADER_LENGTH + " bytes");
			} else if (headerLength > MAX_SAFE_HEADER_LENGTH) {
				throw new IllegalArgumentException(file + " has a header of " + headerLength
						+ " bytes, which exceeds the " + MAX_SAFE_HEADER_LENGTH
						+ "-byte limit this reader will allocate for a checkpoint header");
			}

			byte[] headerBytes = new byte[(int) headerLength];
			in.readFully(headerBytes);
			try {
				header = JsonParser.parseString(new String(headerBytes, StandardCharsets.UTF_8)).getAsJsonObject();
			} catch (RuntimeException e) {
				throw new IllegalArgumentException(file + " is not a safetensors file: its header is not a JSON object", e);
			}
			dataStart = Long.BYTES + headerLength;
			dataLength = fileLength - dataStart;
		}

		Map<String, SafetensorsReference> tensors = new LinkedHashMap<>();
		List<Range> ranges = new ArrayList<>();
		for (Map.Entry<String, JsonElement> entry : header.entrySet()) {
			if ("__metadata__".equals(entry.getKey())) continue;

			JsonObject tensorEntry;
			try {
				tensorEntry = entry.getValue().getAsJsonObject();
			} catch (RuntimeException e) {
				throw new IllegalArgumentException(entry.getKey() + " in " + file
						+ " is not described by a JSON object", e);
			}

			SafetensorsReference tensor = locateTensor(file, entry.getKey(),
					tensorEntry, dataStart, dataLength, ranges);
			if (tensor != null) tensors.put(entry.getKey(), tensor);
		}

		requireTiled(file, ranges, dataLength);
		return tensors;
	}

	/**
	 * The byte range a tensor occupies within a file's tensor data.
	 *
	 * @param name  the tensor's name, for error messages
	 * @param begin position of its first byte
	 * @param end   position just past its last byte
	 */
	private record Range(String name, long begin, long end) { }

	/**
	 * Requires the tensors' byte ranges to tile the tensor data exactly, as the safetensors format
	 * does: taken in order of position, each range begins where the previous one ended, the first
	 * begins at zero and the last ends at the end of the file. A range that overlaps another would
	 * expose the same bytes as two different tensors, and bytes that belong to no tensor are not
	 * part of a well-formed file, so either is rejected rather than read.
	 *
	 * @param file       the file, for error messages
	 * @param ranges     every tensor's byte range, including those of tensors with no values
	 * @param dataLength number of bytes of tensor data in the file
	 * @throws IllegalArgumentException if two ranges overlap, or some bytes belong to no range
	 */
	private static void requireTiled(File file, List<Range> ranges, long dataLength) {
		ranges.sort(Comparator.comparingLong(Range::begin).thenComparingLong(Range::end));

		long position = 0;
		String previous = null;
		for (Range range : ranges) {
			if (range.begin() < position) {
				throw new IllegalArgumentException(range.name() + " in " + file + " occupies bytes "
						+ range.begin() + ".." + range.end() + ", which overlap " + previous
						+ ", ending at byte " + position);
			} else if (range.begin() > position) {
				throw new IllegalArgumentException("bytes " + position + ".." + range.begin()
						+ " of the tensor data in " + file + " belong to no tensor");
			}

			position = range.end();
			previous = range.name();
		}

		if (position != dataLength) {
			throw new IllegalArgumentException("bytes " + position + ".." + dataLength
					+ " of the tensor data in " + file + " belong to no tensor");
		}
	}

	/**
	 * Returns a required header field converted by {@code read}, failing with a message that names
	 * the tensor and file when the field is absent or is not of the JSON type {@code read} expects.
	 *
	 * @param file  the file, for error messages
	 * @param name  the tensor's name, for error messages
	 * @param field the name of the required field
	 * @param entry the tensor's header entry
	 * @param read  converts the field's JSON value
	 * @param <T>   the type of the converted value
	 * @return the converted value
	 * @throws IllegalArgumentException if {@code entry} has no such field or it cannot be converted
	 */
	private static <T> T require(File file, String name, String field, JsonObject entry,
								 Function<JsonElement, T> read) {
		JsonElement value = entry.get(field);
		if (value == null) {
			throw new IllegalArgumentException(name + " in " + file + " has no " + field);
		}

		try {
			return read.apply(value);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException(name + " in " + file + " has a malformed "
					+ field + ": " + value, e);
		}
	}

	/**
	 * Reads a JSON array of integers. An element must be a JSON number with no fractional part;
	 * a numeric string such as {@code "4"} is not a JSON integer and is rejected, so a header that
	 * quotes its shape or offsets does not pass as a well-formed one.
	 *
	 * @param element the array
	 * @return its values
	 * @throws IllegalStateException if {@code element} is not an array
	 * @throws RuntimeException if an element is not an integer
	 */
	private static long[] longs(JsonElement element) {
		JsonArray array = element.getAsJsonArray();
		long[] values = new long[array.size()];
		for (int i = 0; i < values.length; i++) {
			JsonPrimitive value = array.get(i).getAsJsonPrimitive();
			if (!value.isNumber()) {
				throw new IllegalArgumentException("element " + i + " (" + value + ") is not an integer");
			}
			values[i] = value.getAsBigDecimal().longValueExact();
		}
		return values;
	}

	/**
	 * Locates one tensor from its header entry.
	 *
	 * @param file      the file, for error messages
	 * @param name      the tensor's name
	 * @param entry     its header entry: {@code dtype}, {@code shape} and {@code data_offsets}
	 * @param dataStart  byte position where the tensors' data begins
	 * @param dataLength number of bytes from {@code dataStart} to the end of the file
	 * @param ranges     receives the tensor's byte range, for {@link #requireTiled}
	 * @return the reference, or {@code null} if the tensor holds no values
	 * @throws IllegalArgumentException if the entry is missing {@code dtype}, {@code shape} or
	 *         {@code data_offsets} or gives one of them a value of the wrong JSON type (a
	 *         {@code dtype} that is not a string, or a {@code shape} or {@code data_offsets} that
	 *         is not an array of integers), names an unreadable element type, or gives a byte range that
	 *         is not a {@code [begin, end]} pair within the file's tensor data for its shape
	 */
	private static SafetensorsReference locateTensor(File file, String name, JsonObject entry,
													 long dataStart, long dataLength,
													 List<Range> ranges) {
		Encoding encoding;
		String dtype = require(file, name, "dtype", entry, e -> e.getAsJsonPrimitive().getAsString());
		try {
			encoding = Encoding.valueOf(dtype);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(name + " in " + file + " is stored as " + dtype
					+ "; only BF16, F16, F32 and F64 tensors can be read", e);
		}

		long[] dims = require(file, name, "shape", entry, SafetensorsReference::longs);
		boolean empty = false;
		int[] shape = new int[Math.max(1, dims.length)];
		shape[0] = 1;
		for (int i = 0; i < dims.length; i++) {
			if (dims[i] < 0 || dims[i] > Integer.MAX_VALUE) {
				throw new IllegalArgumentException(name + " in " + file + " has an axis of length " + dims[i]);
			}
			shape[i] = (int) dims[i];
			if (shape[i] == 0) empty = true;
		}

		long[] offsets = require(file, name, "data_offsets", entry, SafetensorsReference::longs);
		if (offsets.length != 2) {
			throw new IllegalArgumentException(name + " in " + file + " has " + offsets.length
					+ " data offsets, but a tensor occupies a single [begin, end] byte range");
		}
		long begin = offsets[0];
		long end = offsets[1];
		if (begin < 0 || end < begin || end > dataLength) {
			throw new IllegalArgumentException(name + " in " + file + " occupies bytes " + begin + ".." + end
					+ ", which is not a range within the file's " + dataLength + " bytes of tensor data");
		}
		ranges.add(new Range(name, begin, end));

		// A tensor with a zero-length axis holds no values and is left out, but its range is
		// still validated above and must itself be empty, so a malformed entry is not accepted.
		if (empty) {
			if (end != begin) {
				throw new IllegalArgumentException(name + " in " + file + " occupies " + (end - begin)
						+ " bytes, but a tensor with a zero-length axis holds no values");
			}
			return null;
		}

		TraversalPolicy policy = new TraversalPolicy(shape);
		long expected = policy.getTotalSizeLong() * encoding.getWidth().bytes();
		if (end - begin != expected) {
			throw new IllegalArgumentException(name + " in " + file + " occupies " + (end - begin)
					+ " bytes, but " + policy + " values of " + dtype + " occupy " + expected);
		}

		return new SafetensorsReference(policy, encoding, dataStart + begin, policy.getTotalSize());
	}
}
