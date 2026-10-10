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

package org.almostrealism.audio.data.test;

import org.almostrealism.audio.WavFile;
import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * Behavioral tests for {@link FileWaveDataProvider}'s lazily loaded header
 * metadata accessors and its corrupt-file tracking.
 *
 * <p>These pin the behavior the four accessors — {@link FileWaveDataProvider#getSampleRate()},
 * {@link FileWaveDataProvider#getCountLong()}, {@link FileWaveDataProvider#getChannelCount()}
 * and {@link FileWaveDataProvider#getDuration()} — share through the common
 * corrupt-tracking read path: each returns the header value for a readable file,
 * and each short-circuits to its documented default once the file has been
 * recorded as corrupt after an initial failure.</p>
 */
public class FileWaveDataProviderTest extends TestSuiteBase {

	/** Sample rate written into the fixtures. */
	private static final long SAMPLE_RATE = 44100L;

	/**
	 * Writes a silent signed-16-bit WAV fixture to a temporary file.
	 *
	 * @param channels number of channels to write
	 * @param frames   number of frames to write
	 * @return the temporary file, scheduled for deletion on JVM exit
	 * @throws IOException if the file cannot be written
	 */
	private File writeWav(int channels, int frames) throws IOException {
		File file = File.createTempFile("ar-filewaveprovider-test", ".wav");
		file.deleteOnExit();

		try (WavFile wav = WavFile.newWavFile(file, channels, frames, 16, SAMPLE_RATE)) {
			wav.writeFrames(new double[channels][frames]);
		}

		return file;
	}

	/**
	 * Each accessor reads the corresponding field from the WAV header of a
	 * readable file.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(timeout = 60000)
	public void metadataAccessorsReadHeader() throws IOException {
		File file = writeWav(2, 100);
		FileWaveDataProvider provider = new FileWaveDataProvider(file);

		Assert.assertEquals(44100, provider.getSampleRate());
		Assert.assertEquals(2, provider.getChannelCount());
		Assert.assertEquals(100L, provider.getCountLong());
		Assert.assertEquals(100.0 / 44100.0, provider.getDuration(), 1e-9);
	}

	/**
	 * A mono fixture reports a single channel, and once read every accessor is
	 * served from its cached field: the values survive deletion of the file,
	 * which would otherwise fail the read and mark the file corrupt.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(timeout = 60000)
	public void metadataAccessorsCacheAndAgree() throws IOException {
		File file = writeWav(1, 50);
		FileWaveDataProvider provider = new FileWaveDataProvider(file);

		Assert.assertEquals(SAMPLE_RATE, provider.getSampleRate());
		Assert.assertEquals(1, provider.getChannelCount());
		Assert.assertEquals(50L, provider.getCountLong());
		Assert.assertEquals(50.0 / 44100.0, provider.getDuration(), 1e-9);

		Assert.assertTrue(file.delete());

		Assert.assertEquals(SAMPLE_RATE, provider.getSampleRate());
		Assert.assertEquals(1, provider.getChannelCount());
		Assert.assertEquals(50L, provider.getCountLong());
		Assert.assertEquals(50.0 / 44100.0, provider.getDuration(), 1e-9);
	}

	/**
	 * An unreadable file throws a {@link RuntimeException} wrapping an
	 * {@link IOException} on first access, and every accessor then short-circuits
	 * to its documented default because the file has been recorded as corrupt.
	 */
	@Test(timeout = 60000)
	public void unreadableFileThrowsThenReturnsDefaults() {
		File missing = new File(System.getProperty("java.io.tmpdir"),
				"ar-filewaveprovider-missing-" + System.nanoTime() + ".wav");
		FileWaveDataProvider provider = new FileWaveDataProvider(missing);

		try {
			provider.getSampleRate();
			Assert.fail("Expected a RuntimeException for an unreadable file");
		} catch (RuntimeException expected) {
			Assert.assertTrue("cause should be the underlying IOException",
					expected.getCause() instanceof IOException);
		}

		Assert.assertEquals(0, provider.getSampleRate());
		Assert.assertEquals(0L, provider.getCountLong());
		Assert.assertEquals(0, provider.getChannelCount());
		Assert.assertEquals(0.0, provider.getDuration(), 0.0);
	}

	/**
	 * The shared load path records an unreadable file as corrupt: the first
	 * {@link FileWaveDataProvider#load()} throws a {@link RuntimeException}
	 * wrapping the underlying {@link IOException}, and a subsequent load
	 * short-circuits to {@code null} because the file is now tracked as corrupt
	 * rather than retried. This pins the fifth corrupt-tracking call site, which
	 * the metadata-accessor tests do not reach.
	 */
	@Test(timeout = 60000)
	public void loadTracksCorruptFileThenReturnsNull() {
		File missing = new File(System.getProperty("java.io.tmpdir"),
				"ar-filewaveprovider-load-missing-" + System.nanoTime() + ".wav");
		ExposedProvider provider = new ExposedProvider(missing);

		try {
			provider.callLoad();
			Assert.fail("Expected a RuntimeException for an unreadable file");
		} catch (RuntimeException expected) {
			Assert.assertTrue("cause should be the underlying IOException",
					expected.getCause() instanceof IOException);
		}

		Assert.assertNull("a corrupt file should load as null on subsequent access",
				provider.callLoad());
	}

	/**
	 * Exposes the protected {@link FileWaveDataProvider#load()} so the shared
	 * corrupt-tracking load path can be exercised directly from this test
	 * package, where {@link FileWaveDataProvider#get()} cannot reach the
	 * corrupt-null branch (its validity predicate dereferences the loaded data).
	 */
	private static class ExposedProvider extends FileWaveDataProvider {
		/**
		 * Creates a provider for the given file.
		 *
		 * @param file the file to load
		 */
		ExposedProvider(File file) {
			super(file);
		}

		/**
		 * Invokes the inherited protected {@link FileWaveDataProvider#load()}.
		 *
		 * @return the loaded data, or {@code null} if the file is tracked as corrupt
		 */
		WaveData callLoad() {
			return load();
		}
	}
}
