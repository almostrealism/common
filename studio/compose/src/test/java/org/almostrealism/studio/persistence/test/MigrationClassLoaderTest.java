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

import org.almostrealism.studio.persistence.MigrationClassLoader;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

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
	public void loadClassExactMapping() throws ClassNotFoundException {
		Class<?> clazz = MigrationClassLoader.getInstance()
				.loadClass("org.almostrealism.audio.notes.AudioChoiceNode");
		Assert.assertEquals("org.almostrealism.studio.notes.AudioChoiceNode", clazz.getName());
	}

	@Test
	public void loadClassPrefixMapping() throws ClassNotFoundException {
		Class<?> clazz = MigrationClassLoader.getInstance()
				.loadClass("org.almostrealism.audio.notes.FileNoteSource");
		Assert.assertEquals("org.almostrealism.music.notes.FileNoteSource", clazz.getName());
	}

	@Test
	public void loadClassUnmapped() throws ClassNotFoundException {
		Class<?> clazz = MigrationClassLoader.getInstance()
				.loadClass("java.util.ArrayList");
		Assert.assertEquals("java.util.ArrayList", clazz.getName());
	}
}
