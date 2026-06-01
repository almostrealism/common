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

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.cl.CLMemoryProvider;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.hardware.metal.MetalMemoryProvider;
import org.almostrealism.heredity.Genome;
import org.almostrealism.music.pattern.PatternLayerManager;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.health.MultiChannelAudioOutput;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Continuously renders an {@link AudioScene} buffer-by-buffer for an unbounded
 * duration, proving the scene can be generated in real time indefinitely. A finite
 * arrangement is rendered and looped (the per-buffer cost is independent of how
 * long it has been running — only the current playback window is rendered each
 * buffer), so a 40-hour run exercises exactly the same per-buffer work as the first
 * second.
 *
 * <p>For each buffer the wall-clock render time is compared to the audio duration of
 * the buffer ({@code bufferSize / sampleRate}); the ratio is the realtime factor
 * (&lt; 1.0 means faster than realtime). A rolling summary is appended to
 * {@code results/rt-monitor.log} so a long run can be watched. The first arrangement
 * is written to {@code results/rt-sample.wav} as proof of real audio.</p>
 *
 * <p>Configuration via system properties: {@code AR_RT_CHANNELS} (comma list, default
 * {@code 0}), {@code AR_RT_MEASURES} (arrangement length, default {@code 64}),
 * {@code AR_RT_BUFFER} (frames, default {@code 8192}), {@code AR_RT_HOURS} (target
 * audio hours, default {@code 40}), {@code AR_RT_LOG_EVERY} (buffers between log
 * lines, default {@code 64}).</p>
 */
public final class RealtimeContinuousRenderer {

	/** Utility class; not instantiable. */
	private RealtimeContinuousRenderer() { }

	/**
	 * Runs the continuous render loop until the target number of audio hours has
	 * been generated, logging the realtime factor throughout.
	 *
	 * @param args ignored; configuration is via system properties
	 * @throws Exception if scene construction or rendering fails
	 */
	public static void main(String[] args) throws Exception {
		List<Integer> channels = parseChannels(System.getProperty("AR_RT_CHANNELS", "0"));
		int measures = Integer.getInteger("AR_RT_MEASURES", 64);
		int bufferSize = Integer.getInteger("AR_RT_BUFFER", 8192);
		double targetHours = Double.parseDouble(System.getProperty("AR_RT_HOURS", "40"));
		long logEvery = Long.getLong("AR_RT_LOG_EVERY", 64L);
		// Switch to a freshly generated arrangement every this many arrangement loops
		// (0 = never switch). A switch assigns a new random genome in place and
		// invalidates the note caches; the compiled graph is reused (no recompile).
		int switchLoops = Integer.getInteger("AR_RT_SWITCH_LOOPS", 0);
		// When true, the master mix of every completed arrangement loop is written to
		// results/rt-live.wav (overwritten each loop) so the ongoing generation can be
		// listened to while it runs. Otherwise only the first loop is written.
		boolean liveOutput = Boolean.getBoolean("AR_RT_LIVE_OUTPUT");

		File resultsDir = new File("results");
		if (!resultsDir.mkdirs() && !resultsDir.isDirectory()) {
			throw new IOException("Unable to create results directory: " + resultsDir.getAbsolutePath());
		}
		// Route monitor output through the framework's Console rather than an ad-hoc
		// PrintWriter: a child console mirrors every line to results/rt-monitor.log (via
		// the file-output listener) and to the root console, with framework timestamps.
		Console monitor = Console.root().child();
		monitor.addListener(OutputFeatures.fileOutput("results/rt-monitor.log"));
		ConsoleFeatures log = monitor.features(RealtimeContinuousRenderer.class);

		AudioScene<?> scene = AudioSceneOptimizer.createScene();
		scene.setTotalMeasures(measures);
		int frames = scene.getContext().getFrameForPosition().applyAsInt(measures);
		int buffersPerLoop = (frames + bufferSize - 1) / bufferSize;
		double bufferAudioSec = (double) bufferSize / OutputLine.sampleRate;

		Genome<PackedCollection> genome = scene.getGenome().random();
		AudioScenePopulation pop = new AudioScenePopulation(scene, List.of(genome));
		String outFile = liveOutput ? "results/rt-live.wav" : "results/rt-sample.wav";
		WaveOutput out = new WaveOutput(() -> new File(outFile), 24, true);
		pop.init(genome, new MultiChannelAudioOutput(out), channels, bufferSize);

		TemporalCellular cells = pop.enableGenome(0);
		Runnable setup = cells.setup().get();
		Runnable tick = cells.tick().get();

		log.log(String.format("channels=%s measures=%d buffer=%d arrangement=%.1fs buffersPerLoop=%d targetHours=%.1f",
				channels, measures, bufferSize, frames / (double) OutputLine.sampleRate, buffersPerLoop, targetHours));

		long setupStart = System.nanoTime();
		setup.run();
		log.log(String.format("setup_ms=%d", (System.nanoTime() - setupStart) / 1_000_000L));

		long targetBuffers = (long) (targetHours * 3600.0 / bufferAudioSec);
		long[] count = {0};
		long[] loopIdx = {0};
		double[] audioSec = {0};
		double[] rollSum = {0};
		long[] rollN = {0};
		double[] peak = {0};
		double[] worstRoll = {0};
		long wallStart = System.nanoTime();

		// When AR_RT_HEAP is enabled, each buffer's render runs inside a Heap stage so
		// per-buffer device allocations (note-evaluation intermediates) are freed every
		// buffer. With AR_PATTERN_CACHE_PERSIST a looped arrangement evaluates notes
		// only on the first pass, so after that there are no intermediates to free and
		// the Heap is unnecessary (it is disabled by default because the per-buffer
		// stage push/pop interferes with kernel completion signaling).
		boolean useHeap = Boolean.getBoolean("AR_RT_HEAP");
		Runnable runTick = useHeap ? () -> Heap.stage(tick) : tick;
		Runnable writeOut = useHeap
				? () -> Heap.stage(() -> out.write().get().run())
				: () -> out.write().get().run();

		// Pace submission to playback time. A real-time renderer must NOT render
		// flat-out: doing so submits GPU work many times faster than realtime, and
		// after a few minutes the Metal command pipeline saturates — completion
		// handlers lag and the render kernel intermittently stalls for seconds.
		// AR_RT_PACE_RATE is the submission rate as a multiple of realtime (1.0 =
		// realtime; 4.0 = 4x realtime, still bounded; 0 disables pacing). After each
		// buffer the loop sleeps so the buffer occupies bufferAudioSec/paceRate of
		// wall time, keeping the GPU queue shallow.
		double paceRate = Double.parseDouble(System.getProperty("AR_RT_PACE_RATE", "1.0"));
		long pacedBufferNanos = paceRate > 0 ? (long) (bufferAudioSec / paceRate * 1e9) : 0;

		Runnable loopBody = () -> {
			while (count[0] < targetBuffers) {
				for (int b = 0; b < buffersPerLoop && count[0] < targetBuffers; b++) {
					long t0 = System.nanoTime();
					runTick.run();
					long renderNanos = System.nanoTime() - t0;
					double ratio = renderNanos / 1e9 / bufferAudioSec;

					sleepNanos(pacedBufferNanos - renderNanos);

					rollSum[0] += ratio;
					rollN[0]++;
					peak[0] = Math.max(peak[0], ratio);
					audioSec[0] += bufferAudioSec;
					count[0]++;

					if (count[0] % logEvery == 0) {
						double roll = rollSum[0] / rollN[0];
						worstRoll[0] = Math.max(worstRoll[0], roll);
						rollSum[0] = 0;
						rollN[0] = 0;
						double wall = (System.nanoTime() - wallStart) / 1e9;
						log.log(String.format("audio=%.0fs(%.3fh) wall=%.0fs buffers=%d loop=%d ratio=%.3f peak=%.3f worstWindow=%.3f mem=%dMB %s",
								audioSec[0], audioSec[0] / 3600.0, wall, count[0], loopIdx[0], roll, peak[0],
								worstRoll[0], allocatedNativeBytes() / (1024 * 1024),
								roll < 1.0 ? "realtime" : "slower-than-realtime"));
					}
				}

				if (liveOutput || loopIdx[0] == 0) {
					writeOut.run();
				}
				out.reset();
				loopIdx[0]++;

				if (switchLoops > 0 && loopIdx[0] % switchLoops == 0) {
					// Switch to a new arrangement: assign a fresh random genome in
					// place (the compiled graph is reused — no recompile) and invalidate
					// the note caches so the new arrangement renders from scratch.
					pop.disableGenome();
					pop.setGenomes(List.of(scene.getGenome().random()));
					pop.enableGenome(0);
					PatternLayerManager.invalidateCaches();
					log.log(String.format("switch_arrangement loop=%d buffers=%d", loopIdx[0], count[0]));
				} else {
					cells.reset();
				}
			}
		};

		Heap heap = useHeap ? new Heap(16_000_000, 256_000_000) : null;
		try {
			if (useHeap) {
				heap.use(loopBody);
			} else {
				loopBody.run();
			}
			log.log(String.format("generated %.2f hours of audio over %d loops; peak=%.3f worstWindow=%.3f",
					audioSec[0] / 3600.0, loopIdx[0], peak[0], worstRoll[0]));
		} finally {
			pop.destroy();
			scene.destroy();
			if (heap != null) heap.destroy();
		}
	}

	/**
	 * Returns the total native (GPU/accelerator) memory currently allocated across
	 * all hardware memory providers, in bytes. Used to monitor for leaks during a
	 * long continuous render.
	 *
	 * @return total allocated native memory in bytes
	 */
	private static long allocatedNativeBytes() {
		long total = 0;
		for (MemoryProvider<? extends Memory> provider :
				Hardware.getLocalHardware().getDataContext().getMemoryProviders()) {
			if (provider instanceof MetalMemoryProvider) {
				total += ((MetalMemoryProvider) provider).getAllocatedMemory();
			} else if (provider instanceof CLMemoryProvider) {
				total += ((CLMemoryProvider) provider).getAllocatedMemory();
			}
		}
		return total;
	}

	/**
	 * Sleeps for the given number of nanoseconds, used to pace buffer rendering to
	 * playback time. Does nothing if the duration is not positive (the buffer already
	 * took at least its paced budget to render).
	 *
	 * @param nanos the number of nanoseconds to sleep
	 */
	private static void sleepNanos(long nanos) {
		if (nanos <= 0) return;
		try {
			Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Parses a comma-separated channel list. */
	private static List<Integer> parseChannels(String spec) {
		List<Integer> channels = new ArrayList<>();
		for (String s : spec.split(",")) {
			if (!s.isBlank()) channels.add(Integer.parseInt(s.trim()));
		}
		return channels;
	}
}
