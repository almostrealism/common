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

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Parser;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The bytes of a protobuf message, whose fields can be found without being
 * decoded.
 *
 * <p>Parsing a message turns all of it into objects, which is the wrong price
 * to pay for finding out where one field is. This walks the encoding instead,
 * skipping field contents rather than reading them, so locating something
 * costs what reading its structure costs and nothing more.</p>
 *
 * <p>A message knows where it sits in the file it came from, so the position
 * it reports for a field is a position in that file. That is what makes a
 * field addressable by something that will read it later from somewhere
 * else.</p>
 */
public class EncodedMessage {
	/** The bytes of this message. */
	private final ByteBuffer bytes;

	/** Byte position of this message within the file it came from. */
	private final long offset;

	/**
	 * Creates a message over the given bytes.
	 *
	 * @param bytes  the bytes of the message
	 * @param offset byte position of those bytes within the file
	 */
	public EncodedMessage(ByteBuffer bytes, long offset) {
		this.bytes = bytes;
		this.offset = offset;
	}

	/** Returns the byte position of this message within its file. */
	public long getOffset() { return offset; }

	/** Returns the number of bytes this message occupies. */
	public int size() { return bytes.remaining(); }

	/** Returns whether this message carries the given length-delimited field. */
	public boolean has(int field) { return locate(field, true) >= 0; }

	/**
	 * Returns where the contents of the given field begin, as a position
	 * within the file this message came from.
	 *
	 * @param field the field number
	 * @return the position, or {@code -1} if the field is absent
	 */
	public long positionOf(int field) {
		long within = locate(field, true);
		return within < 0 ? -1 : offset + within;
	}

	/**
	 * Returns how many bytes the contents of the given field occupy.
	 *
	 * @param field the field number
	 * @return the length, or {@code -1} if the field is absent
	 */
	public int lengthOf(int field) { return (int) locate(field, false); }

	/**
	 * Returns the message held by the given field.
	 *
	 * @param field the field number
	 * @return the message, or {@code null} if the field is absent
	 */
	public EncodedMessage field(int field) {
		long within = locate(field, true);
		if (within < 0) return null;

		return new EncodedMessage(
				bytes.slice((int) within, (int) locate(field, false)),
				offset + within);
	}

	/**
	 * Returns every message held by the given repeated field, in the order
	 * they were written.
	 *
	 * <p>{@link #field(int)} answers with the first, which is all a singular
	 * field has. A repeated field needs all of them, and a library of tensors
	 * or a batch of records is exactly that.</p>
	 *
	 * @param field the field number
	 * @return the messages, empty if the field is absent
	 */
	public List<EncodedMessage> fields(int field) {
		List<EncodedMessage> found = new ArrayList<>();
		CodedInputStream in = CodedInputStream.newInstance(bytes.duplicate());

		try {
			while (true) {
				int tag = in.readTag();
				if (tag == 0) return found;

				if (WireFormat.getTagFieldNumber(tag) == field &&
						WireFormat.getTagWireType(tag) ==
								WireFormat.WIRETYPE_LENGTH_DELIMITED) {
					int size = in.readRawVarint32();
					int start = in.getTotalBytesRead();

					found.add(new EncodedMessage(
							bytes.slice(start, size), offset + start));
					in.skipRawBytes(size);
				} else {
					in.skipField(tag);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(
					"Unable to read the structure of the message at " + offset, e);
		}
	}

	/**
	 * Returns the contents of the given field read as a string.
	 *
	 * @param field the field number
	 * @return the string, or {@code null} if the field is absent
	 */
	public String stringOf(int field) {
		EncodedMessage value = field(field);
		if (value == null) return null;

		byte[] raw = new byte[value.size()];
		value.bytes.duplicate().get(raw);
		return new String(raw, StandardCharsets.UTF_8);
	}

	/**
	 * Returns the message reached by descending the given field numbers,
	 * outermost first. An empty path returns this message.
	 *
	 * @param path the field numbers to descend
	 * @return the message reached, or {@code null} if the path leads nowhere
	 */
	public EncodedMessage descend(int... path) {
		EncodedMessage current = this;

		for (int field : path) {
			if (current == null) return null;
			current = current.field(field);
		}

		return current;
	}

	/**
	 * Parses this message in full.
	 *
	 * <p>For the small parts, where structure is the content: a shape, a key.
	 * Parsing something whose bulk is values defeats the purpose of locating
	 * it.</p>
	 *
	 * @param parser the parser for this message's type
	 * @param <T>    the message type
	 * @return the parsed message
	 */
	public <T> T parse(Parser<T> parser) {
		try {
			return parser.parseFrom(bytes.duplicate());
		} catch (IOException e) {
			throw new UncheckedIOException("Unable to parse message at " + offset, e);
		}
	}

	/**
	 * Walks this message's fields to find one, reporting either where its
	 * contents begin within this message or how long they are.
	 *
	 * @param field    the field number to find
	 * @param wantsEnd whether to report the position of the contents rather
	 *                 than their length
	 * @return the position or length, or {@code -1} if the field is absent
	 */
	private long locate(int field, boolean wantsEnd) {
		CodedInputStream in = CodedInputStream.newInstance(bytes.duplicate());

		try {
			while (true) {
				int tag = in.readTag();
				if (tag == 0) return -1;

				if (WireFormat.getTagFieldNumber(tag) == field &&
						WireFormat.getTagWireType(tag) ==
								WireFormat.WIRETYPE_LENGTH_DELIMITED) {
					int size = in.readRawVarint32();
					return wantsEnd ? in.getTotalBytesRead() : size;
				}

				in.skipField(tag);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(
					"Unable to read the structure of the message at " + offset, e);
		}
	}
}
