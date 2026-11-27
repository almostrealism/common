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

package org.almostrealism.audio;

import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

/**
 * Pins the handling of a file that cannot be read when the same file is
 * analysed more than once within a single JVM.
 *
 * <p>{@link FileWaveDataProvider} records a file as corrupt after its first
 * failed read and thereafter reports a sample rate of 0. A second analysis in
 * the same process must warn and skip the file exactly as the first failure
 * did, and must not surface a divide-by-zero from the resampling path to the
 * library's error listener.</p>
 */
public class UnreadableFileRetryTest extends TestSuiteBase {

	/** Target sample rate of the library under test, in Hz. */
	private static final int SAMPLE_RATE = 44100;

	/** The library under test; stopped after each case so no worker leaks. */
	private AudioLibrary library;

	/** Stops the library's analysis executor after each case. */
	@After
	public void tearDown() {
		if (library != null) library.stop();
	}

	/**
	 * Analysing an unreadable file twice in one JVM warns and skips both times,
	 * with nothing sent to the error listener: the first access marks the file
	 * corrupt through an {@link IOException} (warned, not reported), and the
	 * retry — which previously divided by the corrupt file's zero sample rate —
	 * is skipped as unreadable rather than reported.
	 *
	 * @throws IOException if the temporary library directory cannot be created
	 */
	@Test(timeout = 60000)
	public void secondAnalysisOfAnUnreadableFileWarnsAndSkips() throws IOException {
		File root = Files.createTempDirectory("ar-unreadable-retry-test").toFile();
		root.deleteOnExit();
		library = new AudioLibrary(new FileWaveDataProviderNode(root), SAMPLE_RATE, null);

		List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
		library.setErrorListener(errors::add);

		File missing = new File(root, "never-written-" + System.nanoTime() + ".wav");
		FileWaveDataProvider provider = new FileWaveDataProvider(missing.getPath());

		Assert.assertNull("the first analysis of an unreadable file is skipped",
				library.computeDetails(provider, false));
		Assert.assertFalse("the failed read marks the file corrupt", provider.isReadable());
		Assert.assertEquals("the first failure is warned, not reported as an error",
				0, errors.size());

		Assert.assertNull("the retry of a file already marked corrupt is skipped",
				library.computeDetails(provider, false));
		Assert.assertEquals("the retry must warn and skip, never reach the error listener",
				0, errors.size());
	}
}
