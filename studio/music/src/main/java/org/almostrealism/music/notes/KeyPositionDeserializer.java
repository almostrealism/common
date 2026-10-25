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

package org.almostrealism.music.notes;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import org.almostrealism.audio.tone.KeyPosition;

import java.io.IOException;
import java.util.function.Function;

/**
 * Reads a stored {@link KeyPosition} from its name, through the factory that is the source
 * of truth for resolving names of that type.
 *
 * <p>A key position is stored as its name, either alone (such as {@code "C1"}) or, when
 * written with type information, as a two-element array of class name and name. Either way
 * the name is passed to the factory, and a name the factory does not recognise is refused
 * rather than read as some other key.</p>
 *
 * @param <T> the type of key position read
 * @see Named
 */
public class KeyPositionDeserializer<T extends KeyPosition<?>> extends StdDeserializer<T> {
	/** Resolves a stored name to the key position it names. */
	private final transient Function<String, T> factory;

	/**
	 * Creates a deserializer for the given type of key position.
	 *
	 * @param type    the type of key position read
	 * @param factory resolves a stored name to the key position it names
	 */
	public KeyPositionDeserializer(Class<?> type, Function<String, T> factory) {
		super(type);
		this.factory = factory;
	}

	/**
	 * Reads the stored name and resolves it with the factory.
	 *
	 * @param parser  the parser positioned on the stored key position
	 * @param context the deserialization context
	 * @return the key position the stored name resolves to
	 * @throws IOException if the stored value cannot be read or names no key position
	 */
	@Override
	public T deserialize(JsonParser parser, DeserializationContext context) throws IOException {
		String name;

		if (parser.currentToken() == JsonToken.START_ARRAY) {
			parser.nextToken();
			parser.nextToken();
			name = parser.getValueAsString();
			parser.nextToken();
		} else {
			name = parser.getValueAsString();
		}

		try {
			return factory.apply(name);
		} catch (IllegalArgumentException e) {
			throw context.weirdStringException(name, handledType(), "not the name of a key position");
		}
	}

	/**
	 * Reads any stored {@link KeyPosition} through {@link KeyPosition#of(String)}. It has the
	 * no-argument constructor that {@code @JsonDeserialize(using = ...)} requires.
	 */
	public static class Named extends KeyPositionDeserializer<KeyPosition<?>> {
		/** Creates a deserializer that resolves names with {@link KeyPosition#of(String)}. */
		public Named() {
			super(KeyPosition.class, KeyPosition::of);
		}
	}
}
