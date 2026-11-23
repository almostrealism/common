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

package org.almostrealism.studio.optimize.test;

import org.almostrealism.studio.optimize.AudioSceneBenchmark;
import org.almostrealism.studio.optimize.AudioSceneBenchmark.BenchmarkResult;
import org.almostrealism.studio.optimize.AudioSceneBenchmark.TickStats;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Behavioral tests for the pure (hardware-independent) parts of {@link AudioSceneBenchmark}:
 * the {@link TickStats} distribution summary, the {@link BenchmarkResult} value object and its
 * derived realtime ratios, and the {@code parseDurations} / {@code resolveMultiChannels}
 * configuration parsers.
 *
 * <p>The benchmark's actual render loops require a fully loaded {@code AudioScene} and the
 * sample library, so they are not exercised here; what is pinned is the statistics math and the
 * input parsing that decide what those loops run — the parts whose correctness is a matter of
 * arithmetic and string handling rather than audio hardware.</p>
 */
public class AudioSceneBenchmarkTest extends TestSuiteBase {
	/** Tolerance for the ratio comparisons, which are exact up to double rounding. */
	private static final double EPS = 1.0e-9;

	/** An empty sample array yields an all-zero summary rather than an index error. */
	@Test(timeout = 60000)
	public void tickStatsEmptySamplesAreAllZero() {
		TickStats stats = new TickStats(new long[0]);

		Assert.assertEquals(0, stats.getCount());
		Assert.assertEquals(0, stats.getTotalMs());
		Assert.assertEquals(0, stats.getMinMs());
		Assert.assertEquals(0, stats.getP50Ms());
		Assert.assertEquals(0, stats.getP95Ms());
		Assert.assertEquals(0, stats.getMaxMs());
		Assert.assertEquals(0, stats.getFirstDecileMs());
		Assert.assertEquals(0, stats.getLastDecileMs());
	}

	/**
	 * A single sample is simultaneously the min, median, 95th percentile, max, and both decile
	 * means; the total equals that one sample. Values are supplied in nanoseconds and reported in
	 * milliseconds, so one millisecond is {@code 1_000_000} ns.
	 */
	@Test(timeout = 60000)
	public void tickStatsSingleSampleIsEveryStatistic() {
		TickStats stats = new TickStats(new long[] { 7_000_000L });

		Assert.assertEquals(1, stats.getCount());
		Assert.assertEquals(7, stats.getTotalMs());
		Assert.assertEquals(7, stats.getMinMs());
		Assert.assertEquals(7, stats.getP50Ms());
		Assert.assertEquals(7, stats.getP95Ms());
		Assert.assertEquals(7, stats.getMaxMs());
		Assert.assertEquals(7, stats.getFirstDecileMs());
		Assert.assertEquals(7, stats.getLastDecileMs());
	}

	/**
	 * With twenty samples of one through twenty milliseconds the statistics are computed over the
	 * sorted order while the deciles are computed over the supplied order. The sum is
	 * {@code 1+..+20 = 210}; the decile size is {@code 20/10 = 2}, so the first-decile mean is the
	 * mean of the first two supplied samples and the last-decile mean is the mean of the last two.
	 * The median index is {@code count/2 = 10} (the 11th smallest) and the 95th-percentile index is
	 * {@code floor(20*0.95) = 19} (the largest).
	 */
	@Test(timeout = 60000)
	public void tickStatsPercentilesAndDecilesOverTwentySamples() {
		long[] samples = new long[20];
		for (int i = 0; i < 20; i++) samples[i] = (i + 1) * 1_000_000L;

		TickStats stats = new TickStats(samples);

		Assert.assertEquals(20, stats.getCount());
		Assert.assertEquals(210, stats.getTotalMs());
		Assert.assertEquals(1, stats.getMinMs());
		Assert.assertEquals(20, stats.getMaxMs());
		Assert.assertEquals("median is the 11th smallest (value 11)", 11, stats.getP50Ms());
		Assert.assertEquals("95th percentile is the largest (value 20)", 20, stats.getP95Ms());
		Assert.assertEquals("first decile is the mean of samples 1 and 2", 1, stats.getFirstDecileMs());
		Assert.assertEquals("last decile is the mean of samples 19 and 20", 19, stats.getLastDecileMs());
	}

	/**
	 * The constructor does not reorder the caller's array: the decile means depend on the supplied
	 * order, and the caller may continue to use the array afterwards. Here the samples arrive in
	 * descending order, so the first decile (first two supplied) is larger than the last decile.
	 */
	@Test(timeout = 60000)
	public void tickStatsDecilesRespectSuppliedOrder() {
		long[] samples = new long[20];
		for (int i = 0; i < 20; i++) samples[i] = (20 - i) * 1_000_000L;

		TickStats stats = new TickStats(samples);

		Assert.assertEquals("first decile is the mean of 20 and 19", 19, stats.getFirstDecileMs());
		Assert.assertEquals("last decile is the mean of 2 and 1", 1, stats.getLastDecileMs());
		Assert.assertEquals("the supplied array must not be reordered",
				20_000_000L, samples[0]);
	}

	/**
	 * {@code renderMs} is {@code setupMs + tickStats.totalMs}, and the three realtime ratios are
	 * those wall-clock times (in seconds) divided by the audio duration. With ten audio seconds,
	 * two seconds of setup and eight seconds of ticks, the render ratio is {@code 1.0}, the setup
	 * ratio {@code 0.2}, and the tick ratio {@code 0.8}.
	 */
	@Test(timeout = 60000)
	public void benchmarkResultDerivesRenderMsAndRatios() {
		TickStats ticks = new TickStats(new long[] { 8_000L * 1_000_000L });
		BenchmarkResult r = new BenchmarkResult("multi", 64, "0,1", 7,
				10.0, 441000, 8192, 2_000L, ticks, 12_000L,
				"p.xml", "t.csv", "m.wav", 1, true);

		Assert.assertEquals("multi", r.getMode());
		Assert.assertEquals(64, r.getMeasures());
		Assert.assertEquals("0,1", r.getChannels());
		Assert.assertEquals(7, r.getFeatureLevel());
		Assert.assertEquals(10.0, r.getAudioSeconds(), EPS);
		Assert.assertEquals(441000, r.getFrames());
		Assert.assertEquals(8192, r.getBufferSize());
		Assert.assertEquals(2_000L, r.getSetupMs());
		Assert.assertSame(ticks, r.getTickStats());
		Assert.assertEquals("renderMs is setup plus tick total", 10_000L, r.getRenderMs());
		Assert.assertEquals(12_000L, r.getSectionMs());
		Assert.assertEquals("p.xml", r.getProfileXmlPath());
		Assert.assertEquals("t.csv", r.getTimelineCsvPath());
		Assert.assertEquals("m.wav", r.getWavPath());
		Assert.assertEquals(1, r.getRepeat());
		Assert.assertTrue(r.isWarmup());

		Assert.assertEquals(1.0, r.getRatio(), EPS);
		Assert.assertEquals(0.2, r.getSetupRatio(), EPS);
		Assert.assertEquals(0.8, r.getTickRatio(), EPS);
	}

	/**
	 * A non-positive audio duration cannot form a realtime ratio (division by zero), so each of the
	 * three ratio accessors reports {@code NaN} rather than an infinity or a divide error.
	 */
	@Test(timeout = 60000)
	public void benchmarkResultRatiosAreNaNWhenNoAudio() {
		TickStats ticks = new TickStats(new long[] { 5_000_000L });
		BenchmarkResult r = new BenchmarkResult("single", 8, "1", 0,
				0.0, 0, 8192, 1_000L, ticks, 2_000L,
				null, null, "m.wav", 0, false);

		Assert.assertTrue(Double.isNaN(r.getRatio()));
		Assert.assertTrue(Double.isNaN(r.getSetupRatio()));
		Assert.assertTrue(Double.isNaN(r.getTickRatio()));
		Assert.assertFalse(r.isWarmup());
		Assert.assertNull(r.getProfileXmlPath());
		Assert.assertNull(r.getTimelineCsvPath());
	}

	/** Durations parse in order, ignoring surrounding whitespace and empty tokens. */
	@Test(timeout = 60000)
	public void parseDurationsReadsPositiveIntegersInOrder() {
		Assert.assertArrayEquals(new int[] { 8, 16, 64 },
				AudioSceneBenchmark.parseDurations("8, 16 ,64"));
		Assert.assertArrayEquals(new int[] { 32 },
				AudioSceneBenchmark.parseDurations(" 32 , "));
	}

	/** A zero or negative duration is rejected, since a benchmark cannot render a non-positive span. */
	@Test(timeout = 60000)
	public void parseDurationsRejectsNonPositive() {
		try {
			AudioSceneBenchmark.parseDurations("8,0,16");
			Assert.fail("A non-positive duration must be rejected");
		} catch (IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains("0"));
		}

		try {
			AudioSceneBenchmark.parseDurations("-4");
			Assert.fail("A negative duration must be rejected");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}

	/** A spec with no usable tokens has no durations to run and is rejected. */
	@Test(timeout = 60000)
	public void parseDurationsRejectsEmptySpec() {
		try {
			AudioSceneBenchmark.parseDurations(" , ");
			Assert.fail("An empty duration spec must be rejected");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}

	/**
	 * An explicit comma-separated channel list is parsed directly into an immutable index list,
	 * without constructing a scene (that path is only taken for the {@code "all"} keyword).
	 */
	@Test(timeout = 60000)
	public void resolveMultiChannelsParsesExplicitList() {
		List<Integer> channels = AudioSceneBenchmark.resolveMultiChannels("0, 2 ,5", 16);

		Assert.assertEquals(List.of(0, 2, 5), channels);

		try {
			channels.add(9);
			Assert.fail("The returned channel list must be immutable");
		} catch (UnsupportedOperationException expected) {
			// expected
		}
	}

	/** A channel spec that lists no indices is rejected rather than silently rendering nothing. */
	@Test(timeout = 60000)
	public void resolveMultiChannelsRejectsEmptyIndexList() {
		try {
			AudioSceneBenchmark.resolveMultiChannels(",,", 16);
			Assert.fail("A channel spec with no indices must be rejected");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}
}
