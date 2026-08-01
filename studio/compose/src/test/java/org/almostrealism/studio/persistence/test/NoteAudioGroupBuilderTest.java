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

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.WavFile;
import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.data.WaveDataProvider;
import org.almostrealism.audio.notes.NoteAudioGroup;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.studio.persistence.AudioLayerGroupLibrary;
import org.almostrealism.studio.persistence.AudioLayerPitch;
import org.almostrealism.studio.persistence.NoteAudioGroupBuilder;
import org.almostrealism.studio.persistence.ProtobufLayerGroupStore;
import org.almostrealism.studio.persistence.ProtobufWaveDetailsStore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Tests for {@link NoteAudioGroupBuilder}: building the render-time
 * {@link NoteAudioGroup} from a <em>saved</em> {@link Audio.AudioLayerGroup},
 * resolving member audio through the library and member pitch through
 * {@link AudioLayerPitch}, excluding pitchless members, and the
 * backward-compatibility anchor that a one-member group routed through the
 * builder renders byte-identically to the bare sample.
 *
 * @see NoteAudioGroupBuilder
 * @see AudioLayerGroupLibrary
 */
public class NoteAudioGroupBuilderTest extends TestSuiteBase {

	/** Sample rate for generated audio in Hz. */
	private static final int SAMPLE_RATE = 44100;

	/** Duration of each generated sample in seconds. */
	private static final double SAMPLE_DURATION = 0.25;

	/** Shared tuning so the group and the bare reference compute identical ratios. */
	private final KeyboardTuning tuning = new DefaultKeyboardTuning();

	/** Root temporary directory for the test, removed during teardown. */
	private Path tempDir;
	/** The user-selected library folder into which member WAVs are copied. */
	private Path libraryRoot;
	/** Directory backing the details store. */
	private Path storeDir;
	/** Directory backing the group store. */
	private Path groupDir;
	/** Source directory holding the staged WAV files. */
	private Path stagingDir;

	/**
	 * Creates the temporary directory layout used by each test.
	 *
	 * @throws IOException if directory creation fails
	 */
	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("note-audio-group-builder-test");
		libraryRoot = tempDir.resolve("library");
		storeDir = tempDir.resolve("library-store");
		groupDir = tempDir.resolve("library-store").resolve("groups");
		stagingDir = tempDir.resolve("staging");
		Files.createDirectories(libraryRoot);
		Files.createDirectories(stagingDir);
	}

	/**
	 * Recursively deletes the test's temporary directory.
	 *
	 * @throws IOException if directory traversal fails
	 */
	@After
	public void tearDown() throws IOException {
		if (tempDir != null && Files.exists(tempDir)) {
			try (Stream<Path> walk = Files.walk(tempDir)) {
				walk.sorted(Comparator.reverseOrder())
						.map(Path::toFile)
						.forEach(File::delete);
			}
		}
	}

	/**
	 * Builds a group from a saved {@link Audio.AudioLayerGroup}: three pitched
	 * audio layers become members with their captured roots, a pitchless audio
	 * layer is excluded, nearest-member selection works, and a one-member render
	 * is byte-identical to the bare sample.
	 */
	@Test(timeout = 180000)
	public void buildsGroupFromSavedLayerGroup() throws Exception {
		File w2 = stagingWav("c2", 110.0);
		File w4 = stagingWav("c4", 220.0);
		File w6 = stagingWav("c6", 440.0);
		File wTexture = stagingWav("texture", 660.0);
		Map<String, File> sources = new HashMap<>();
		sources.put("c2", w2);
		sources.put("c4", w4);
		sources.put("c6", w6);
		sources.put("texture", wTexture);

		Audio.AudioLayerGroup group = Audio.AudioLayerGroup.newBuilder()
				.setKey("20260628-120000_Alchemy_aabbccdd")
				.addLayers(pitchedLayer("c2", identifier(w2), WesternChromatic.C2))
				.addLayers(pitchedLayer("c4", identifier(w4), WesternChromatic.C4))
				.addLayers(pitchedLayer("c6", identifier(w6), WesternChromatic.C6))
				// Pitchless: no captured_pitch and a layer_id that holds no note name.
				.addLayers(audioLayer("texture", identifier(wTexture)))
				.build();

		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		AudioLibrary library = new AudioLibrary(
				new FileWaveDataProviderNode(libraryRoot.toFile()), SAMPLE_RATE, store);
		library.getWaveDetailsFactory()
				.setFeatureProvider(new DiskStoreAudioLibraryTest.SimpleFeatureProvider());
		ProtobufLayerGroupStore groupStore = new ProtobufLayerGroupStore(groupDir.toFile());
		AudioLayerGroupLibrary coordinator =
				new AudioLayerGroupLibrary(library, groupStore, libraryRoot.toFile());

		try {
			Optional<String> key = coordinator.includeGroup(group, byLayerId(sources));
			Assert.assertTrue("Group save should succeed", key.isPresent());

			Audio.AudioLayerGroup stored = groupStore.get(group.getKey());
			Assert.assertNotNull("Group should be stored", stored);

			NoteAudioGroup built = NoteAudioGroupBuilder.build(stored, library);
			built.setTuning(tuning);

			// The pitchless layer is excluded; the three pitched layers become members.
			Assert.assertEquals("Pitchless layer must be excluded", 3, built.getMembers().size());
			Assert.assertTrue("All members carry a captured root",
					built.getMembers().stream().allMatch(m -> m.getRoot() != null));

			// Nearest-member selection over the captured roots.
			Assert.assertEquals("G4 is nearest the C4 member",
					WesternChromatic.C4.position(),
					built.nearest(WesternChromatic.G4).getRoot().position());

			// Backward-compatibility anchor through the builder: the C4 member,
			// shifted to G4, equals a bare provider over the same library file.
			String idC4 = audioRefById(stored, "c4");
			WaveDataProvider provider = library.find(idC4);
			Assert.assertNotNull("C4 member must resolve in the library", provider);
			NoteAudioProvider bare = new NoteAudioProvider(provider, WesternChromatic.C4);
			bare.setTuning(tuning);

			for (KeyPosition<?> target : List.of(WesternChromatic.C4, WesternChromatic.G4)) {
				PackedCollection expected = bare.getAudio(target, 0).evaluate();
				PackedCollection actual = built.getAudio(target, 0).evaluate();
				assertBytesIdentical(expected, actual);
			}
		} finally {
			library.stop();
			store.close();
			groupStore.close();
		}
	}

	/** Asserts two collections are element-for-element identical. */
	private void assertBytesIdentical(PackedCollection expected, PackedCollection actual) {
		Assert.assertEquals("Frame count must match", expected.getMemLength(), actual.getMemLength());
		for (int i = 0; i < expected.getMemLength(); i++) {
			Assert.assertEquals("Sample " + i + " must match exactly",
					expected.toDouble(i), actual.toDouble(i), 0.0);
		}
	}

	/** Returns the {@code audio_ref} of the stored layer with the given id. */
	private static String audioRefById(Audio.AudioLayerGroup group, String layerId) {
		for (Audio.AudioLayer layer : group.getLayersList()) {
			if (layerId.equals(layer.getLayerId())) return layer.getAudioRef();
		}
		throw new AssertionError("No layer " + layerId);
	}

	/** Builds a per-layer WAV resolver backed by a {@code layer_id -> file} map. */
	private static Function<Audio.AudioLayer, File> byLayerId(Map<String, File> sources) {
		return layer -> sources.get(layer.getLayerId());
	}

	/** Builds an inline-audio layer with a captured pitch. */
	private static Audio.AudioLayer pitchedLayer(String layerId, String identifier, KeyPosition<?> pitch) {
		return audioLayer(layerId, identifier).toBuilder()
				.setCapturedPitch(AudioLayerPitch.toKeyPositionData(pitch))
				.build();
	}

	/** Builds an inline-audio layer carrying only identifier + shape metadata. */
	private static Audio.AudioLayer audioLayer(String layerId, String identifier) {
		Audio.WaveDetailData audio = Audio.WaveDetailData.newBuilder()
				.setIdentifier(identifier)
				.setSampleRate(SAMPLE_RATE)
				.setChannelCount(1)
				.setFrameCount((int) (SAMPLE_RATE * SAMPLE_DURATION))
				.build();
		return Audio.AudioLayer.newBuilder()
				.setLayerId(layerId)
				.setAudio(audio)
				.setDeviceType(Audio.DeviceType.SOFTWARE)
				.setCreatedAtMillis(1700000000000L)
				.build();
	}

	/** Writes a mono sine WAV into the staging directory and returns it. */
	private File stagingWav(String name, double frequency) throws IOException {
		int frames = (int) (SAMPLE_RATE * SAMPLE_DURATION);
		File wavFile = stagingDir.resolve(name + ".wav").toFile();
		try (WavFile wav = WavFile.newWavFile(wavFile, 1, frames, 16, SAMPLE_RATE)) {
			double[][] buffer = new double[1][frames];
			for (int i = 0; i < frames; i++) {
				buffer[0][i] = 0.8 * Math.sin(2.0 * Math.PI * frequency * i / SAMPLE_RATE);
			}
			wav.writeFrames(buffer, frames);
		}
		return wavFile;
	}

	/** Computes the library content identifier (MD5 hex) for a WAV file. */
	private static String identifier(File f) {
		return new FileWaveDataProvider(f.getAbsolutePath()).getIdentifier();
	}
}
