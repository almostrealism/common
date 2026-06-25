/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.studio.persistence.test;

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.studio.persistence.AudioLibraryPersistence;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;

/**
 * Verifies that the persistent {@code rank} field added to
 * {@link WaveDetails} round-trips through the protobuf encoder/decoder and
 * through {@link AudioLibrary}'s include/get persistence path.
 */
public class WaveDetailsRankTest extends TestSuiteBase {

	/** Temporary directory backing the audio library under test. */
	private File tempDir;

	/** The audio library under test. */
	private AudioLibrary library;

	/**
	 * Creates a temporary directory and audio library for each test.
	 *
	 * @throws IOException if the temporary directory cannot be created
	 */
	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("wave-details-rank-test").toFile();
		library = new AudioLibrary(tempDir, 44100);
	}

	/** Stops the audio library after each test. */
	@After
	public void tearDown() {
		if (library != null) {
			library.stop();
		}
	}

	/**
	 * Creates a minimal complete {@link WaveDetails} so it survives the
	 * encode/decode and library completeness checks.
	 */
	private WaveDetails completeDetails(String identifier) {
		WaveDetails details = new WaveDetails(identifier, 44100);
		details.setFreqData(new PackedCollection(1));
		details.setFeatureData(new PackedCollection(1));
		details.setSimilarities(new HashMap<>());
		return details;
	}

	/** A freshly-created WaveDetails has no rank assigned. */
	@Test
	public void rankAbsentByDefault() {
		WaveDetails details = completeDetails("id-default");
		Assert.assertFalse(details.hasRank());
		Assert.assertNull(details.getRank());
	}

	/** A set rank survives a protobuf encode/decode round-trip. */
	@Test
	public void rankRoundTripsThroughProto() {
		WaveDetails details = completeDetails("id-proto");
		details.setRank(3.5);

		Audio.WaveDetailData encoded = AudioLibraryPersistence.encode(details, null);
		Assert.assertTrue("encoded data should carry the rank", encoded.hasRank());
		Assert.assertEquals(3.5, encoded.getRank(), 0.0);

		WaveDetails decoded = AudioLibraryPersistence.decode(encoded);
		Assert.assertTrue(decoded.hasRank());
		Assert.assertEquals(3.5, decoded.getRank(), 0.0);
	}

	/** An unranked WaveDetails encodes without a rank and decodes as unranked. */
	@Test
	public void absentRankRoundTripsThroughProto() {
		WaveDetails details = completeDetails("id-proto-absent");

		Audio.WaveDetailData encoded = AudioLibraryPersistence.encode(details, null);
		Assert.assertFalse("absent rank should not be encoded", encoded.hasRank());

		WaveDetails decoded = AudioLibraryPersistence.decode(encoded);
		Assert.assertFalse(decoded.hasRank());
		Assert.assertNull(decoded.getRank());
	}

	/** A rank written through AudioLibrary.include is readable via get. */
	@Test(timeout = 10000)
	public void rankPersistsThroughAudioLibrary() {
		WaveDetails details = completeDetails("id-lib");
		details.setRank(7.25);
		library.include(details);

		WaveDetails retrieved = library.get("id-lib");
		Assert.assertNotNull(retrieved);
		Assert.assertTrue(retrieved.hasRank());
		Assert.assertEquals(7.25, retrieved.getRank(), 0.0);
	}

	/**
	 * Updating an item's rank and re-including it overwrites the stored rank,
	 * mirroring the re-rank persistence path used by the library tree.
	 */
	@Test(timeout = 10000)
	public void rankUpdatePersistsThroughAudioLibrary() {
		WaveDetails details = completeDetails("id-update");
		details.setRank(1.0);
		library.include(details);

		WaveDetails stored = library.get("id-update");
		stored.setRank(1.5);
		library.include(stored);

		WaveDetails retrieved = library.get("id-update");
		Assert.assertEquals(1.5, retrieved.getRank(), 0.0);
	}
}
