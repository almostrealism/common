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

package org.almostrealism.music.notes.test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.music.notes.FileNoteSource;
import org.almostrealism.music.notes.NoteAudioSource;
import org.almostrealism.music.notes.TreeNoteSource;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pins that a stored note-source root can be read by any {@link ObjectMapper}.
 *
 * <p>The root is declared as the {@code KeyPosition} interface, which Jackson
 * cannot construct on its own. A stored root is the name of a key, resolved
 * by {@code KeyPosition.of}, so a plain mapper with no registered
 * deserializers must still read it, and must still reject a name that is not
 * a key or a value that is not a name.</p>
 */
public class NoteSourceRootDeserializationTest extends TestSuiteBase {
	/** A mapper with no deserializers registered for the root type. */
	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Reads a stored note source, supplying the class type id that
	 * {@link NoteAudioSource} requires of every stored source.
	 *
	 * @param type       the source type to read
	 * @param properties the stored properties, as the body of a JSON object
	 * @param <T>        the source type
	 * @return the source that was read
	 * @throws JsonProcessingException if the stored source cannot be read
	 */
	private <T extends NoteAudioSource> T read(Class<T> type, String properties)
			throws JsonProcessingException {
		return mapper.readValue("{\"@type\":\"" + type.getName() + "\"," + properties + "}", type);
	}

	/** A tree source's stored root is read as the named key. */
	@Test(timeout = 30000)
	public void treeSourceRootIsReadByName() throws JsonProcessingException {
		TreeNoteSource source = read(TreeNoteSource.class, "\"root\":\"C1\"");

		Assert.assertEquals(WesternChromatic.C1, source.getRoot());
	}

	/** A file source's stored root is read as the named key, alongside its file. */
	@Test(timeout = 30000)
	public void fileSourceRootIsReadByName() throws JsonProcessingException {
		FileNoteSource source = read(FileNoteSource.class,
				"\"source\":\"/a/kick.wav\",\"root\":\"G3\"");

		Assert.assertEquals("/a/kick.wav", source.getSource());
		Assert.assertEquals(WesternChromatic.G3, source.getRoot());
	}

	/** A stored null root stays null rather than defaulting to some key. */
	@Test(timeout = 30000)
	public void nullRootIsReadAsNull() throws JsonProcessingException {
		TreeNoteSource source = read(TreeNoteSource.class, "\"root\":null");

		Assert.assertNull(source.getRoot());
	}

	/** A name that is not a key is refused instead of being read as some other key. */
	@Test(timeout = 30000)
	public void unknownKeyNameIsRefused() {
		try {
			FileNoteSource source = read(FileNoteSource.class, "\"root\":\"H9\"");
			Assert.fail("Expected an unknown key name to be refused, but read " + source.getRoot());
		} catch (JsonProcessingException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("H9"));
		}
	}

	/**
	 * A root stored with type information, as a class name and key name pair, is read
	 * as the named key, and the properties after it are still read.
	 */
	@Test(timeout = 30000)
	public void typedRootIsReadByName() throws JsonProcessingException {
		FileNoteSource source = read(FileNoteSource.class,
				"\"root\":[\"" + WesternChromatic.class.getName() + "\",\"D2\"],\"source\":\"/a/snare.wav\"");

		Assert.assertEquals(WesternChromatic.D2, source.getRoot());
		Assert.assertEquals("/a/snare.wav", source.getSource());
	}

	/** A typed root whose name is not a key is refused like a plain one. */
	@Test(timeout = 30000)
	public void typedUnknownKeyNameIsRefused() {
		try {
			TreeNoteSource source = read(TreeNoteSource.class,
					"\"root\":[\"" + WesternChromatic.class.getName() + "\",\"H9\"]");
			Assert.fail("Expected an unknown typed key name to be refused, but read " + source.getRoot());
		} catch (JsonProcessingException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("H9"));
		}
	}

	/** A root that is not stored as a name is refused rather than failing inside the lookup. */
	@Test(timeout = 30000)
	public void nonNameRootIsRefused() {
		try {
			TreeNoteSource source = read(TreeNoteSource.class, "\"root\":{\"key\":\"C1\"}");
			Assert.fail("Expected a non-name root to be refused, but read " + source.getRoot());
		} catch (JsonProcessingException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("stored as its name"));
		}
	}

	/** A root key as written by a plain mapper reads back as the same key. */
	@Test(timeout = 30000)
	public void writtenRootReadsBackAsTheSameKey() throws JsonProcessingException {
		String root = mapper.writeValueAsString(WesternChromatic.A4);

		TreeNoteSource restored = read(TreeNoteSource.class, "\"root\":" + root);

		Assert.assertEquals(WesternChromatic.A4, restored.getRoot());
	}
}
