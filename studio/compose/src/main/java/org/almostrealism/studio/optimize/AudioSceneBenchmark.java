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

package org.almostrealism.studio.optimize;

import io.almostrealism.code.DataContext;
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.filter.AudioProcessingUtils;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.heredity.Genome;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.io.Console;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.health.HealthComputationAdapter;
import org.almostrealism.studio.health.MultiChannelAudioOutput;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Standalone benchmark harness for {@link AudioSceneOptimizer}-style audio rendering. Renders
 * the same {@link AudioScene} configuration across a sweep of total-measure durations using two
 * code paths and reports per-section setup, tick, and render timings together with per-tick
 * distribution statistics and an {@link OperationProfileNode} XML capture for downstream analysis
 * via {@code mcp__ar-profile-analyzer__*} tools.
 *
 * <ul>
 *   <li><b>multi</b> — multi-channel render driven through the same temporal pipeline used by
 *       {@link AudioSceneOptimizer} during health evaluation. Channel set is configurable via
 *       {@link #MULTI_CHANNELS_PROPERTY}; default is all channels.</li>
 *   <li><b>single</b> — single-channel render via the
 *       {@link AudioScenePopulation#generate(int, int, java.util.function.Supplier,
 *       java.util.function.Consumer)} path used by ringsdesktop pattern preview generation.</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@code AR_BENCHMARK_DURATIONS} — comma-separated list of total-measure values to sweep
 *       (default {@code "8,16,32,64"}).</li>
 *   <li>{@code AR_BENCHMARK_CHANNEL} — channel index used for single-channel runs (default {@code 0}).</li>
 *   <li>{@code AR_BENCHMARK_MULTI_CHANNELS} — comma-separated channel indices for the multi
 *       sweep (e.g. {@code "0,1,2"}), or {@code "all"} for every channel (default {@code "all"}).</li>
 *   <li>{@code AR_BENCHMARK_RUN_MULTI} — set to {@code false} to skip the multi-channel sweep.</li>
 *   <li>{@code AR_BENCHMARK_RUN_SINGLE} — set to {@code false} to skip the single-channel sweep.</li>
 *   <li>{@code AR_BENCHMARK_FEATURE_LEVEL} — feature level passed to
 *       {@link AudioSceneOptimizer#setFeatureLevel(int)} (default {@code 7}).</li>
 *   <li>{@code AR_BENCHMARK_STEMS} — set to {@code false} to disable per-channel stem WAV output
 *       (default {@code true}; mirrors {@code AudioPopulationOptimizer.enableStemOutput}).</li>
 *   <li>{@code AR_BENCHMARK_REPEATS} — number of render repetitions per (mode, duration) pair
 *       (default {@code 1}). The first repetition is reported as a warm-up.</li>
 *   <li>{@code AR_BENCHMARK_REPORT} — output path for the CSV report (default
 *       {@code results/audio-scene-benchmark.csv}).</li>
 *   <li>{@code AR_BENCHMARK_PROFILE_DIR} — directory for {@link OperationProfileNode} XML files
 *       (default {@code results/profiles}).</li>
 * </ul>
 */
public class AudioSceneBenchmark {

	/** Property name for the comma-separated list of measure-count durations to sweep. */
	public static final String DURATIONS_PROPERTY = "AR_BENCHMARK_DURATIONS";

	/** Property name for the channel index used in single-channel runs. */
	public static final String CHANNEL_PROPERTY = "AR_BENCHMARK_CHANNEL";

	/** Property name for the comma-separated channel list used in multi-channel runs. */
	public static final String MULTI_CHANNELS_PROPERTY = "AR_BENCHMARK_MULTI_CHANNELS";

	/** Property name controlling whether the multi-channel sweep is executed. */
	public static final String RUN_MULTI_PROPERTY = "AR_BENCHMARK_RUN_MULTI";

	/** Property name controlling whether the single-channel sweep is executed. */
	public static final String RUN_SINGLE_PROPERTY = "AR_BENCHMARK_RUN_SINGLE";

	/** Property name for the feature level applied to the scene. */
	public static final String FEATURE_LEVEL_PROPERTY = "AR_BENCHMARK_FEATURE_LEVEL";

	/** Property name for the per-channel stem output toggle. */
	public static final String STEMS_PROPERTY = "AR_BENCHMARK_STEMS";

	/** Property name for the number of repetitions per (mode, duration) pair. */
	public static final String REPEATS_PROPERTY = "AR_BENCHMARK_REPEATS";

	/** Property name for the CSV report destination path. */
	public static final String REPORT_PROPERTY = "AR_BENCHMARK_REPORT";

	/** Property name for the {@link OperationProfileNode} XML directory. */
	public static final String PROFILE_DIR_PROPERTY = "AR_BENCHMARK_PROFILE_DIR";

	/** Property name for the per-tick timeline CSV directory. */
	public static final String TIMELINE_DIR_PROPERTY = "AR_BENCHMARK_TIMELINE_DIR";

	/** Property name for the toggle that disables {@link OperationProfileNode} XML capture. */
	public static final String DISABLE_PROFILE_PROPERTY = "AR_BENCHMARK_DISABLE_PROFILE";

	/**
	 * Property name for the inter-repeat pause (milliseconds). When set to a positive
	 * value, the JVM sleeps for that duration after each render repetition, writing a
	 * sentinel file at {@code <reportDir>/rep-<index>-paused.marker} for external
	 * tooling (e.g. ar-jmx class histogram capture) to observe the pause window.
	 */
	public static final String INTER_REPEAT_PAUSE_PROPERTY = "AR_BENCHMARK_INTER_REPEAT_PAUSE_MS";

	/**
	 * Property name for the toggle that calls {@link NoteAudioProvider#clearCache()}
	 * after each render repetition. Used to verify whether the static cache in
	 * {@code NoteAudioProvider} is the carrier of inter-section heap retention.
	 */
	public static final String CLEAR_AUDIO_CACHE_PROPERTY = "AR_BENCHMARK_CLEAR_AUDIO_CACHE";

	/**
	 * Property name for the toggle that reuses one {@link AudioScene} and
	 * {@link AudioScenePopulation} across all multi-channel repetitions
	 * within a duration. Mirrors the optimizer's production lifecycle, where
	 * a single scene is built once and many genomes are evaluated against it.
	 * Default {@code false}; the default behaviour creates a fresh scene per
	 * repetition.
	 */
	public static final String REUSE_SCENE_PROPERTY = "AR_BENCHMARK_REUSE_SCENE";

	/** Default measure-count durations swept when {@link #DURATIONS_PROPERTY} is unset. */
	public static final String DEFAULT_DURATIONS = "8,16,32,64";

	/** Default CSV report path used when {@link #REPORT_PROPERTY} is unset. */
	public static final String DEFAULT_REPORT_PATH = "results/audio-scene-benchmark.csv";

	/** Default profile XML directory used when {@link #PROFILE_DIR_PROPERTY} is unset. */
	public static final String DEFAULT_PROFILE_DIR = "results/profiles";

	/** Default per-tick timeline directory used when {@link #TIMELINE_DIR_PROPERTY} is unset. */
	public static final String DEFAULT_TIMELINE_DIR = "results/timelines";

	/** PCM buffer size used for both render paths. Mirrors {@link AudioScene#DEFAULT_REALTIME_BUFFER_SIZE}. */
	public static final int BUFFER_SIZE = AudioScene.DEFAULT_REALTIME_BUFFER_SIZE;

	/**
	 * Aggregated timing data for one section's tick loop.
	 */
	public static class TickStats {
		/** Number of ticks executed (length of {@code samples}). */
		private final int count;

		/** Sum of all tick durations in nanoseconds. */
		private final long totalNs;

		/** Minimum tick duration observed, in nanoseconds. */
		private final long minNs;

		/** 50th-percentile tick duration, in nanoseconds. */
		private final long p50Ns;

		/** 95th-percentile tick duration, in nanoseconds. */
		private final long p95Ns;

		/** Maximum tick duration observed, in nanoseconds. */
		private final long maxNs;

		/** Mean tick duration over the first 10% of ticks, in nanoseconds. */
		private final long firstDecileMeanNs;

		/** Mean tick duration over the last 10% of ticks, in nanoseconds. */
		private final long lastDecileMeanNs;

		/**
		 * Computes summary statistics from a per-tick nanosecond array.
		 *
		 * @param samples per-tick durations in nanoseconds (consumed; sorted in place)
		 */
		public TickStats(long[] samples) {
			this.count = samples.length;
			if (count == 0) {
				this.totalNs = 0;
				this.minNs = 0;
				this.p50Ns = 0;
				this.p95Ns = 0;
				this.maxNs = 0;
				this.firstDecileMeanNs = 0;
				this.lastDecileMeanNs = 0;
				return;
			}

			long sum = 0;
			int decile = Math.max(1, count / 10);
			long firstSum = 0;
			long lastSum = 0;
			for (int i = 0; i < count; i++) {
				sum += samples[i];
				if (i < decile) firstSum += samples[i];
				if (i >= count - decile) lastSum += samples[i];
			}
			this.totalNs = sum;
			this.firstDecileMeanNs = firstSum / decile;
			this.lastDecileMeanNs = lastSum / decile;

			long[] sorted = samples.clone();
			Arrays.sort(sorted);
			this.minNs = sorted[0];
			this.p50Ns = sorted[Math.min(count - 1, count / 2)];
			this.p95Ns = sorted[Math.min(count - 1, (int) (count * 0.95))];
			this.maxNs = sorted[count - 1];
		}

		/** Returns the number of ticks recorded. */
		public int getCount() { return count; }

		/** Returns the total tick wall-clock time in milliseconds. */
		public long getTotalMs() { return totalNs / 1_000_000L; }

		/** Returns the minimum per-tick duration in milliseconds. */
		public long getMinMs() { return minNs / 1_000_000L; }

		/** Returns the median per-tick duration in milliseconds. */
		public long getP50Ms() { return p50Ns / 1_000_000L; }

		/** Returns the 95th-percentile per-tick duration in milliseconds. */
		public long getP95Ms() { return p95Ns / 1_000_000L; }

		/** Returns the maximum per-tick duration in milliseconds. */
		public long getMaxMs() { return maxNs / 1_000_000L; }

		/** Returns the mean per-tick duration over the first 10% of ticks, in milliseconds. */
		public long getFirstDecileMs() { return firstDecileMeanNs / 1_000_000L; }

		/** Returns the mean per-tick duration over the last 10% of ticks, in milliseconds. */
		public long getLastDecileMs() { return lastDecileMeanNs / 1_000_000L; }
	}

	/**
	 * Result row produced by a single benchmark render.
	 */
	public static class BenchmarkResult {
		/** Render path identifier; either {@code "multi"} or {@code "single"}. */
		private final String mode;

		/** Number of audio measures rendered. */
		private final int measures;

		/** Comma-separated channel index list rendered (e.g. {@code "0,1,2,3,4,5"} or {@code "1"}). */
		private final String channels;

		/** Feature level applied to the scene for this section. */
		private final int featureLevel;

		/** Equivalent audio duration in seconds. */
		private final double audioSeconds;

		/** Total audio frames rendered. */
		private final int frames;

		/** Wall-clock milliseconds spent inside {@code setup.run()}. */
		private final long setupMs;

		/** Per-tick timing distribution. */
		private final TickStats tickStats;

		/** Wall-clock milliseconds spent inside the {@code setup}+tick loop ({@code setupMs + tickStats.totalMs}). */
		private final long renderMs;

		/** Wall-clock milliseconds around the entire benchmark section (build, render, teardown). */
		private final long sectionMs;

		/** Path to the {@link OperationProfileNode} XML for this section, or {@code null} if not captured. */
		private final String profileXmlPath;

		/** Path to the per-tick timeline CSV for this section, or {@code null} if not captured. */
		private final String timelineCsvPath;

		/** Path to the rendered WAV master file. */
		private final String wavPath;

		/** 0-based repetition index for this (mode, measures) pair. */
		private final int repeat;

		/** {@code true} when this is the first repetition for the (mode, measures) pair (warm-up). */
		private final boolean warmup;

		/**
		 * Creates a benchmark result.
		 *
		 * @param mode           render path identifier
		 * @param measures       total measures rendered
		 * @param channels       comma-separated channel index list
		 * @param featureLevel   feature level applied
		 * @param audioSeconds   equivalent audio duration in seconds
		 * @param frames         total frames rendered
		 * @param setupMs        wall-clock milliseconds spent in setup
		 * @param tickStats      per-tick distribution statistics
		 * @param sectionMs      wall-clock milliseconds for build+render+teardown
		 * @param profileXmlPath  path to the profile XML, or {@code null}
		 * @param timelineCsvPath path to the per-tick timeline CSV, or {@code null}
		 * @param wavPath         path to the rendered master WAV
		 * @param repeat          repetition index
		 * @param warmup          {@code true} when this is the first repetition
		 */
		public BenchmarkResult(String mode, int measures, String channels, int featureLevel,
							   double audioSeconds, int frames, long setupMs, TickStats tickStats,
							   long sectionMs, String profileXmlPath, String timelineCsvPath,
							   String wavPath, int repeat, boolean warmup) {
			this.mode = mode;
			this.measures = measures;
			this.channels = channels;
			this.featureLevel = featureLevel;
			this.audioSeconds = audioSeconds;
			this.frames = frames;
			this.setupMs = setupMs;
			this.tickStats = tickStats;
			this.renderMs = setupMs + tickStats.getTotalMs();
			this.sectionMs = sectionMs;
			this.profileXmlPath = profileXmlPath;
			this.timelineCsvPath = timelineCsvPath;
			this.wavPath = wavPath;
			this.repeat = repeat;
			this.warmup = warmup;
		}

		/** Returns the render path identifier. */
		public String getMode() { return mode; }

		/** Returns the total measures rendered. */
		public int getMeasures() { return measures; }

		/** Returns the comma-separated channel index list. */
		public String getChannels() { return channels; }

		/** Returns the feature level applied. */
		public int getFeatureLevel() { return featureLevel; }

		/** Returns the audio duration in seconds. */
		public double getAudioSeconds() { return audioSeconds; }

		/** Returns the total frames rendered. */
		public int getFrames() { return frames; }

		/** Returns wall-clock milliseconds spent in {@code setup.run()}. */
		public long getSetupMs() { return setupMs; }

		/** Returns the per-tick statistics. */
		public TickStats getTickStats() { return tickStats; }

		/** Returns wall-clock milliseconds spent in setup+tick (matches the previous schema). */
		public long getRenderMs() { return renderMs; }

		/** Returns wall-clock milliseconds for the entire section. */
		public long getSectionMs() { return sectionMs; }

		/** Returns the profile XML path, or {@code null}. */
		public String getProfileXmlPath() { return profileXmlPath; }

		/** Returns the per-tick timeline CSV path, or {@code null}. */
		public String getTimelineCsvPath() { return timelineCsvPath; }

		/** Returns the rendered WAV master path. */
		public String getWavPath() { return wavPath; }

		/** Returns the repetition index. */
		public int getRepeat() { return repeat; }

		/** Returns {@code true} when this is the warm-up repetition. */
		public boolean isWarmup() { return warmup; }

		/** Returns render-time / audio-time. */
		public double getRatio() {
			return audioSeconds <= 0 ? Double.NaN : (renderMs / 1000.0) / audioSeconds;
		}

		/** Returns setup-time / audio-time. */
		public double getSetupRatio() {
			return audioSeconds <= 0 ? Double.NaN : (setupMs / 1000.0) / audioSeconds;
		}

		/** Returns tick-total-time / audio-time. */
		public double getTickRatio() {
			return audioSeconds <= 0 ? Double.NaN : (tickStats.getTotalMs() / 1000.0) / audioSeconds;
		}
	}

	/**
	 * Entry point. Reads configuration from system properties, runs the configured sweeps, and
	 * writes a CSV report. Total wall-clock time of the entire {@code main} body is logged at
	 * the end so any overhead not captured per-section is visible.
	 *
	 * @param args ignored
	 * @throws IOException if log destinations cannot be opened
	 */
	public static void main(String[] args) throws IOException {
		long scriptStart = System.currentTimeMillis();

		ensureDirectory("results/logs");
		ensureDirectory("results");
		Console.root().addListener(OutputFeatures.fileOutput("results/logs/audio-scene-benchmark.out"));

		int featureLevel = SystemUtils.getInt(FEATURE_LEVEL_PROPERTY).orElse(7);
		AudioSceneOptimizer.setFeatureLevel(featureLevel);

		boolean stems = !"false".equalsIgnoreCase(SystemUtils.getProperty(STEMS_PROPERTY, "true"));
		AudioPopulationOptimizer.enableStemOutput = stems;

		AudioProcessingUtils.init();
		WaveData.init();

		int[] measures = parseDurations(SystemUtils.getProperty(DURATIONS_PROPERTY, DEFAULT_DURATIONS));
		int channel = SystemUtils.getInt(CHANNEL_PROPERTY).orElse(0);
		String multiChannelsSpec = SystemUtils.getProperty(MULTI_CHANNELS_PROPERTY, "all");
		boolean runMulti = !"false".equalsIgnoreCase(SystemUtils.getProperty(RUN_MULTI_PROPERTY, "true"));
		boolean runSingle = !"false".equalsIgnoreCase(SystemUtils.getProperty(RUN_SINGLE_PROPERTY, "true"));
		int repeats = Math.max(1, SystemUtils.getInt(REPEATS_PROPERTY).orElse(1));
		String reportPath = SystemUtils.getProperty(REPORT_PROPERTY, DEFAULT_REPORT_PATH);
		String profileDir = SystemUtils.getProperty(PROFILE_DIR_PROPERTY, DEFAULT_PROFILE_DIR);
		String timelineDir = SystemUtils.getProperty(TIMELINE_DIR_PROPERTY, DEFAULT_TIMELINE_DIR);
		boolean disableProfile = "true".equalsIgnoreCase(SystemUtils.getProperty(DISABLE_PROFILE_PROPERTY, "false"));
		long interRepeatPauseMs = Math.max(0L, SystemUtils.getInt(INTER_REPEAT_PAUSE_PROPERTY).orElse(0));
		boolean clearAudioCache = "true".equalsIgnoreCase(SystemUtils.getProperty(CLEAR_AUDIO_CACHE_PROPERTY, "false"));
		boolean reuseScene = "true".equalsIgnoreCase(SystemUtils.getProperty(REUSE_SCENE_PROPERTY, "false"));
		AudioScenePopulation.gcBeforeGenome =
				!"false".equalsIgnoreCase(SystemUtils.getProperty("AR_BENCHMARK_GC_BEFORE_GENOME", "true"));
		ensureDirectory(profileDir);
		ensureDirectory(timelineDir);

		Path markerDir = Paths.get(reportPath).toAbsolutePath().getParent();
		long jvmPid = ProcessHandle.current().pid();
		Console.root().println("AudioSceneBenchmark JVM PID=" + jvmPid);

		ensureTimelineCapacity(measures);

		Console.root().println("AudioSceneBenchmark starting (durations="
				+ Arrays.toString(measures) + ", channel=" + channel
				+ ", multiChannels=" + multiChannelsSpec
				+ ", runMulti=" + runMulti + ", runSingle=" + runSingle
				+ ", repeats=" + repeats + ", featureLevel=" + featureLevel
				+ ", stems=" + stems + ", profileDir=" + profileDir
				+ ", timelineDir=" + timelineDir
				+ ", disableProfile=" + disableProfile + ")");

		List<BenchmarkResult> results = new ArrayList<>();

		try {
			for (int totalMeasures : measures) {
				if (runMulti) {
					List<Integer> channelList = resolveMultiChannels(multiChannelsSpec, totalMeasures);
					if (reuseScene) {
						results.addAll(runReuseSceneMulti(totalMeasures, channelList,
								featureLevel, profileDir, timelineDir, disableProfile,
								repeats, markerDir, clearAudioCache, interRepeatPauseMs));
					} else {
						for (int r = 0; r < repeats; r++) {
							results.add(runMultiChannel(totalMeasures, channelList, featureLevel,
									profileDir, timelineDir, disableProfile, r, r == 0));
							if (clearAudioCache) NoteAudioProvider.clearCache();
							pauseForExternalSnapshot(markerDir, "multi", totalMeasures, r,
									interRepeatPauseMs);
						}
					}
				}
				if (runSingle) {
					for (int r = 0; r < repeats; r++) {
						results.add(runSingleChannel(totalMeasures, channel, featureLevel,
								profileDir, timelineDir, disableProfile, r, r == 0));
						if (clearAudioCache) NoteAudioProvider.clearCache();
						pauseForExternalSnapshot(markerDir, "single", totalMeasures, r,
								interRepeatPauseMs);
					}
				}
			}
		} finally {
			long totalMs = System.currentTimeMillis() - scriptStart;
			writeReport(reportPath, results, totalMs);
			printSummary(results, totalMs);

			Hardware.getLocalHardware().clearProfile();
			try {
				Hardware.getLocalHardware().getAllDataContexts().forEach(DataContext::destroy);
			} catch (Throwable t) {
				Console.root().println("WARN: data-context destroy failed: " + t);
			}

			// Force JVM exit. The Metal hardware data context cleanup can deadlock
			// with non-daemon native threads on this platform; for a one-shot
			// benchmark that has already written its CSV, profile XML, and timeline
			// files, a hard exit is preferable to a wedged process that blocks the
			// driver script from launching the next sweep iteration.
			System.exit(0);
		}
	}

	/**
	 * Runs the multi-channel render path for one duration. Setup and tick are timed separately;
	 * each tick is timed individually so the resulting {@link TickStats} can reveal whether
	 * per-buffer cost grows during the render. An {@link OperationProfileNode} XML is written to
	 * {@code profileDir} for kernel-level attribution via {@code mcp__ar-profile-analyzer__*}.
	 *
	 * @param totalMeasures   number of measures to render
	 * @param channels        pattern channel indices to include in the render
	 * @param featureLevel    feature level applied (recorded in the result row)
	 * @param profileDir      directory where the profile XML will be written
	 * @param timelineDir     directory where the per-tick timeline CSV will be written
	 * @param disableProfile  when {@code true}, skips {@link OperationProfileNode} capture
	 * @param repeat          repetition index for this duration
	 * @param warmup          {@code true} when this is the first repetition
	 * @return the benchmark result
	 */
	public static BenchmarkResult runMultiChannel(int totalMeasures, List<Integer> channels,
												  int featureLevel, String profileDir,
												  String timelineDir, boolean disableProfile,
												  int repeat, boolean warmup) {
		long sectionStart = System.currentTimeMillis();

		String channelsLabel = channels.stream().map(String::valueOf).collect(Collectors.joining(","));
		String tag = "multi-c" + channelsLabel.replace(",", "_") + "-" + totalMeasures + "m-r" + repeat;
		String wavPath = "results/benchmark-" + tag + ".wav";
		String profileXmlPath = disableProfile ? null : profileDir + "/benchmark-" + tag + ".xml";
		String timelineCsvPath = timelineDir + "/benchmark-" + tag + ".csv";

		AudioScene<?> scene = AudioSceneOptimizer.createScene();
		AudioScenePopulation pop = null;
		OperationProfileNode profile = disableProfile ? null : new OperationProfileNode("benchmark-" + tag);
		try {
			scene.setTotalMeasures(totalMeasures);

			double audioSeconds = scene.getContext().getTimeForDuration().applyAsDouble(totalMeasures);
			int frames = scene.getContext().getFrameForPosition().applyAsInt(totalMeasures);
			int bufferCount = (frames + BUFFER_SIZE - 1) / BUFFER_SIZE;

			Genome<PackedCollection> genome = scene.getGenome().random();
			pop = new AudioScenePopulation(scene, List.of(genome));

			File outFile = new File(wavPath);
			WaveOutput out = new WaveOutput(() -> outFile, 24, true);
			pop.init(genome, new MultiChannelAudioOutput(out), channels, BUFFER_SIZE);

			TemporalCellular cells = pop.enableGenome(0);
			Runnable setup = cells.setup().get();
			Runnable tick = cells.tick().get();

			if (profile != null) Hardware.getLocalHardware().assignProfile(profile);
			try {
				long setupStart = System.nanoTime();
				setup.run();
				long setupMs = (System.nanoTime() - setupStart) / 1_000_000L;

				long[] tickNs = new long[bufferCount];
				for (int b = 0; b < bufferCount; b++) {
					long t0 = System.nanoTime();
					tick.run();
					tickNs[b] = System.nanoTime() - t0;
				}
				TickStats tickStats = new TickStats(tickNs);

				out.write().get().run();
				out.reset();
				cells.reset();
				pop.disableGenome();

				writeTimeline(timelineCsvPath, tickNs);
				if (profile != null) saveProfile(profile, profileXmlPath);
				long sectionMs = System.currentTimeMillis() - sectionStart;
				BenchmarkResult result = new BenchmarkResult("multi", totalMeasures, channelsLabel,
						featureLevel, audioSeconds, frames, setupMs, tickStats, sectionMs,
						profileXmlPath, timelineCsvPath, wavPath, repeat, warmup);
				Console.root().println(formatResultLine(result));
				return result;
			} finally {
				if (profile != null) Hardware.getLocalHardware().clearProfile();
			}
		} finally {
			if (pop != null) pop.destroy();
			scene.destroy();
		}
	}

	/**
	 * Runs the multi-channel render path for one duration {@code repeats} times,
	 * reusing a single {@link AudioScene} and {@link AudioScenePopulation} across
	 * all repetitions. Mirrors the optimizer's production lifecycle in which one
	 * scene is built once and many genomes are evaluated against it; the per-rep
	 * boundary is therefore {@link AudioScenePopulation#enableGenome(int)} /
	 * {@link AudioScenePopulation#disableGenome()} rather than the per-rep
	 * scene/pop creation/destruction performed by {@link #runMultiChannel}.
	 *
	 * @param totalMeasures   number of measures to render
	 * @param channels        pattern channel indices to include in the render
	 * @param featureLevel    feature level applied (recorded in the result row)
	 * @param profileDir      directory where the profile XML will be written
	 * @param timelineDir     directory where the per-tick timeline CSV will be written
	 * @param disableProfile  when {@code true}, skips {@link OperationProfileNode} capture
	 * @param repeats         number of repetitions
	 * @param markerDir       directory in which to write inter-repeat pause markers
	 * @param clearAudioCache when {@code true}, drains the static audio cache between reps
	 * @param interRepeatPauseMs milliseconds to pause between reps (also fires {@code System.gc})
	 * @return one {@link BenchmarkResult} per repetition
	 */
	public static List<BenchmarkResult> runReuseSceneMulti(int totalMeasures, List<Integer> channels,
														   int featureLevel, String profileDir,
														   String timelineDir, boolean disableProfile,
														   int repeats, Path markerDir,
														   boolean clearAudioCache, long interRepeatPauseMs) {
		List<BenchmarkResult> results = new ArrayList<>();
		String channelsLabel = channels.stream().map(String::valueOf).collect(Collectors.joining(","));

		AudioScene<?> scene = AudioSceneOptimizer.createScene();
		AudioScenePopulation pop = null;
		try {
			scene.setTotalMeasures(totalMeasures);

			double audioSeconds = scene.getContext().getTimeForDuration().applyAsDouble(totalMeasures);
			int frames = scene.getContext().getFrameForPosition().applyAsInt(totalMeasures);
			int bufferCount = (frames + BUFFER_SIZE - 1) / BUFFER_SIZE;

			List<Genome<PackedCollection>> genomes = new ArrayList<>();
			for (int r = 0; r < repeats; r++) genomes.add(scene.getGenome().random());
			pop = new AudioScenePopulation(scene, genomes);

			String wavPath0 = "results/benchmark-multi-c"
					+ channelsLabel.replace(",", "_") + "-" + totalMeasures + "m-reuse-r0.wav";
			File outFile = new File(wavPath0);
			WaveOutput out = new WaveOutput(() -> outFile, 24, true);
			pop.init(genomes.get(0), new MultiChannelAudioOutput(out), channels, BUFFER_SIZE);

			for (int r = 0; r < repeats; r++) {
				long sectionStart = System.currentTimeMillis();
				String tag = "multi-c" + channelsLabel.replace(",", "_") + "-" + totalMeasures + "m-reuse-r" + r;
				String profileXmlPath = disableProfile ? null : profileDir + "/benchmark-" + tag + ".xml";
				String timelineCsvPath = timelineDir + "/benchmark-" + tag + ".csv";
				String wavPath = "results/benchmark-" + tag + ".wav";
				OperationProfileNode profile = disableProfile ? null : new OperationProfileNode("benchmark-" + tag);

				TemporalCellular cells = pop.enableGenome(r);
				Runnable setup = cells.setup().get();
				Runnable tick = cells.tick().get();

				if (profile != null) Hardware.getLocalHardware().assignProfile(profile);
				try {
					long setupStart = System.nanoTime();
					setup.run();
					long setupMs = (System.nanoTime() - setupStart) / 1_000_000L;

					long[] tickNs = new long[bufferCount];
					for (int b = 0; b < bufferCount; b++) {
						long t0 = System.nanoTime();
						tick.run();
						tickNs[b] = System.nanoTime() - t0;
					}
					TickStats tickStats = new TickStats(tickNs);

					out.reset();
					cells.reset();
					pop.disableGenome();

					writeTimeline(timelineCsvPath, tickNs);
					if (profile != null) saveProfile(profile, profileXmlPath);
					long sectionMs = System.currentTimeMillis() - sectionStart;
					BenchmarkResult result = new BenchmarkResult("multi-reuse", totalMeasures, channelsLabel,
							featureLevel, audioSeconds, frames, setupMs, tickStats, sectionMs,
							profileXmlPath, timelineCsvPath, wavPath, r, r == 0);
					Console.root().println(formatResultLine(result));
					results.add(result);
				} finally {
					if (profile != null) Hardware.getLocalHardware().clearProfile();
				}

				if (clearAudioCache) NoteAudioProvider.clearCache();
				pauseForExternalSnapshot(markerDir, "multi-reuse", totalMeasures, r, interRepeatPauseMs);
			}
		} finally {
			if (pop != null) pop.destroy();
			scene.destroy();
		}
		return results;
	}

	/**
	 * Runs the single-channel render path for one duration. Mirrors the
	 * {@link AudioScenePopulation#generate(int, int, java.util.function.Supplier,
	 * java.util.function.Consumer)} code path used by ringsdesktop pattern preview generation, but
	 * inlines the rendering loop so per-tick timings can be captured.
	 *
	 * @param totalMeasures   number of measures to render
	 * @param channel         pattern channel index to render
	 * @param featureLevel    feature level applied (recorded in the result row)
	 * @param profileDir      directory where the profile XML will be written
	 * @param timelineDir     directory where the per-tick timeline CSV will be written
	 * @param disableProfile  when {@code true}, skips {@link OperationProfileNode} capture
	 * @param repeat          repetition index for this duration
	 * @param warmup          {@code true} when this is the first repetition
	 * @return the benchmark result
	 */
	public static BenchmarkResult runSingleChannel(int totalMeasures, int channel, int featureLevel,
													String profileDir, String timelineDir,
													boolean disableProfile, int repeat, boolean warmup) {
		long sectionStart = System.currentTimeMillis();

		String tag = "single-c" + channel + "-" + totalMeasures + "m-r" + repeat;
		String wavPath = "results/benchmark-" + tag + ".wav";
		String profileXmlPath = disableProfile ? null : profileDir + "/benchmark-" + tag + ".xml";
		String timelineCsvPath = timelineDir + "/benchmark-" + tag + ".csv";

		AudioScene<?> scene = AudioSceneOptimizer.createScene();
		AudioScenePopulation pop = null;
		OperationProfileNode profile = disableProfile ? null : new OperationProfileNode("benchmark-" + tag);
		try {
			scene.setTotalMeasures(totalMeasures);

			ChannelInfo info = new ChannelInfo(channel);
			double audioSeconds = scene.getContext(List.of(info)).getTimeForDuration().applyAsDouble(totalMeasures);
			int frames = scene.getContext(List.of(info)).getFrameForPosition().applyAsInt(totalMeasures);
			int bufferCount = (frames + BUFFER_SIZE - 1) / BUFFER_SIZE;

			Genome<PackedCollection> genome = scene.getGenome().random();
			pop = new AudioScenePopulation(scene, List.of(genome));

			File outFile = new File(wavPath);
			WaveOutput out = new WaveOutput(() -> outFile, 24, true);
			pop.init(genome, new MultiChannelAudioOutput(out), List.of(channel), BUFFER_SIZE);

			TemporalCellular cells = pop.enableGenome(0);
			Runnable setup = cells.setup().get();
			Runnable tick = cells.tick().get();

			if (profile != null) Hardware.getLocalHardware().assignProfile(profile);
			try {
				long setupStart = System.nanoTime();
				setup.run();
				long setupMs = (System.nanoTime() - setupStart) / 1_000_000L;

				long[] tickNs = new long[bufferCount];
				for (int b = 0; b < bufferCount; b++) {
					long t0 = System.nanoTime();
					tick.run();
					tickNs[b] = System.nanoTime() - t0;
				}
				TickStats tickStats = new TickStats(tickNs);

				out.write().get().run();
				out.reset();
				cells.reset();
				pop.disableGenome();

				writeTimeline(timelineCsvPath, tickNs);
				if (profile != null) saveProfile(profile, profileXmlPath);
				long sectionMs = System.currentTimeMillis() - sectionStart;
				BenchmarkResult result = new BenchmarkResult("single", totalMeasures,
						String.valueOf(channel), featureLevel, audioSeconds, frames, setupMs,
						tickStats, sectionMs, profileXmlPath, timelineCsvPath, wavPath, repeat, warmup);
				Console.root().println(formatResultLine(result));
				return result;
			} finally {
				if (profile != null) Hardware.getLocalHardware().clearProfile();
			}
		} finally {
			if (pop != null) pop.destroy();
			scene.destroy();
		}
	}

	/**
	 * Resolves the multi-channel selection from a property string. Accepts either {@code "all"}
	 * (case-insensitive) for every channel index in the scene, or a comma-separated index list.
	 *
	 * @param spec          the property value
	 * @param totalMeasures the total measures (used to construct a probe scene for channel count)
	 * @return an immutable list of channel indices to render
	 */
	public static List<Integer> resolveMultiChannels(String spec, int totalMeasures) {
		if (spec == null || spec.isBlank() || "all".equalsIgnoreCase(spec.trim())) {
			AudioScene<?> probe = AudioSceneOptimizer.createScene();
			try {
				probe.setTotalMeasures(totalMeasures);
				return Collections.unmodifiableList(IntStream.range(0, probe.getChannelCount())
						.boxed().collect(Collectors.toList()));
			} finally {
				probe.destroy();
			}
		}

		List<Integer> values = new ArrayList<>();
		for (String token : spec.split(",")) {
			String trimmed = token.trim();
			if (trimmed.isEmpty()) continue;
			values.add(Integer.parseInt(trimmed));
		}

		if (values.isEmpty()) {
			throw new IllegalArgumentException("Empty multi-channel list: '" + spec + "'");
		}
		return Collections.unmodifiableList(values);
	}

	/**
	 * Parses a comma-separated list of positive integers into an array.
	 *
	 * @param spec comma-separated string
	 * @return parsed integer array
	 * @throws IllegalArgumentException if {@code spec} contains no valid positive integers
	 */
	public static int[] parseDurations(String spec) {
		String[] tokens = spec.split(",");
		List<Integer> values = new ArrayList<>();
		for (String token : tokens) {
			String trimmed = token.trim();
			if (trimmed.isEmpty()) continue;

			int v = Integer.parseInt(trimmed);
			if (v <= 0) throw new IllegalArgumentException("Duration must be positive: " + trimmed);
			values.add(v);
		}

		if (values.isEmpty()) {
			throw new IllegalArgumentException("At least one duration required: '" + spec + "'");
		}

		int[] out = new int[values.size()];
		for (int i = 0; i < out.length; i++) out[i] = values.get(i);
		return out;
	}

	/**
	 * Ensures {@link HealthComputationAdapter#standardDurationFrames} is large enough for the
	 * longest measure count in the sweep. The default standard duration covers 230 seconds; for
	 * longer benchmarks the timeline must be expanded before any {@link WaveOutput} is built.
	 *
	 * @param measures the measure-count durations being swept
	 */
	private static void ensureTimelineCapacity(int[] measures) {
		AudioScene<?> probe = AudioSceneOptimizer.createScene();
		try {
			int maxFrames = 0;
			for (int m : measures) {
				probe.setTotalMeasures(m);
				int f = probe.getContext().getFrameForPosition().applyAsInt(m);
				if (f > maxFrames) maxFrames = f;
			}

			int requiredSeconds = (int) Math.ceil(maxFrames / (double) OutputLine.sampleRate) + 4;
			if (requiredSeconds > HealthComputationAdapter.standardDurationSeconds) {
				HealthComputationAdapter.setStandardDuration(requiredSeconds);
				Console.root().println("Expanded HealthComputationAdapter.standardDuration to "
						+ requiredSeconds + " seconds for benchmark sweep");
			}
		} finally {
			probe.destroy();
		}
	}

	/**
	 * Saves the given profile node to the specified XML path. Catches {@link Throwable} (not
	 * just {@link IOException}) so that {@link OutOfMemoryError} during {@code XMLEncoder}
	 * serialisation — which can happen for very large profile trees — does not abort the
	 * remainder of the benchmark sweep.
	 *
	 * @param profile the profile to save
	 * @param path    destination XML path
	 */
	private static void saveProfile(OperationProfileNode profile, String path) {
		try {
			profile.save(path);
		} catch (Throwable t) {
			Console.root().println("WARN: failed to save profile XML to " + path + ": " + t);
		}
	}

	/**
	 * Writes the per-tick nanosecond array as a two-column CSV ({@code tick_index,tick_ns}) so
	 * the shape of the tick-cost-over-time curve can be plotted. Failures are logged but do not
	 * propagate.
	 *
	 * @param path   destination CSV path
	 * @param tickNs per-tick durations in nanoseconds
	 */
	private static void writeTimeline(String path, long[] tickNs) {
		File f = new File(path);
		File parent = f.getParentFile();
		if (parent != null && !parent.exists()) parent.mkdirs();

		try (PrintWriter w = new PrintWriter(f, "UTF-8")) {
			w.println("tick_index,tick_ns");
			for (int i = 0; i < tickNs.length; i++) {
				w.print(i);
				w.print(',');
				w.println(tickNs[i]);
			}
		} catch (Throwable t) {
			Console.root().println("WARN: failed to write timeline CSV to " + path + ": " + t);
		}
	}

	/**
	 * Formats a benchmark result as a one-line human-readable log entry with split timings.
	 *
	 * @param r the result to format
	 * @return a single-line representation suitable for console output
	 */
	private static String formatResultLine(BenchmarkResult r) {
		TickStats t = r.getTickStats();
		return String.format(
				"[bench] mode=%s ch=%s fl=%d measures=%d audio=%.3fs setup=%dms tick_total=%dms "
						+ "render=%dms section=%dms ratio=%.3f tick(p50=%d p95=%d max=%d "
						+ "first10=%d last10=%d) repeat=%d%s",
				r.mode, r.channels, r.featureLevel, r.measures, r.audioSeconds,
				r.setupMs, t.getTotalMs(), r.renderMs, r.sectionMs, r.getRatio(),
				t.getP50Ms(), t.getP95Ms(), t.getMaxMs(),
				t.getFirstDecileMs(), t.getLastDecileMs(),
				r.repeat, r.warmup ? " (warmup)" : "");
	}

	/**
	 * Writes the collected results to a CSV file, appending a trailing comment line with the
	 * total script wall-clock time. Failures to write are logged but do not propagate.
	 *
	 * @param path     destination CSV path
	 * @param results  the benchmark results to serialize
	 * @param totalMs  total script wall-clock time in milliseconds
	 */
	private static void writeReport(String path, List<BenchmarkResult> results, long totalMs) {
		File f = new File(path);
		File parent = f.getParentFile();
		if (parent != null && !parent.exists()) parent.mkdirs();

		try (PrintWriter w = new PrintWriter(f, "UTF-8")) {
			w.println("mode,measures,channels,feature_level,audio_seconds,frames,"
					+ "setup_ms,tick_total_ms,render_ms,section_ms,"
					+ "tick_count,tick_min_ms,tick_p50_ms,tick_p95_ms,tick_max_ms,"
					+ "tick_first_decile_ms,tick_last_decile_ms,"
					+ "ratio,setup_ratio,tick_ratio,profile_xml,timeline_csv,wav_path,repeat,warmup");
			for (BenchmarkResult r : results) {
				TickStats t = r.getTickStats();
				w.printf("%s,%d,\"%s\",%d,%.6f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.6f,%.6f,%.6f,%s,%s,%s,%d,%s%n",
						r.mode, r.measures, r.channels, r.featureLevel, r.audioSeconds, r.frames,
						r.setupMs, t.getTotalMs(), r.renderMs, r.sectionMs,
						t.getCount(), t.getMinMs(), t.getP50Ms(), t.getP95Ms(), t.getMaxMs(),
						t.getFirstDecileMs(), t.getLastDecileMs(),
						r.getRatio(), r.getSetupRatio(), r.getTickRatio(),
						r.getProfileXmlPath() == null ? "" : r.getProfileXmlPath(),
						r.getTimelineCsvPath() == null ? "" : r.getTimelineCsvPath(),
						r.getWavPath() == null ? "" : r.getWavPath(),
						r.repeat, r.warmup ? "true" : "false");
			}
			w.printf("# total_script_ms=%d%n", totalMs);
		} catch (IOException e) {
			Console.root().println("WARN: failed to write report to " + path + ": " + e.getMessage());
		}
	}

	/**
	 * Prints a tabular summary of all benchmark results to the root console, followed by total
	 * script wall-clock time and the difference between sum-of-section time and total time
	 * (out-of-section overhead).
	 *
	 * @param results the benchmark results to print
	 * @param totalMs total script wall-clock time in milliseconds
	 */
	private static void printSummary(List<BenchmarkResult> results, long totalMs) {
		Console.root().println("=== AudioSceneBenchmark Summary ===");
		Console.root().println(String.format(
				"%-7s %-9s %-7s %-6s %-13s %-10s %-9s %-11s %-10s %-12s %-7s %-9s %-9s",
				"mode", "measures", "ch", "fl", "audio_s", "frames",
				"setup_ms", "tick_total", "render_ms", "section_ms", "ratio",
				"first10ms", "last10ms"));

		for (BenchmarkResult r : results) {
			TickStats t = r.getTickStats();
			Console.root().println(String.format(
					"%-7s %-9d %-7s %-6d %-13.3f %-10d %-9d %-11d %-10d %-12d %-7.3f %-9d %-9d%s",
					r.mode, r.measures, r.channels, r.featureLevel,
					r.audioSeconds, r.frames, r.setupMs, t.getTotalMs(),
					r.renderMs, r.sectionMs, r.getRatio(),
					t.getFirstDecileMs(), t.getLastDecileMs(),
					r.warmup ? " (warmup)" : ""));
		}

		Console.root().println(String.format("Total script wall-clock: %d ms", totalMs));

		long renderTotal = results.stream().mapToLong(BenchmarkResult::getRenderMs).sum();
		long sectionTotal = results.stream().mapToLong(BenchmarkResult::getSectionMs).sum();
		long overhead = totalMs - sectionTotal;
		Console.root().println(String.format(
				"Sum of render_ms: %d ms; sum of section_ms: %d ms; out-of-section overhead: %d ms",
				renderTotal, sectionTotal, overhead));
	}

	/**
	 * Creates the given directory (and any missing parents) if it does not already exist.
	 *
	 * @param path directory path to create
	 */
	private static void ensureDirectory(String path) {
		File d = new File(path);
		if (!d.exists()) d.mkdirs();
	}

	/**
	 * Pauses the JVM after a render repetition so that an external observer (typically
	 * an MCP-driven JMX client) can capture a class histogram of the post-repetition
	 * heap state. While paused, a sentinel file is created under {@code markerDir} and
	 * removed when the pause ends, giving the observer a precise window during which
	 * the JVM is idle and the heap is stable. When {@code pauseMs} is zero or negative
	 * the method returns immediately and no sentinel file is written.
	 *
	 * @param markerDir directory in which to write the sentinel file
	 * @param mode      "multi" or "single"
	 * @param measures  total measure count for the repetition
	 * @param repeat    repetition index
	 * @param pauseMs   number of milliseconds to pause (0 disables the pause)
	 */
	private static void pauseForExternalSnapshot(Path markerDir, String mode,
												 int measures, int repeat, long pauseMs) {
		if (pauseMs <= 0L) return;

		Path marker = markerDir.resolve("rep-" + mode + "-" + measures + "m-" + repeat + "-paused.marker");
		try {
			System.gc();
			Files.writeString(marker, "pid=" + ProcessHandle.current().pid()
					+ " mode=" + mode + " measures=" + measures + " repeat=" + repeat
					+ " timestamp=" + System.currentTimeMillis() + "\n");
			Console.root().println("PAUSE for snapshot: marker=" + marker
					+ " duration=" + pauseMs + "ms");
			Thread.sleep(pauseMs);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		} catch (IOException ioe) {
			Console.root().println("WARN: marker write failed: " + ioe);
			try {
				Thread.sleep(pauseMs);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
			}
		} finally {
			try {
				Files.deleteIfExists(marker);
			} catch (IOException ioe) {
				Console.root().println("WARN: marker delete failed: " + ioe);
			}
			Console.root().println("PAUSE complete: " + mode + " " + measures + "m repeat=" + repeat);
		}
	}

	/** Hidden constructor; this class is a static {@code main} entry point and is not instantiated. */
	private AudioSceneBenchmark() { }
}
