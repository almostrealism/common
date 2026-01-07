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

package org.almostrealism.audio.pattern.test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.almostrealism.audio.AudioScene;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.arrange.AudioSceneContext;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.notes.NoteAudioChoice;
import org.almostrealism.audio.notes.NoteAudioSource;
import org.almostrealism.audio.notes.TreeNoteSource;
import org.almostrealism.audio.pattern.ChordProgressionManager;
import org.almostrealism.audio.pattern.NoteAudioChoiceList;
import org.almostrealism.audio.pattern.PatternLayerManager;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.time.Frequency;
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

public class PatternFactoryTest implements CellFeatures {

	public static String LIBRARY = "Library";

	static {
		String env = System.getenv("AR_RINGS_LIBRARY");
		if (env != null) LIBRARY = env;

		String arg = System.getProperty("AR_RINGS_LIBRARY");
		if (arg != null) LIBRARY = arg;
	}

	@Test
	public void fixChoices() throws IOException {
		List<NoteAudioChoice> choices = readChoices();
		new ObjectMapper().writeValue(new File("pattern-factory-new.json"), choices);
	}

	// @Test
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
					System.out.println("Copied " + file.getName());
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		});
	}

	public NoteAudioChoiceList readChoices() throws IOException {
		return readChoices(true);
	}

	public NoteAudioChoiceList readChoices(boolean useOld) throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

		File f = new File("pattern-factory.json.old");
		if (useOld && f.exists()) {
			return mapper.readValue(f, NoteAudioChoiceList.class);
		} else {
			return mapper.readValue(new File("pattern-factory.json"), NoteAudioChoiceList.class);
		}
	}

	@Test
	public void runLayers() throws IOException {
		Frequency bpm = bpm(120);

		int measures = 32;
		int beats = 4;
		double measureDuration = bpm.l(beats);
		double measureFrames = measureDuration * OutputLine.sampleRate;

		AudioScene.Settings settings = new ObjectMapper().readValue(
				new File(SystemUtils.getLocalDestination("scene-settings.json")),
				AudioScene.Settings.class);

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

		System.out.println("a = " + a);

		AudioSceneContext context = new AudioSceneContext();
		context.setMeasures(measures);
		context.setFrames((int) (measures * measureFrames));
		context.setFrameForPosition(pos -> (int) (pos * measureFrames));
		context.setTimeForDuration(pos -> pos * measureDuration);
		context.setScaleForPosition(chordProgression::forPosition);

		manager.updateDestination(context);
		manager.sum(() -> context, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT);

		WaveData out = new WaveData(manager.getDestination().get(
				new ChannelInfo(ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT)), OutputLine.sampleRate);
		out.save(new File("results/pattern-layer-test.wav"));
	}
}
