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

package org.almostrealism.studio.persistence;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Migrates serialized file content from old fully-qualified class names
 * to their current locations after the package reorganization. This allows
 * legacy JSON files (via Jackson {@code @JsonTypeInfo}) and XML files (via
 * {@link java.beans.XMLDecoder}) to be deserialized without manual edits.
 *
 * <p>Migration works by replacing old class name strings in the serialized
 * content before it reaches the deserializer. Two mapping layers are applied
 * in order:</p>
 * <ol>
 *   <li><b>Exact mappings</b> for classes whose destination does not follow
 *       the general prefix pattern (e.g. {@code audio.notes.AudioChoiceNode}
 *       moved to {@code studio.notes} rather than {@code music.notes}).</li>
 *   <li><b>Prefix mappings</b> for bulk package renames, checked most-specific
 *       first.</li>
 * </ol>
 *
 * <h3>Usage with Jackson</h3>
 * <pre>{@code
 * ObjectMapper mapper = AudioScene.defaultMapper();
 * NoteAudioChoiceList list = mapper.readValue(
 *         MigrationClassLoader.migrateStream(new FileInputStream(file)),
 *         NoteAudioChoiceList.class);
 * }</pre>
 *
 * <h3>Usage with XMLDecoder</h3>
 * <pre>{@code
 * XMLDecoder dec = new XMLDecoder(
 *         MigrationClassLoader.migrateStream(new FileInputStream(file)));
 * }</pre>
 */
public class MigrationClassLoader {

	/**
	 * Exact old-FQCN &rarr; new-FQCN overrides for classes that moved to
	 * a package not predictable from the general prefix rules.
	 */
	private static final Map<String, String> EXACT_MAPPINGS;

	/**
	 * Ordered old-prefix &rarr; new-prefix replacements. Most-specific
	 * prefixes appear first so that a match on
	 * {@code org.almostrealism.audio.notes.} is found before
	 * {@code org.almostrealism.audio.}.
	 */
	private static final Map<String, String> PREFIX_MAPPINGS;

	static {
		Map<String, String> exact = new LinkedHashMap<>();
		exact.put("org.almostrealism.audio.notes.AudioChoiceNode",
				"org.almostrealism.studio.notes.AudioChoiceNode");
		exact.put("org.almostrealism.audio.notes.ChannelAudioNode",
				"org.almostrealism.studio.notes.ChannelAudioNode");
		exact.put("org.almostrealism.audio.notes.MultiSceneAudioNode",
				"org.almostrealism.studio.notes.MultiSceneAudioNode");
		exact.put("org.almostrealism.audio.notes.SceneAudioNode",
				"org.almostrealism.studio.notes.SceneAudioNode");
		EXACT_MAPPINGS = Collections.unmodifiableMap(exact);

		Map<String, String> prefix = new LinkedHashMap<>();
		prefix.put("org.almostrealism.audio.notes.", "org.almostrealism.music.notes.");
		prefix.put("org.almostrealism.audio.filter.", "org.almostrealism.music.filter.");
		prefix.put("org.almostrealism.audio.pattern.", "org.almostrealism.music.pattern.");
		prefix.put("org.almostrealism.audio.arrange.", "org.almostrealism.music.arrange.");
		prefix.put("org.almostrealism.audio.data.", "org.almostrealism.music.data.");
		prefix.put("org.almostrealism.audio.grains.", "org.almostrealism.music.grains.");
		prefix.put("org.almostrealism.audio.sequence.", "org.almostrealism.music.sequence.");
		prefix.put("org.almostrealism.audio.health.", "org.almostrealism.studio.health.");
		prefix.put("org.almostrealism.ml.audio.", "org.almostrealism.studio.ml.");
		prefix.put("com.almostrealism.spatial.", "org.almostrealism.spatial.");
		PREFIX_MAPPINGS = Collections.unmodifiableMap(prefix);
	}

	/**
	 * Translates an old fully-qualified class name to its current name.
	 * Returns the original name unchanged if no mapping applies.
	 *
	 * @param className the potentially outdated fully-qualified class name
	 * @return the current class name, or {@code className} if no mapping matched
	 */
	public static String translate(String className) {
		String exact = EXACT_MAPPINGS.get(className);
		if (exact != null) {
			return exact;
		}

		for (Map.Entry<String, String> entry : PREFIX_MAPPINGS.entrySet()) {
			if (className.startsWith(entry.getKey())) {
				return entry.getValue() + className.substring(entry.getKey().length());
			}
		}

		return className;
	}

	/**
	 * Replaces all old class name references in the given content string
	 * with their current equivalents.
	 *
	 * <p>Exact mappings are applied first, then prefix-based replacements.
	 * This is safe for both XML and JSON content because fully-qualified
	 * class names are unique identifiers that do not collide with data
	 * values.</p>
	 *
	 * @param content the serialized file content
	 * @return the content with all old class names replaced
	 */
	public static String migrateContent(String content) {
		for (Map.Entry<String, String> entry : EXACT_MAPPINGS.entrySet()) {
			content = content.replace(entry.getKey(), entry.getValue());
		}

		for (Map.Entry<String, String> entry : PREFIX_MAPPINGS.entrySet()) {
			content = content.replace(entry.getKey(), entry.getValue());
		}

		return content;
	}

	/**
	 * Reads all bytes from the given stream, replaces old class name
	 * references, and returns a new stream over the migrated content.
	 *
	 * @param in the original input stream (will be fully consumed)
	 * @return a new stream with migrated class names
	 * @throws IOException if reading the stream fails
	 */
	public static InputStream migrateStream(InputStream in) throws IOException {
		String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		String migrated = migrateContent(content);
		return new ByteArrayInputStream(migrated.getBytes(StandardCharsets.UTF_8));
	}
}
