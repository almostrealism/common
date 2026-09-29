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

package org.almostrealism.util;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * Pins the observable behavior of {@link Chart}, the ASCII running-value chart:
 * entries and messages append lines, the bar width tracks the configured scale,
 * peaks and valleys are annotated once enough points accumulate, the rendered
 * output carries a range header, the point cap discards the oldest lines, and
 * the raw values can be persisted to a file.
 */
public class ChartTest extends TestSuiteBase {

	/** Adding an entry appends a timestamped chart line. */
	@Test(timeout = 10000)
	public void addEntryAppendsTimestampedLine() {
		Chart chart = new Chart();
		Assert.assertEquals(0, chart.size());

		chart.addEntry(1.0);

		Assert.assertEquals(1, chart.size());
		Assert.assertTrue("Chart line should begin with a timestamp bracket",
				chart.get(0).startsWith("["));
	}

	/** The number of bar characters scales inversely with the configured scale. */
	@Test(timeout = 10000)
	public void barWidthTracksScale() {
		Chart coarse = new Chart();
		coarse.setScale(1.0);
		coarse.addEntry(1.0);
		long coarseBars = coarse.get(0).chars().filter(c -> c == '#').count();

		Chart fine = new Chart();
		fine.setScale(0.05);
		fine.addEntry(1.0);
		long fineBars = fine.get(0).chars().filter(c -> c == '#').count();

		// A value of 1.0 clears one 1.0-wide step but twenty 0.05-wide steps.
		Assert.assertEquals(1, coarseBars);
		Assert.assertEquals(20, fineBars);
	}

	/** A rising-then-falling sequence annotates the peak value. */
	@Test(timeout = 10000)
	public void annotatesLocalPeak() {
		Chart chart = new Chart();
		chart.addEntry(1.0);
		chart.addEntry(2.0);
		chart.addEntry(3.0);
		chart.addEntry(1.0);

		Assert.assertTrue("Expected the peak (3.000) to be annotated with a run of spaces",
				chart.toString().contains("]" + " ".repeat(20) + "3.000"));
	}

	/** A falling-then-rising sequence annotates the valley value. */
	@Test(timeout = 10000)
	public void annotatesLocalValley() {
		Chart chart = new Chart();
		chart.addEntry(5.0);
		chart.addEntry(4.0);
		chart.addEntry(3.0);
		chart.addEntry(4.0);

		Assert.assertTrue("Expected the valley (3.000) to be annotated with a run of spaces",
				chart.toString().contains("[" + " ".repeat(20) + "3.000"));
	}

	/** A message line is appended verbatim after its timestamp. */
	@Test(timeout = 10000)
	public void addMessageAppendsMessageLine() {
		Chart chart = new Chart();
		chart.addMessage("checkpoint reached");

		Assert.assertEquals(1, chart.size());
		Assert.assertTrue(chart.get(0).endsWith("checkpoint reached"));
	}

	/** The rendered report carries a range header line. */
	@Test(timeout = 10000)
	public void toStringCarriesRangeHeader() {
		Chart chart = new Chart();
		chart.addEntry(2.0);

		String rendered = chart.toString();
		Assert.assertTrue(rendered.startsWith("Range: ("));
		Assert.assertTrue(rendered.contains("[--------]:"));
	}

	/**
	 * Once the entry cap is exceeded the oldest lines are discarded, not arbitrary ones.
	 * The sequence rises monotonically, so no peak/valley annotation fires and each
	 * {@link Chart#addEntry(double)} appends exactly one line, leaving exactly the last
	 * three. Values above {@code scale * div} (0.05 * 80 = 4.0) render a trailing
	 * formatted value, so the three newest values must appear and the newest discarded
	 * value (9) must not.
	 */
	@Test(timeout = 10000)
	public void capDiscardsOldestLines() {
		Chart chart = new Chart(3);
		for (int i = 1; i <= 12; i++) {
			chart.addEntry(i);
		}

		Assert.assertEquals("Cap should retain exactly the configured number of lines",
				3, chart.size());

		String rendered = chart.toString();
		Assert.assertTrue("Newest retained value 10 should be present",
				rendered.contains(" 10.000"));
		Assert.assertTrue("Newest retained value 11 should be present",
				rendered.contains(" 11.000"));
		Assert.assertTrue("Newest retained value 12 should be present",
				rendered.contains(" 12.000"));
		Assert.assertFalse("Discarded value 9 should be absent once the oldest lines are dropped",
				rendered.contains(" 9.000"));
	}

	/** {@link Chart#storeValues(File)} writes each recorded value on its own line. */
	@Test(timeout = 10000)
	public void storeValuesWritesRawValues() throws IOException {
		Chart chart = new Chart();
		chart.addEntry(1.0);
		chart.addEntry(2.5);

		File out = File.createTempFile("chart-values", ".txt");
		out.deleteOnExit();
		chart.storeValues(out);

		List<String> lines = Files.readAllLines(out.toPath());
		Assert.assertEquals(2, lines.size());
		Assert.assertEquals("1.0", lines.get(0));
		Assert.assertEquals("2.5", lines.get(1));
	}
}
