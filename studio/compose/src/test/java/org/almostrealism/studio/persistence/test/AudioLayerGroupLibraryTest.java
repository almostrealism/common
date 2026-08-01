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
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveDetailsStore;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.studio.persistence.AudioLayerGroupLibrary;
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
 * Round-trip tests for {@link AudioLayerGroupLibrary}: saving a staged
 * {@link Audio.AudioLayerGroup} into the library, the semantic-index invariant,
 * the WAV-copy collision policy, and all-or-nothing rollback.
 *
 * <p>The save algorithm must (a) route every audio member into the main details
 * store so it is searchable in the semantic index, (b) rewrite each saved
 * layer's inline {@code audio} payload to a slim {@code audio_ref}, and (c)
 * write the slim group; reloading the stores (simulating a restart) must return
 * the same group with the same layers, members still present in the index.</p>
 *
 * @see AudioLayerGroupLibrary
 * @see ProtobufLayerGroupStore
 */
public class AudioLayerGroupLibraryTest extends TestSuiteBase {

	/** Sample rate for generated audio in Hz (matches {@link DiskStoreAudioLibraryTest.SimpleFeatureProvider}). */
	private static final int SAMPLE_RATE = 44100;

	/** Duration of each generated sample in seconds. */
	private static final double SAMPLE_DURATION = 0.25;

	/** Root temporary directory for the test, removed during teardown. */
	private Path tempDir;
	/** The user-selected library folder into which member WAVs are copied. */
	private Path libraryRoot;
	/** Directory backing the {@link ProtobufWaveDetailsStore}. */
	private Path storeDir;
	/** Directory backing the {@link ProtobufLayerGroupStore}. */
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
		tempDir = Files.createTempDirectory("layer-group-library-test");
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
	 * Full round-trip: a staged group with three audio layers and one MIDI
	 * layer saves so that (a) every audio member is retrievable and searchable
	 * by identifier in the semantic index, (b) each saved layer is rewritten to
	 * {@code audio_ref}, the MIDI layer untouched, and (c) the slim group is
	 * stored. Reloading the stores returns the same group with the same layers
	 * and members still indexed.
	 */
	@Test(timeout = 180000)
	public void roundTripSaveGroup() throws Exception {
		File w0 = stagingWav("layerA", 220.0);
		File w1 = stagingWav("layerB", 440.0);
		File w2 = stagingWav("layerC", 880.0);
		Map<String, File> sources = new HashMap<>();
		sources.put("layerA", w0);
		sources.put("layerB", w1);
		sources.put("layerC", w2);

		String idA = identifier(w0);
		String idB = identifier(w1);
		String idC = identifier(w2);

		Audio.AudioLayerGroup group = Audio.AudioLayerGroup.newBuilder()
				.setKey("20260613-100000_Alchemy_abcd1234")
				.addLayers(audioLayer("layerA", idA))
				.addLayers(audioLayer("layerB", idB))
				.addLayers(midiLayer("layerMidi"))
				.addLayers(audioLayer("layerC", idC))
				.build();

		Ctx ctx = open();
		try {
			Optional<String> key = ctx.coordinator.includeGroup(group, byLayerId(sources));
			Assert.assertTrue("Group save should succeed", key.isPresent());
			Assert.assertEquals(group.getKey(), key.get());

			// (a) Each audio member is in the semantic index, searchable by id.
			for (String id : List.of(idA, idB, idC)) {
				assertIndexed(ctx, id);
			}

			// (b)/(c) The stored slim group rewrites audio layers to audio_ref
			// and leaves the MIDI layer untouched.
			Audio.AudioLayerGroup stored = ctx.groupStore.get(group.getKey());
			Assert.assertNotNull("Group should be in the group store", stored);
			assertSlimLayers(stored, Map.of("layerA", idA, "layerB", idB, "layerC", idC));
			Audio.AudioLayer midi = layerById(stored, "layerMidi");
			Assert.assertTrue("MIDI layer must be untouched", midi.hasMidi());
		} finally {
			ctx.close();
		}

		// Simulate restart: reopen the stores and verify persistence.
		Ctx reopened = open();
		try {
			Audio.AudioLayerGroup stored = reopened.groupStore.get(group.getKey());
			Assert.assertNotNull("Group should survive reload", stored);
			assertSlimLayers(stored, Map.of("layerA", idA, "layerB", idB, "layerC", idC));
			Assert.assertEquals(4, stored.getLayersCount());

			for (String id : List.of(idA, idB, idC)) {
				Assert.assertTrue("Member " + id + " should survive reload",
						reopened.store.containsKey(id));
				Assert.assertNotNull("Member " + id + " should resolve to a library file",
						reopened.library.find(id));
			}
		} finally {
			reopened.close();
		}
	}

	/**
	 * Collision policy — same MD5: saving a group whose member WAV already
	 * occupies {@code <md5>.wav} is a no-op copy and still succeeds, without
	 * duplicating the index entry.
	 */
	@Test(timeout = 180000)
	public void collisionSameMd5IsNoOp() throws Exception {
		File w0 = stagingWav("only", 330.0);
		String id = identifier(w0);
		Audio.AudioLayerGroup group = Audio.AudioLayerGroup.newBuilder()
				.setKey("same-md5-group")
				.addLayers(audioLayer("only", id))
				.build();

		Ctx ctx = open();
		try {
			Assert.assertTrue(ctx.coordinator.includeGroup(group, byLayerId(Map.of("only", w0))).isPresent());
			Assert.assertTrue("Target WAV should exist", new File(libraryRoot.toFile(), id + ".wav").isFile());

			// Saving the same group again: target already present with same MD5.
			Assert.assertTrue("Re-save with identical MD5 should succeed",
					ctx.coordinator.includeGroup(group, byLayerId(Map.of("only", w0))).isPresent());
			Assert.assertTrue(ctx.store.containsKey(id));
		} finally {
			ctx.close();
		}
	}

	/**
	 * Collision policy — different MD5 at the same target name: the save fails
	 * rather than overwriting a user-curated file, the existing file is left
	 * untouched, and nothing is added to the index or the group store.
	 */
	@Test(timeout = 180000)
	public void collisionDifferentMd5IsError() throws Exception {
		File source = stagingWav("src", 200.0);
		String id = identifier(source);

		// Pre-place a DIFFERENT file at <md5>.wav (its real content hashes to
		// something other than id).
		File occupant = new File(libraryRoot.toFile(), id + ".wav");
		File other = stagingWav("other", 7000.0);
		Files.copy(other.toPath(), occupant.toPath());
		String occupantContentHash = identifier(occupant);
		Assert.assertNotEquals("Occupant must hash differently from the target name",
				id, occupantContentHash);
		byte[] before = Files.readAllBytes(occupant.toPath());

		Audio.AudioLayerGroup group = Audio.AudioLayerGroup.newBuilder()
				.setKey("collision-group")
				.addLayers(audioLayer("src", id))
				.build();

		Ctx ctx = open();
		try {
			Optional<String> key = ctx.coordinator.includeGroup(group, byLayerId(Map.of("src", source)));
			Assert.assertTrue("Save must fail on a different-MD5 collision", key.isEmpty());

			Assert.assertArrayEquals("Existing file must be left untouched",
					before, Files.readAllBytes(occupant.toPath()));
			Assert.assertFalse("No index entry should be added", ctx.store.containsKey(id));
			Assert.assertNull("No group should be stored", ctx.groupStore.get(group.getKey()));
		} finally {
			ctx.close();
		}
	}

	/**
	 * All-or-nothing rollback: when a later member cannot be written, every WAV
	 * and index entry added earlier in the call is undone and no group is stored.
	 */
	@Test(timeout = 180000)
	public void allOrNothingRollback() throws Exception {
		File good = stagingWav("good", 261.0);
		String goodId = identifier(good);

		Map<String, File> sources = new HashMap<>();
		sources.put("good", good);
		// "bad" resolves to a non-existent WAV, forcing a mid-group failure.
		sources.put("bad", new File(stagingDir.toFile(), "missing.wav"));

		Audio.AudioLayerGroup group = Audio.AudioLayerGroup.newBuilder()
				.setKey("rollback-group")
				.addLayers(audioLayer("good", goodId))
				.addLayers(audioLayer("bad", "deadbeef"))
				.build();

		Ctx ctx = open();
		try {
			Optional<String> key = ctx.coordinator.includeGroup(group, byLayerId(sources));
			Assert.assertTrue("Save must fail when a member WAV is missing", key.isEmpty());

			Assert.assertFalse("First member's index entry must be rolled back",
					ctx.store.containsKey(goodId));
			Assert.assertFalse("First member's WAV must be rolled back",
					new File(libraryRoot.toFile(), goodId + ".wav").exists());
			Assert.assertNull("No group should be stored", ctx.groupStore.get(group.getKey()));
		} finally {
			ctx.close();
		}
	}

	// ── Assertions ────────────────────────────────────────────────────────

	/** Asserts a member is retrievable and searchable by identifier in the semantic index. */
	private void assertIndexed(Ctx ctx, String id) {
		Assert.assertTrue("Member " + id + " should be in the details store", ctx.store.containsKey(id));
		WaveDetails details = ctx.library.get(id);
		Assert.assertNotNull("Member " + id + " should be retrievable", details);
		Assert.assertNotNull("Member " + id + " should resolve to a library file", ctx.library.find(id));

		PackedCollection embedding = AudioLibrary.computeEmbeddingVector(details);
		Assert.assertNotNull("Member " + id + " should have a similarity embedding", embedding);
		List<WaveDetailsStore.NeighborResult> neighbors = ctx.store.searchNeighbors(embedding, 10);
		boolean found = neighbors.stream().anyMatch(n -> id.equals(n.identifier()));
		Assert.assertTrue("Member " + id + " should be findable by HNSW similarity search", found);
	}

	/** Asserts each named layer was rewritten to {@code audio_ref} with the expected MD5. */
	private void assertSlimLayers(Audio.AudioLayerGroup group, Map<String, String> expectedRefs) {
		for (Map.Entry<String, String> e : expectedRefs.entrySet()) {
			Audio.AudioLayer layer = layerById(group, e.getKey());
			Assert.assertEquals("Layer " + e.getKey() + " should carry an audio_ref",
					Audio.AudioLayer.ContentCase.AUDIO_REF, layer.getContentCase());
			Assert.assertEquals(e.getValue(), layer.getAudioRef());
			Assert.assertFalse("Inline audio must be stripped", layer.hasAudio());
		}
	}

	/**
	 * Returns the layer with the given id, failing the test if absent.
	 *
	 * @param group   the group to search
	 * @param layerId the layer id to find
	 * @return the matching layer
	 */
	private static Audio.AudioLayer layerById(Audio.AudioLayerGroup group, String layerId) {
		for (Audio.AudioLayer layer : group.getLayersList()) {
			if (layerId.equals(layer.getLayerId())) return layer;
		}
		throw new AssertionError("No layer " + layerId);
	}

	// ── Fixtures ──────────────────────────────────────────────────────────

	/** Holds an open library + details store + group store + coordinator. */
	private final class Ctx {
		/** The details store. */
		final ProtobufWaveDetailsStore store;
		/** The audio library backed by {@link #store}. */
		final AudioLibrary library;
		/** The slim group store. */
		final ProtobufLayerGroupStore groupStore;
		/** The group-save coordinator under test. */
		final AudioLayerGroupLibrary coordinator;

		/** Opens fresh store, library, group store, and coordinator instances. */
		Ctx() {
			this.store = new ProtobufWaveDetailsStore(storeDir.toFile());
			this.library = new AudioLibrary(
					new FileWaveDataProviderNode(libraryRoot.toFile()), SAMPLE_RATE, store);
			this.library.getWaveDetailsFactory()
					.setFeatureProvider(new DiskStoreAudioLibraryTest.SimpleFeatureProvider());
			this.groupStore = new ProtobufLayerGroupStore(groupDir.toFile());
			this.coordinator = new AudioLayerGroupLibrary(library, groupStore, libraryRoot.toFile());
		}

		/** Stops the library and closes both stores. */
		void close() {
			library.stop();
			store.close();
			groupStore.close();
		}
	}

	/**
	 * Opens a fresh {@link Ctx} over the shared temp directories.
	 *
	 * @return the open context
	 */
	private Ctx open() {
		return new Ctx();
	}

	/**
	 * Builds a per-layer WAV resolver backed by a {@code layer_id -> file} map.
	 *
	 * @param sources mapping from layer id to source WAV file
	 * @return a resolver for use as the {@code wavSource} argument
	 */
	private static Function<Audio.AudioLayer, File> byLayerId(Map<String, File> sources) {
		return layer -> sources.get(layer.getLayerId());
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

	/** Builds a MIDI layer with a single note, to confirm it is left untouched. */
	private static Audio.AudioLayer midiLayer(String layerId) {
		return Audio.AudioLayer.newBuilder()
				.setLayerId(layerId)
				.setMidi(Audio.MidiPattern.newBuilder()
						.setTicksPerQuarter(480)
						.addEvents(Audio.MidiEvent.newBuilder()
								.setTick(0)
								.setNote(Audio.NoteEvent.newBuilder()
										.setPitch(60).setVelocity(100).setDurationTicks(480))))
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
