/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.studio.pattern.test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.AudioSceneLoader;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.music.notes.FileNoteSource;
import org.almostrealism.music.notes.NoteAudioChoice;
import org.almostrealism.music.notes.NoteAudioSource;
import org.almostrealism.music.notes.TreeNoteSource;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.music.pattern.ChordProgressionManager;
import org.almostrealism.music.pattern.NoteAudioChoiceList;
import org.almostrealism.music.pattern.PatternLayerManager;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.time.Frequency;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Tests for {@link PatternLayerManager} pattern factory functionality,
 * verifying pattern loading, genome assignment, and audio rendering.
 */
public class PatternFactoryTest extends TestSuiteBase implements CellFeatures {

	/** Path to the library directory. */
	public static String LIBRARY = "Library";

	static {
		String env = System.getenv("AR_RINGS_LIBRARY");
		if (env != null) LIBRARY = env;

		String arg = System.getProperty("AR_RINGS_LIBRARY");
		if (arg != null) LIBRARY = arg;
	}

	/**
	 * Test that the curated pattern choices can be read with a plain mapper and re-serialized.
	 * The curated pattern factory is required on every host meant to carry it, so this fails
	 * rather than skips on a GPU host or a host that declares {@code AR_RINGS_PATTERNS}.
	 */
	@Test(timeout = 30_000)
	public void fixChoices() throws IOException {
		File patternFactory = new File(AudioSceneTestBase.PATTERN_FACTORY);
		requireResource("curated pattern factory " + patternFactory.getAbsolutePath(),
				"AR_RINGS_PATTERNS", patternFactory.isFile());
		List<NoteAudioChoice> choices = readChoices(patternFactory);
		Assert.assertFalse("no pattern choices were read from " + patternFactory, choices.isEmpty());
		new ObjectMapper().writeValue(new File("pattern-factory-new.json"), choices);
	}

	/**
	 * A plain {@link ObjectMapper}, with no {@code KeyPosition} deserializer registered,
	 * must still be able to read a note source whose {@code root} is a {@code KeyPosition}.
	 * This reproduces the {@code fixChoices} failure on hosts that carry the pattern factory
	 * file without depending on that host-local resource: the {@code root} field is declared
	 * as the abstract {@link KeyPosition} interface, so {@code setRoot} must narrow it to the
	 * concrete {@link WesternChromatic} enum for a default mapper to construct it.
	 */
	@Test(timeout = 30_000)
	public void noteSourceRootDeserializes() throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

		String treeJson = "{\"@type\":\"" + TreeNoteSource.class.getName() + "\",\"root\":\"C1\"}";
		NoteAudioSource treeSource = mapper.readValue(treeJson, NoteAudioSource.class);
		Assert.assertTrue(treeSource instanceof TreeNoteSource);
		KeyPosition<?> treeRoot = ((TreeNoteSource) treeSource).getRoot();
		Assert.assertTrue(treeRoot instanceof WesternChromatic);
		Assert.assertEquals(WesternChromatic.C1, treeRoot);

		String fileJson = "{\"@type\":\"" + FileNoteSource.class.getName() + "\",\"source\":\"a.wav\",\"root\":\"C1\"}";
		NoteAudioSource fileSource = mapper.readValue(fileJson, NoteAudioSource.class);
		Assert.assertTrue(fileSource instanceof FileNoteSource);
		KeyPosition<?> fileRoot = ((FileNoteSource) fileSource).getRoot();
		Assert.assertTrue(fileRoot instanceof WesternChromatic);
		Assert.assertEquals(WesternChromatic.C1, fileRoot);
	}

	/**
	 * Test that choices can be consolidated into directories by name.
	 */
	// @Test(timeout = 30000)
	public void consolidateChoices() throws IOException {
		List<NoteAudioChoice> choices = readChoices();

		Map<String, List<File>> dirs = new HashMap<>();

		choices.forEach(c -> {
			dirs.put(c.getName().replaceAll(" ", "_"),
					c.getSources().stream().map(NoteAudioSource::getOrigin).map(File::new).collect(Collectors.toList()));
		});

		dirs.forEach((name, files) -> {
			File root = new File("pattern-factory/" + name);
			root.mkdirs();

			files.forEach(file -> {
				try {
					Files.copy(file.toPath(),
							new File(root, file.getName()).toPath(),
							StandardCopyOption.REPLACE_EXISTING);
					log("Copied " + file.getName());
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		});
	}

	/**
	 * Reads pattern choices from the pattern-factory file.
	 *
	 * @return the deserialized choice list
	 * @throws IOException if reading fails
	 */
	public NoteAudioChoiceList readChoices() throws IOException {
		return readChoices(true);
	}

	/**
	 * Reads pattern choices from either the old or new pattern-factory file.
	 *
	 * @param useOld if true, prefers the old file format
	 * @return the deserialized choice list
	 * @throws IOException if reading fails
	 */
	public NoteAudioChoiceList readChoices(boolean useOld) throws IOException {
		File f = new File("pattern-factory.json.old");
		return readChoices(useOld && f.exists() ? f : new File("pattern-factory.json"));
	}

	/**
	 * Reads pattern choices from the given file with a plain mapper.
	 *
	 * @param file the pattern-factory file to read
	 * @return the deserialized choice list
	 * @throws IOException if reading fails
	 */
	public NoteAudioChoiceList readChoices(File file) throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		return mapper.readValue(file, NoteAudioChoiceList.class);
	}

	/**
	 * Test that pattern layers can be rendered to audio and saved to file.
	 */
	@Test(timeout = 300_000)
	public void runLayers() throws IOException {
		File patternFactory = new File("pattern-factory.json");
		requireLocalResource("pattern factory " + patternFactory.getAbsolutePath(), null,
				patternFactory.exists());
		File sceneSettings = new File(SystemUtils.getLocalDestination("scene-settings.json"));
		requireLocalResource("scene settings " + sceneSettings.getAbsolutePath(), null,
				sceneSettings.exists());
		requireResource("sample library " + new File(LIBRARY).getAbsolutePath(), "AR_RINGS_LIBRARY",
				new File(LIBRARY).isDirectory());
		Frequency bpm = bpm(120);

		int measures = 32;
		int beats = 4;
		double measureDuration = bpm.l(beats);
		double measureFrames = measureDuration * OutputLine.sampleRate;

		AudioSceneLoader.Settings settings = new ObjectMapper().readValue(
				new File(SystemUtils.getLocalDestination("scene-settings.json")),
				AudioSceneLoader.Settings.class);

		FileWaveDataProviderNode library = new FileWaveDataProviderNode(new File(LIBRARY));

		List<NoteAudioChoice> choices = readChoices(false);
		choices.stream()
				.flatMap(c -> c.getSources().stream())
				.map(c -> c instanceof TreeNoteSource ? (TreeNoteSource) c : null)
				.filter(Objects::nonNull)
				.forEach(c -> c.setTree(library));

		DefaultKeyboardTuning tuning = new DefaultKeyboardTuning();

		choices.forEach(c -> {
			c.setTuning(tuning);
			c.setBias(1.0);
		});

		ChordProgressionManager chordProgression = new ChordProgressionManager(8);
		chordProgression.setSettings(settings.getChordProgression());
		chordProgression.refreshParameters();

		ProjectedGenome genome = new ProjectedGenome(8);

		PatternLayerManager manager = new PatternLayerManager(
				choices, genome.addChromosome(),
				3, 16.0, true);
		manager.setScaleTraversalDepth(3);

		double a = Math.random(); // 0.2;
		manager.setLayerCount(3);

		log("a = " + a);

		AudioSceneContext context = new AudioSceneContext();
		context.setMeasures(measures);
		context.setFrames((int) (measures * measureFrames));
		context.setFrameForPosition(pos -> (int) (pos * measureFrames));
		context.setTimeForDuration(pos -> pos * measureDuration);
		context.setScaleForPosition(chordProgression::forPosition);

		manager.updateDestination(context);
		manager.sum(() -> context, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT,
				() -> 0, context.getFrames());

		WaveData out = new WaveData(manager.getDestination().get(
				new ChannelInfo(ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT)), OutputLine.sampleRate);
		out.save(new File("results/pattern-layer-test.wav"));
	}
}
