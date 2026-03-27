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

package org.almostrealism.studio.persistence.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.almostrealism.music.pattern.NoteAudioChoiceList;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.persistence.MigrationClassLoader;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.beans.ExceptionListener;
import java.beans.XMLDecoder;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link MigrationClassLoader}, verifying that old fully-qualified
 * class names from before the package reorganization are correctly translated
 * to their current locations.
 */
public class MigrationClassLoaderTest extends TestSuiteBase {

	@Test
	public void exactMappingAudioChoiceNode() {
		Assert.assertEquals(
				"org.almostrealism.studio.notes.AudioChoiceNode",
				MigrationClassLoader.translate("org.almostrealism.audio.notes.AudioChoiceNode"));
	}

	@Test
	public void exactMappingChannelAudioNode() {
		Assert.assertEquals(
				"org.almostrealism.studio.notes.ChannelAudioNode",
				MigrationClassLoader.translate("org.almostrealism.audio.notes.ChannelAudioNode"));
	}

	@Test
	public void exactMappingSceneAudioNode() {
		Assert.assertEquals(
				"org.almostrealism.studio.notes.SceneAudioNode",
				MigrationClassLoader.translate("org.almostrealism.audio.notes.SceneAudioNode"));
	}

	@Test
	public void prefixMappingFileNoteSource() {
		Assert.assertEquals(
				"org.almostrealism.music.notes.FileNoteSource",
				MigrationClassLoader.translate("org.almostrealism.audio.notes.FileNoteSource"));
	}

	@Test
	public void prefixMappingTreeNoteSource() {
		Assert.assertEquals(
				"org.almostrealism.music.notes.TreeNoteSource",
				MigrationClassLoader.translate("org.almostrealism.audio.notes.TreeNoteSource"));
	}

	@Test
	public void prefixMappingFilter() {
		Assert.assertEquals(
				"org.almostrealism.music.filter.ParameterizedFilterEnvelope",
				MigrationClassLoader.translate("org.almostrealism.audio.filter.ParameterizedFilterEnvelope"));
	}

	@Test
	public void prefixMappingPattern() {
		Assert.assertEquals(
				"org.almostrealism.music.pattern.PatternElementFactory",
				MigrationClassLoader.translate("org.almostrealism.audio.pattern.PatternElementFactory"));
	}

	@Test
	public void prefixMappingHealth() {
		Assert.assertEquals(
				"org.almostrealism.studio.health.AudioHealthScore",
				MigrationClassLoader.translate("org.almostrealism.audio.health.AudioHealthScore"));
	}

	@Test
	public void prefixMappingMlAudio() {
		Assert.assertEquals(
				"org.almostrealism.studio.ml.AudioGenerator",
				MigrationClassLoader.translate("org.almostrealism.ml.audio.AudioGenerator"));
	}

	@Test
	public void prefixMappingComAlmostrealism() {
		Assert.assertEquals(
				"org.almostrealism.spatial.GenomicNetwork",
				MigrationClassLoader.translate("com.almostrealism.spatial.GenomicNetwork"));
	}

	@Test
	public void unmappedNamePassesThrough() {
		Assert.assertEquals(
				"java.util.ArrayList",
				MigrationClassLoader.translate("java.util.ArrayList"));
	}

	@Test
	public void migrateContentExactAndPrefix() {
		String input = "class=\"org.almostrealism.audio.notes.AudioChoiceNode\" " +
				"class=\"org.almostrealism.audio.notes.FileNoteSource\"";
		String result = MigrationClassLoader.migrateContent(input);
		Assert.assertTrue(result.contains("org.almostrealism.studio.notes.AudioChoiceNode"));
		Assert.assertTrue(result.contains("org.almostrealism.music.notes.FileNoteSource"));
	}

	@Test
	public void migrateContentXmlFragment() {
		String xml = "<object class=\"com.almostrealism.spatial.GenomicNetwork\">" +
				"<object class=\"org.almostrealism.audio.health.AudioHealthScore\">";
		String result = MigrationClassLoader.migrateContent(xml);
		Assert.assertTrue(result.contains("org.almostrealism.spatial.GenomicNetwork"));
		Assert.assertTrue(result.contains("org.almostrealism.studio.health.AudioHealthScore"));
	}

	@Test
	public void migrateContentJsonFragment() {
		String json = "{\"@type\":\"org.almostrealism.audio.notes.TreeNoteSource\"}";
		String result = MigrationClassLoader.migrateContent(json);
		Assert.assertTrue(result.contains("org.almostrealism.music.notes.TreeNoteSource"));
	}

	/**
	 * Verifies that the class name migration in networks.xml content is
	 * applied correctly. Full deserialization requires the ringsdesktop
	 * classpath (GenomicNetwork lives in studio/spatial), so this test
	 * only checks that the content transformation is correct.
	 */
	@Test
	public void migrateNetworksXmlContent() throws Exception {
		File file = new File("../../../examples/networks.xml");
		if (!file.exists()) return;

		try (FileInputStream in = new FileInputStream(file)) {
			String original = new String(in.readAllBytes());
			String migrated = MigrationClassLoader.migrateContent(original);

			Assert.assertFalse("Old com.almostrealism.spatial prefix should be replaced",
					migrated.contains("com.almostrealism.spatial."));
			Assert.assertTrue("New org.almostrealism.spatial prefix should be present",
					migrated.contains("org.almostrealism.spatial.GenomicNetwork"));
			Assert.assertFalse("Old audio.health prefix should be replaced",
					migrated.contains("org.almostrealism.audio.health."));
			Assert.assertTrue("New studio.health prefix should be present",
					migrated.contains("org.almostrealism.studio.health.AudioHealthScore"));
			Assert.assertFalse("Old audio.notes prefix should be replaced",
					migrated.contains("org.almostrealism.audio.notes."));
			Assert.assertTrue("AudioChoiceNode should go to studio.notes",
					migrated.contains("org.almostrealism.studio.notes.AudioChoiceNode"));
			Assert.assertTrue("AudioProviderNode should go to music.notes",
					migrated.contains("org.almostrealism.music.notes.AudioProviderNode"));
		}
	}

	@Test
	public void loadPatternFactoryJson() throws Exception {
		File file = new File("../../../examples/pattern-factory.json");
		if (!file.exists()) return;

		ObjectMapper mapper = AudioScene.defaultMapper();
		try (FileInputStream in = new FileInputStream(file);
			 InputStream migrated = MigrationClassLoader.migrateStream(in)) {
			NoteAudioChoiceList choices = mapper.readValue(migrated, NoteAudioChoiceList.class);
			Assert.assertNotNull("pattern-factory.json should produce a non-null object", choices);
			Assert.assertFalse("pattern-factory.json should have entries", choices.isEmpty());
		}
	}
}
