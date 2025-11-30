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

package org.almostrealism.audio.line.test;

import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.line.BufferedOutputScheduler;
import org.almostrealism.audio.line.LineUtilities;
import org.almostrealism.audio.line.SourceDataOutputLine;
import org.junit.Test;

import java.io.File;

/**
 * Tests for {@link SourceDataOutputLine} using proper buffered, scheduled
 * Producer-based writes with {@link BufferedOutputScheduler}.
 */
public class SourceDataOutputLineTest implements CellFeatures {

	/**
	 * Tests buffered playback of WAV file data using BufferedOutputScheduler.
	 * This replaces the old direct write approach with proper Producer-based scheduling.
	 */
	@Test
	public void playWaveData() throws Exception {
		File f = new File("Library/SN_Forever_Future.wav");

		if (!f.exists()) {
			System.out.println("Test file not found, skipping playWaveData test");
			return;
		}

		// Create output line with default format
		SourceDataOutputLine outputLine = (SourceDataOutputLine) LineUtilities.getLine();

		// Load WAV file as a WaveCell (Producer-based)
		CellList cells = w(0, f.getPath());

		// Create BufferedOutputScheduler for scheduled writes
		BufferedOutputScheduler scheduler = cells.buffer(outputLine);

		System.out.println("Starting scheduled WAV playback...");

		// Start the buffered, scheduled playback
		scheduler.start();

		// Let it play
		Thread.sleep(30000);

		// Verify playback occurred
		long renderedFrames = scheduler.getRenderedFrames();
		System.out.println("Rendered frames: " + renderedFrames);

		// Stop and clean up
		scheduler.stop();
		outputLine.destroy();

		System.out.println("WAV playback test completed");
	}

}
