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

package org.almostrealism.ml.audio;

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.cl.CLMemoryProvider;
import org.almostrealism.hardware.metal.MetalMemoryProvider;
import org.almostrealism.ml.tokenization.SentencePieceBPETokenizer;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestDepth;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Measures the device and physical memory of the Stable Audio 3 generator through the stages a
 * client goes through: building the generator, three text clips, compiling a blend encoder for
 * the generator's audio length, and two blends that start from an encoded clip.
 *
 * <p>The generator is compiled for 11 seconds (plus the generator's headroom) and each clip is
 * 4 seconds with the default number of steps. <em>Tracked</em> memory is the sum of the
 * allocations the Metal or OpenCL memory providers account for; <em>physical</em> memory is the
 * {@code Footprint:} total reported by {@code /usr/bin/footprint} for this process, and its
 * {@code IOAccelerator (graphics)} category is the GPU-buffer part of it. A daemon thread samples
 * tracked memory every 200 ms and the footprint every second, keeping the peaks, which are reset at
 * the start of each stage; at the end of each stage the heap is collected and the process left to
 * settle, three times, before the settled values are recorded.</p>
 *
 * <p>The table is logged, not asserted on: it is the measurement that compares layer buffer reuse
 * ({@link CompiledModel#enableLayerBufferReuse}) with one buffer per layer. Run it once as is and
 * once with {@code -DAR_GRAPH_LAYER_BUFFER_REUSE=disabled}, on the same node and settings, to
 * obtain both tables. It needs the released weights and the exported tokenizer (see
 * {@link StableAudio3TestBase}) and {@code AR_HARDWARE_MEMORY_SCALE=7}.</p>
 */
public class StableAudio3MemoryMeasurementTest extends StableAudio3TestBase {
	/** The longest duration the generator is compiled for, before its headroom. */
	private static final double MAX_SECONDS = 11.0;

	/** The duration of each generated clip. */
	private static final double CLIP_SECONDS = 4.0;

	/** The prompt of every clip. */
	private static final String PROMPT = "a warm analog synth pad";

	/** How far a blend moves from its start latent. */
	private static final double BLEND_STRENGTH = 0.7;

	/** Bytes in a megabyte, for the table. */
	private static final double MB = 1024.0 * 1024.0;

	/**
	 * Runs the stages and logs the memory table.
	 *
	 * @throws Exception if a weight directory cannot be read or the sampler is interrupted
	 */
	@Test(timeout = 2400000)
	@TestDepth(10)
	public void measuresMemoryThroughGenerationAndBlending() throws Exception {
		if (skipLongTests) return;

		File exported = firstFile(TOKENIZER_PATHS);
		requireLocalResource("exported T5Gemma tokenizer (tried " + Arrays.toString(TOKENIZER_PATHS) + ")",
				"AR_T5GEMMA_TOKENIZER", exported != null);
		SentencePieceBPETokenizer tokenizer = new SentencePieceBPETokenizer(exported.getPath());

		log("enableLayerBufferReuse=" + CompiledModel.enableLayerBufferReuse);
		List<String> rows = new ArrayList<>();
		Sampler sampler = new Sampler();
		sampler.start();

		try {
			withModel(MAX_SECONDS, (model, autoencoderWeights) -> {
				model.setTokenizer(tokenizer);
				rows.add(sampler.settle("Generator built", 0));

				for (int i = 1; i <= 3; i++) {
					sampler.resetPeaks();
					long start = System.nanoTime();
					model.generateFromText(i, PROMPT, CLIP_SECONDS).evaluate().destroy();
					rows.add(sampler.settle("After text clip " + i, System.nanoTime() - start));
				}

				sampler.resetPeaks();
				Block encoderBlock = SAMEAutoEncoder.small(autoencoderWeights).encoder(1, model.getAudioLength());
				Model encoderModel = new Model(encoderBlock.getInputShape());
				encoderModel.add(encoderBlock);
				CompiledModel encoder = encoderModel.compile(false);
				rows.add(sampler.settle("Blend encoder compiled", 0));

				try {
					Random random = new Random(11);
					for (int i = 1; i <= 2; i++) {
						sampler.resetPeaks();
						long start = System.nanoTime();
						PackedCollection clip = new PackedCollection(encoder.getInputShape()).randnFill(random);
						PackedCollection latent = encoder.forward(clip);
						model.generateFromText(i, PROMPT, CLIP_SECONDS, BLEND_STRENGTH, latent).evaluate().destroy();
						clip.destroy();
						rows.add(sampler.settle("After blend " + i, System.nanoTime() - start));
					}
				} finally {
					encoder.destroy();
				}
			});
		} finally {
			sampler.stop();
		}

		log("| Stage | Seconds | Tracked | Peak tracked | Physical | Peak physical | IOAccelerator (graphics) |");
		rows.forEach(this::log);

		assertEquals("every stage must be recorded", 7, rows.size());
		assertTrue("tracked memory is only measurable on the Metal or OpenCL backend", trackedBytes() > 0);
	}

	/**
	 * Samples tracked and physical memory on a daemon thread, keeping the peaks since the last
	 * reset, and records settled values at the end of a stage.
	 */
	private class Sampler {
		/** The peak tracked memory since the last reset, in bytes. */
		private final AtomicLong peakTracked = new AtomicLong();

		/** The peak physical footprint since the last reset, in bytes. */
		private final AtomicLong peakPhysical = new AtomicLong();

		/** The sampling thread. */
		private Thread thread;

		/** Whether the sampling thread should keep running. */
		private volatile boolean running;

		/** Starts the sampling thread. */
		void start() {
			running = true;
			thread = new Thread(() -> {
				int tick = 0;
				while (running) {
					peakTracked.accumulateAndGet(trackedBytes(), Math::max);
					if (tick++ % 5 == 0) peakPhysical.accumulateAndGet(footprint()[0], Math::max);

					try {
						Thread.sleep(200);
					} catch (InterruptedException e) {
						return;
					}
				}
			}, "memory sampler");
			thread.setDaemon(true);
			thread.start();
		}

		/** Stops the sampling thread. */
		void stop() {
			running = false;
			thread.interrupt();
		}

		/** Resets the peaks at the start of a stage. */
		void resetPeaks() {
			peakTracked.set(trackedBytes());
			peakPhysical.set(footprint()[0]);
		}

		/**
		 * Collects the heap and waits, three times, then returns the table row of the stage.
		 *
		 * @param stage the name of the stage
		 * @param nanos the duration of the stage, or 0 if it is not timed
		 * @return the table row
		 */
		String settle(String stage, long nanos) {
			for (int i = 0; i < 3; i++) {
				System.gc();
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}

			long tracked = trackedBytes();
			long[] physical = footprint();
			String row = String.format("| %s | %.1f | %.0f | %.0f | %.0f | %.0f | %.0f |", stage, nanos / 1e9,
					tracked / MB, Math.max(peakTracked.get(), tracked) / MB,
					physical[0] / MB, Math.max(peakPhysical.get(), physical[0]) / MB, physical[1] / MB);
			log(row);
			return row;
		}
	}

	/**
	 * Returns the memory the Metal and OpenCL memory providers account as allocated.
	 *
	 * @return the tracked bytes, or 0 on a backend that does not account for its allocations
	 */
	private static long trackedBytes() {
		long total = 0;
		for (MemoryProvider<? extends Memory> p : Hardware.getLocalHardware().getDataContext().getMemoryProviders()) {
			if (p instanceof MetalMemoryProvider) total += ((MetalMemoryProvider) p).getAllocatedMemory();
			if (p instanceof CLMemoryProvider) total += ((CLMemoryProvider) p).getAllocatedMemory();
		}
		return total;
	}

	/**
	 * Returns the physical footprint of this process and its {@code IOAccelerator (graphics)}
	 * category, as reported by {@code /usr/bin/footprint}.
	 *
	 * @return the footprint and the graphics category in bytes, 0 where unavailable
	 */
	private static long[] footprint() {
		long[] result = new long[2];
		Pattern total = Pattern.compile("Footprint: (\\d+) B");
		Pattern graphics = Pattern.compile("^\\s*(\\d+) B.*IOAccelerator \\(graphics\\)");

		try {
			Process process = new ProcessBuilder("/usr/bin/footprint", "-p",
					String.valueOf(ProcessHandle.current().pid()), "-f", "bytes")
					.redirectErrorStream(true).start();
			try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
				String line;
				while ((line = in.readLine()) != null) {
					Matcher t = total.matcher(line);
					if (result[0] == 0 && t.find()) result[0] = Long.parseLong(t.group(1));
					Matcher g = graphics.matcher(line);
					if (g.find()) result[1] = Long.parseLong(g.group(1));
				}
			}
			process.waitFor();
		} catch (IOException e) {
			return result;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		return result;
	}
}
