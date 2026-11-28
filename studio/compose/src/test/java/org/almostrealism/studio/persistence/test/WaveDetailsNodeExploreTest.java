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
import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveDetailsNode;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.persist.index.ProtobufDiskStore;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Exercises the similarity tree that backs the Explore columns of the
 * Similar view ({@link WaveDetailsNode}) over a store-backed
 * {@link AudioLibrary}, the way the desktop app builds it: every record is
 * decoded afresh from a {@link ProtobufWaveDetailsStore}, and every child is
 * resolved back to a file in the library tree.
 *
 * <p>Covers two reported problems: a column listing the same file more than
 * once, and a column taking many seconds to fill.</p>
 */
public class WaveDetailsNodeExploreTest extends TestSuiteBase {

	/** Sample rate recorded on every synthetic record. */
	private static final int SAMPLE_RATE = 44100;

	/** Feature frames per record, as produced for a conformed clip. */
	private static final int FEATURE_FRAMES = 216;

	/** Feature bins per frame. */
	private static final int FEATURE_BINS = 64;

	/** Feature frames per second, matching the conformed clip length of ten seconds. */
	private static final double FEATURE_RATE = FEATURE_FRAMES / 10.0;

	/** Temporary directory holding the sample files and the store. */
	private Path tempDir;

	/** The library under test. */
	private AudioLibrary library;

	/** The store backing {@link #library}. */
	private ProtobufWaveDetailsStore store;

	/**
	 * Creates the temporary directory.
	 *
	 * @throws IOException if it cannot be created
	 */
	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("wave-details-node-explore");
	}

	/**
	 * Stops the library, closes the store and deletes the temporary directory.
	 *
	 * @throws IOException if the directory cannot be walked
	 */
	@After
	public void tearDown() throws IOException {
		if (library != null) library.stop();
		if (store != null) store.close();

		if (tempDir != null && Files.exists(tempDir)) {
			try (Stream<Path> walk = Files.walk(tempDir)) {
				walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
	}

	/**
	 * Two files holding the same audio under the same name in different
	 * folders (a sample shipped in two packs, or a copy whose bytes differ only
	 * in metadata) have distinct content identifiers, so the library holds a
	 * record for each. A column must still list that sound once, and must not
	 * list a copy of the sound it was opened for.
	 */
	@Test(timeout = 120000)
	public void sameSoundInTwoFoldersIsListedOnce() throws IOException {
		Path samples = tempDir.resolve("samples");
		Random random = new Random(7);

		openLibrary(samples, ProtobufDiskStore.DEFAULT_MAX_MEMORY_BYTES);

		PackedCollection query = features(random, null);
		String queryPath = addSample(samples.resolve("query.wav"), random, query, 0.5);
		addSample(samples.resolve("PackC/query.wav"), random, query, 0.5);

		// The same sound in two packs: identical features, different bytes
		PackedCollection twin = features(random, null);
		addSample(samples.resolve("PackA/SD 808 Air 06.wav"), random, twin, 0.5);
		addSample(samples.resolve("PackB/SD 808 Air 06.wav"), random, twin, 0.5);

		// The same record cannot appear twice, but distinct sounds sharing a name must both remain
		addSample(samples.resolve("PackA/Kick.wav"), random, features(random, null), 0.5);
		addSample(samples.resolve("PackB/Kick.wav"), random, features(random, null), 0.5);

		for (int i = 0; i < 6; i++) {
			addSample(samples.resolve("other-" + i + ".wav"), random, features(random, null), 0.5);
		}

		WaveDetailsNode root = new WaveDetailsNode(library, queryPath);
		root.prepareChildren();

		List<String> children = root.getChildren().stream()
				.map(WaveDetailsNode::getResourcePath).toList();
		Assert.assertEquals("every path appears once: " + children,
				children.size(), new HashSet<>(children).size());

		long twins = children.stream().filter(p -> p.endsWith("SD 808 Air 06.wav")).count();
		Assert.assertEquals("the same sound in two packs is listed once: " + children, 1, twins);

		long kicks = children.stream().filter(p -> p.endsWith("Kick.wav")).count();
		Assert.assertEquals("distinct sounds sharing a name are both listed: " + children, 2, kicks);

		Assert.assertTrue("neither the query nor a copy of it is listed: " + children,
				children.stream().noneMatch(p -> p.endsWith("query.wav")));
	}

	/**
	 * Measures how long the first two Explore columns take to fill for a
	 * store-backed library in the state the desktop app leaves it: records hold
	 * features and are in the store's neighbor index, but carry no pairwise
	 * similarity maps (prototype discovery builds its graph from the neighbor
	 * index, and nothing the Explore columns compute is written back), and the
	 * records do not all fit in the store's memory budget. Each column must
	 * fill within a couple of seconds, and narrowing the scored items to the
	 * nearest ones must still find the closest sounds: the library holds groups
	 * of related sounds, and each column lists sounds from the query's group.
	 */
	@Test(timeout = 600000)
	public void columnsFillQuickly() throws IOException {
		int count = 3000;
		int groups = 20;
		Path samples = tempDir.resolve("samples");
		Random random = new Random(11);

		openLibrary(samples, 16L * 1024 * 1024);

		List<PackedCollection> centroids = new ArrayList<>();
		for (int g = 0; g < groups; g++) centroids.add(features(random, null));

		List<String> paths = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			paths.add(addSample(samples.resolve("group-" + (i % groups) + "/sample-" + i + ".wav"),
					random, features(random, centroids.get(i % groups)), 0.25 + random.nextDouble() * 4));
		}

		WaveDetailsNode[] columns = new WaveDetailsNode[2];
		long first = time(() -> {
			columns[0] = new WaveDetailsNode(library, paths.get(0));
			columns[0].prepareChildren();
		});
		columns[1] = columns[0].getChildren().iterator().next();
		long second = time(columns[1]::prepareChildren);
		log("Column 1 filled in " + first + " ms, column 2 in " + second + " ms, for " + count + " samples");

		for (WaveDetailsNode column : columns) {
			List<String> listed = column.getChildren().stream().map(WaveDetailsNode::getResourcePath).toList();
			Assert.assertEquals(20, listed.size());
			Assert.assertTrue("every match is from the query's group: " + listed,
					listed.stream().allMatch(p -> p.contains("/group-0/")));
		}

		Set<String> left = new HashSet<>();
		columns[0].getChildren().forEach(n -> left.add(n.getResourcePath()));
		left.add(paths.get(0));
		Assert.assertTrue("a deeper column repeats nothing shown to its left",
				columns[1].getChildren().stream().noneMatch(n -> left.contains(n.getResourcePath())));

		Assert.assertTrue("column 1 took " + first + " ms", first < 2000);
		Assert.assertTrue("column 2 took " + second + " ms", second < 2000);
	}

	/**
	 * Opens a store-backed library over the given sample directory.
	 *
	 * @param samples        the sample directory
	 * @param maxMemoryBytes how many bytes of records the store may hold in memory
	 * @throws IOException if the directory cannot be created
	 */
	private void openLibrary(Path samples, long maxMemoryBytes) throws IOException {
		Files.createDirectories(samples);
		store = new ProtobufWaveDetailsStore(tempDir.resolve("store").toFile(),
				maxMemoryBytes, ProtobufWaveDetailsStore.DEFAULT_TARGET_BATCH_SIZE);
		library = new AudioLibrary(new FileWaveDataProviderNode(samples.toFile()), SAMPLE_RATE, store);
	}

	/**
	 * Writes a file of random bytes (so it has its own content identifier)
	 * and records complete details for it carrying the given features.
	 *
	 * @param file     where to write the file
	 * @param random   source of the file's bytes
	 * @param features the features to record for it
	 * @param duration the duration of the sound, in seconds
	 * @return the file's path
	 * @throws IOException if the file cannot be written
	 */
	private String addSample(Path file, Random random, PackedCollection features,
							 double duration) throws IOException {
		Files.createDirectories(file.getParent());
		byte[] bytes = new byte[64];
		random.nextBytes(bytes);
		Files.write(file, bytes);

		String path = file.toString();
		WaveDetails details = new WaveDetails(new FileWaveDataProvider(path).getIdentifier(), SAMPLE_RATE);
		details.setFrameCount((int) (duration * SAMPLE_RATE));
		details.setFreqData(new PackedCollection(1));
		details.setFeatureSampleRate(FEATURE_RATE);
		details.setFeatureChannelCount(1);
		details.setFeatureBinCount(FEATURE_BINS);
		details.setFeatureFrameCount(FEATURE_FRAMES);
		details.setFeatureData(features);
		library.include(details);
		library.persist(details);
		return path;
	}

	/**
	 * Creates gaussian features of the shape a conformed clip produces.
	 *
	 * @param random   source of the values
	 * @param centroid features the result varies around, or {@code null} for
	 *                 features unrelated to any other
	 * @return a {@code [frames, bins]} feature collection
	 */
	private static PackedCollection features(Random random, PackedCollection centroid) {
		double[] values = new double[FEATURE_FRAMES * FEATURE_BINS];
		double[] center = centroid == null ? null : centroid.toArray(0, values.length);
		for (int i = 0; i < values.length; i++) {
			values[i] = center == null ? random.nextGaussian() : center[i] + 0.5 * random.nextGaussian();
		}

		PackedCollection features = new PackedCollection(FEATURE_FRAMES, FEATURE_BINS);
		features.set(0, values);
		return features;
	}

	/**
	 * Runs the given work and returns how long it took.
	 *
	 * @param work the work to time
	 * @return the elapsed milliseconds
	 */
	private static long time(Runnable work) {
		long start = System.currentTimeMillis();
		work.run();
		return System.currentTimeMillis() - start;
	}
}
