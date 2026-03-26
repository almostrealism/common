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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@link ClassLoader} that transparently maps old fully-qualified class
 * names to their new locations after the package reorganization. This allows
 * legacy JSON files (via Jackson {@code @JsonTypeInfo}) and XML files (via
 * {@link java.beans.XMLDecoder}) to be deserialized without manual edits.
 *
 * <p>Two mapping layers are applied in order:</p>
 * <ol>
 *   <li><b>Exact mappings</b> for classes whose destination does not follow
 *       the general prefix pattern (e.g. {@code audio.notes.AudioChoiceNode}
 *       moved to {@code studio.notes} rather than {@code music.notes}).</li>
 *   <li><b>Prefix mappings</b> for bulk package renames, checked most-specific
 *       first.</li>
 * </ol>
 *
 * <p>If a translated class name cannot be loaded, the loader falls back to
 * the original name so that unmoved classes under old-looking prefixes are
 * not broken.</p>
 *
 * <h3>Usage with Jackson</h3>
 * <pre>{@code
 * ObjectMapper mapper = new ObjectMapper();
 * mapper.setTypeFactory(mapper.getTypeFactory()
 *         .withClassLoader(MigrationClassLoader.getInstance()));
 * }</pre>
 *
 * <h3>Usage with XMLDecoder</h3>
 * <pre>{@code
 * XMLDecoder dec = new XMLDecoder(in, null, null,
 *         MigrationClassLoader.getInstance());
 * }</pre>
 */
public class MigrationClassLoader extends ClassLoader {

	private static volatile MigrationClassLoader instance;

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

	private MigrationClassLoader(ClassLoader parent) {
		super(parent);
	}

	/**
	 * Returns the shared singleton instance, creating it lazily with the
	 * current thread's context class loader as its parent.
	 */
	public static MigrationClassLoader getInstance() {
		if (instance == null) {
			synchronized (MigrationClassLoader.class) {
				if (instance == null) {
					instance = new MigrationClassLoader(
							Thread.currentThread().getContextClassLoader());
				}
			}
		}
		return instance;
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

	@Override
	public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		String translated = translate(name);

		if (!translated.equals(name)) {
			try {
				return super.loadClass(translated, resolve);
			} catch (ClassNotFoundException e) {
				// Fall back to the original name in case the prefix
				// matched but the class was not actually renamed.
			}
		}

		return super.loadClass(name, resolve);
	}
}
