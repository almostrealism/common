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

package org.almostrealism.audio.benchmark;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.computations.MultiOrderFilter;
import org.almostrealism.util.TestDepth;
import org.junit.Test;

import java.util.Random;

/**
 * Envelope-focused and Java-side gather benchmarks for the pattern-rendering floor suite.
 *
 * <p>This class covers three envelope kernel benchmarks and the Java-side gather cost
 * measurement:</p>
 * <ul>
 *   <li><b>ENVELOPE-1</b> ({@link #benchmarkVolumeEnvelopeBatched}) — batched volume
 *       envelope (elementwise multiply) in isolation, per-row independent.</li>
 *   <li><b>ENVELOPE-2</b> ({@link #benchmarkFilterEnvelopeBatched}) — batched filter
 *       envelope (lowpass with per-row, per-sample cutoff) in isolation.</li>
 *   <li><b>ENVELOPE-3</b> ({@link #benchmarkCombinedEnvelopeChainBatched}) — filter
 *       envelope followed by volume envelope, confirming the two compose cleanly.</li>
 *   <li><b>ADDITION 7</b> ({@link #benchmarkJavaSideGatherCostB1}) — measures the
 *       Java-side cost of building per-tick batched input tensors via per-note
 *       {@code arraycopy} (Path B1 from the Phase 3 design document).</li>
 * </ul>
 *
 * <p>All shared benchmark infrastructure (constants, timing helpers, ADSR builders)
 * is inherited from {@link PatternRenderingFloorBenchmarkBase}. Core kernel benchmarks
 * (Additions 1–6) are in {@link PatternRenderingFloorBenchmark}.</p>
 *
 * @see PatternRenderingFloorBenchmarkBase
 * @see PatternRenderingFloorBenchmark
 */
public class PatternRenderingFloorBenchmarkAdditional extends PatternRenderingFloorBenchmarkBase {

	/** Pool size for the synthetic source bank used by {@link #benchmarkJavaSideGatherCostB1}. */
	private static final int B1_SOURCE_POOL_SIZE = 16;

	/** Minimum source-buffer length in samples; ensures any random offset leaves at least
	 *  {@link #NOTE_SIZE} samples to gather. */
	private static final int B1_SOURCE_MIN_SAMPLES = 2 * NOTE_SIZE;

	/** Range of additional samples added on top of {@link #B1_SOURCE_MIN_SAMPLES}; together
	 *  these size source buffers to 2048–8192 samples (the typical resampled-buffer range
	 *  for the production audioCache). */
	private static final int B1_SOURCE_EXTRA_RANGE = 6 * NOTE_SIZE;

	/**
	 * Per-note metadata that drives the B1 gather: source index into the pool, sample offset
	 * within that source, pitch ratio, volume + filter ADSR scalars, automation level, and
	 * tick-relative start offset. Generated once per density level outside the timed loop.
	 */
	private static final class B1NoteMetadata {
		/** Source buffer index for each note (into the synthetic pool). */
		final int[] sourceIdx;
		/** Sample offset within the selected source buffer for each note. */
		final int[] sourceOffset;
		/** Pitch ratio (resample factor) for each note. */
		final double[] pitchRatio;
		/** Volume envelope attack fraction for each note. */
		final double[] volAttack;
		/** Volume envelope decay fraction for each note. */
		final double[] volDecay;
		/** Volume envelope sustain level for each note. */
		final double[] volSustain;
		/** Volume envelope release fraction for each note. */
		final double[] volRelease;
		/** Filter envelope attack fraction for each note. */
		final double[] filterAttack;
		/** Filter envelope decay fraction for each note. */
		final double[] filterDecay;
		/** Filter envelope sustain level for each note. */
		final double[] filterSustain;
		/** Filter envelope release fraction for each note. */
		final double[] filterRelease;
		/** Automation level (0–1) for each note. */
		final double[] automationLevel;
		/** Tick-relative start offset in samples for each note. */
		final double[] tickStartOffset;

		/**
		 * Allocates all per-note arrays for the given total note count.
		 */
		B1NoteMetadata(int totalNotes) {
			sourceIdx = new int[totalNotes];
			sourceOffset = new int[totalNotes];
			pitchRatio = new double[totalNotes];
			volAttack = new double[totalNotes];
			volDecay = new double[totalNotes];
			volSustain = new double[totalNotes];
			volRelease = new double[totalNotes];
			filterAttack = new double[totalNotes];
			filterDecay = new double[totalNotes];
			filterSustain = new double[totalNotes];
			filterRelease = new double[totalNotes];
			automationLevel = new double[totalNotes];
			tickStartOffset = new double[totalNotes];
		}
	}

	/**
	 * Holds a compiled E3-style batched chain together with the pre-allocated audio buffer
	 * the chain captures by reference. The chain is reused across timed iterations; each
	 * iteration calls {@code audioInput.setMem(...)} to replace the audio contents before
	 * invoking the chain.
	 */
	private static final class B1Chain {
		/** The pre-allocated audio input buffer captured by reference by the chain. */
		final PackedCollection audioInput;
		/** The compiled chain that reads from {@link #audioInput} on each evaluate call. */
		final Evaluable<PackedCollection> chain;

		/**
		 * Constructs a B1Chain wrapping the given audio input buffer and compiled chain.
		 */
		B1Chain(PackedCollection audioInput, Evaluable<PackedCollection> chain) {
			this.audioInput = audioInput;
			this.chain = chain;
		}
	}

	/**
	 * E1: isolated batched volume envelope kernel — per-row {@code [N, NOTE_SIZE]} audio
	 * multiplied elementwise by per-row {@code [N, NOTE_SIZE]} gain envelopes (each row's
	 * envelope shape independent of every other row's). One {@code evaluate()} per density.
	 *
	 * <p>Compared against a sequential per-note baseline where each note's audio×envelope
	 * runs in its own {@code evaluate()}. The difference between sequential and batched is
	 * the JNI-amortization headroom available for the volume envelope step.</p>
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void benchmarkVolumeEnvelopeBatched() throws Exception {
		setupResultsListener();

		log("==========================================================");
		log("  ENVELOPE-1 — Volume envelope alone, batched");
		log("  Input: [N, NOTE_SIZE] audio");
		log("  Envelope: [N, NOTE_SIZE] per-row gain (varied shape per row)");
		log("  Operation: elementwise multiply");
		log("==========================================================");
		log("");

		PackedCollection seqAudio = buildRandomSource(NOTE_SIZE);
		PackedCollection seqEnvelope = buildAdsrEnvelope();

		log("Compiling sequential per-note volume envelope kernel...");
		Evaluable<PackedCollection> seqChain = cp(seqAudio).multiply(cp(seqEnvelope)).get();
		warmupSequential("sequential volume envelope (audio × envelope)", seqChain);

		log("--- Sequential per-note baseline (for comparison) ---");
		log("");
		double[] seqPerNoteMs = new double[NOTES_PER_MEASURE_VALUES.length];
		for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {
			int npm = NOTES_PER_MEASURE_VALUES[d];
			int totalNotes = npm * MEASURES_PER_TICK;
			long[] timesNs = runTimedIterations(seqChain, totalNotes);
			seqPerNoteMs[d] = computeTimingStats(timesNs)[0] / totalNotes;
			reportStats(npm, totalNotes, timesNs);
		}

		log("--- Batched volume envelope: 1 evaluate() per density ---");
		log("");
		for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {
			int npm = NOTES_PER_MEASURE_VALUES[d];
			int totalNotes = npm * MEASURES_PER_TICK;
			int totalSamples = totalNotes * NOTE_SIZE;

			log("Compiling batched volume envelope for " + totalNotes + " notes...");
			long compileStart = System.currentTimeMillis();
			PackedCollection perRowAudio = buildPerRowAudio(totalNotes);
			PackedCollection perRowEnvelopes = buildPerRowVolumeEnvelopes(totalNotes);

			CollectionProducer flatAudio = cp(perRowAudio).reshape(shape(totalSamples));
			CollectionProducer flatEnv = cp(perRowEnvelopes).reshape(shape(totalSamples));
			Evaluable<PackedCollection> batched = flatAudio.multiply(flatEnv).get();
			long compileMs = System.currentTimeMillis() - compileStart;
			log("Compilation: " + compileMs + " ms");

			log("Warming up batched volume envelope...");
			for (int w = 0; w < WARMUP_RUNS; w++) batched.evaluate();
			log("Warmup complete.");

			long[] batchedTimesNs = runTimedIterations(batched, 1);
			reportBatchedStats(npm, totalNotes, batchedTimesNs, seqPerNoteMs[d]);
		}

		log("Volume envelope batching interpretation:");
		log("- Per-row independence holds (each row's envelope is content-independent).");
		log("- Volume envelope is the cheapest of the per-note envelopes — pure elementwise.");
		log("- Compare amortized per-note batched cost to sequential per-note cost for headroom.");
		log("==========================================================");
		log("Results appended to: " + RESULTS_PATH);
	}

	/**
	 * E2: isolated batched filter envelope kernel — per-row {@code [N, NOTE_SIZE]} audio,
	 * lowpass-filtered with per-row {@code [N, NOTE_SIZE]} cutoff envelope. Each row gets a
	 * different cutoff value per sample. The filter primitive is {@link MultiOrderFilter}
	 * via {@code lowPass(...)}, the same primitive production uses in
	 * {@code MultiOrderFilterEnvelopeProcessor}.
	 *
	 * <p>To preserve per-row independence under FIR convolution, each row is padded by
	 * {@code FILTER_ORDER/2} zero samples on each side. If this benchmark cannot be
	 * compiled — i.e. the existing {@code lowPass(input, cutoff, ...)} primitive does not
	 * handle a 2D batched cutoff input correctly — that IS the finding for Part 2.</p>
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void benchmarkFilterEnvelopeBatched() throws Exception {
		setupResultsListener();

		log("==========================================================");
		log("  ENVELOPE-2 — Filter envelope alone, batched");
		log("  Input: [N, NOTE_SIZE] audio");
		log("  Cutoff: [N, NOTE_SIZE] per-row, per-sample (varied per row)");
		log("  Filter: MultiOrderFilter low-pass, order=" + FILTER_ORDER);
		log("==========================================================");
		log("");

		int padHalf = FILTER_ORDER / 2;
		int paddedNoteSize = NOTE_SIZE + 2 * padHalf;

		PackedCollection seqAudio = buildRandomSource(NOTE_SIZE);
		PackedCollection seqCutoff = buildAdsrCutoff(NOTE_SIZE);

		log("Compiling sequential per-note filter envelope kernel (lowPass with per-sample cutoff)...");
		Evaluable<PackedCollection> seqChain;
		try {
			MultiOrderFilter seqFilter = lowPass(traverseEach(cp(seqAudio)), cp(seqCutoff),
					SAMPLE_RATE, FILTER_ORDER);
			seqChain = c(seqFilter).reshape(shape(NOTE_SIZE)).get();
		} catch (Exception ex) {
			log("Compilation failed for sequential per-note lowPass with per-sample cutoff: "
					+ ex.getClass().getSimpleName() + ": " + ex.getMessage());
			log("This is a finding — the lowPass primitive may not accept Producer-valued");
			log("per-sample cutoff in this configuration. Skipping E2.");
			log("==========================================================");
			return;
		}

		warmupSequential("sequential filter envelope (lowPass, per-sample cutoff)", seqChain);

		log("--- Sequential per-note baseline (for comparison) ---");
		log("");
		double[] seqPerNoteMs = new double[NOTES_PER_MEASURE_VALUES.length];
		for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {
			int npm = NOTES_PER_MEASURE_VALUES[d];
			int totalNotes = npm * MEASURES_PER_TICK;
			long[] timesNs = runTimedIterations(seqChain, totalNotes);
			seqPerNoteMs[d] = computeTimingStats(timesNs)[0] / totalNotes;
			reportStats(npm, totalNotes, timesNs);
		}

		log("--- Batched filter envelope: 1 evaluate() per density ---");
		log("Pad layout: each row = " + padHalf + " zero samples + " + NOTE_SIZE
				+ " + " + padHalf + " = " + paddedNoteSize + " elements");
		log("");

		for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {
			int npm = NOTES_PER_MEASURE_VALUES[d];
			int totalNotes = npm * MEASURES_PER_TICK;

			log("Compiling batched filter envelope for " + totalNotes + " notes...");
			long compileStart;
			Evaluable<PackedCollection> batched;
			try {
				compileStart = System.currentTimeMillis();
				PackedCollection perRowAudio = buildPerRowAudio(totalNotes);
				PackedCollection perRowCutoff = buildPerRowFilterCutoffs(totalNotes, 0, NOTE_SIZE);

				int paddedTotal = totalNotes * paddedNoteSize;

				CollectionProducer paddedAudio2D = pad(cp(perRowAudio), 0, padHalf);
				CollectionProducer paddedCutoff2D = pad(cp(perRowCutoff), 0, padHalf);

				CollectionProducer flatAudio = paddedAudio2D.reshape(shape(paddedTotal));
				CollectionProducer flatCutoff = paddedCutoff2D.reshape(shape(paddedTotal));

				MultiOrderFilter filter = lowPass(traverseEach(flatAudio), flatCutoff,
						SAMPLE_RATE, FILTER_ORDER);
				batched = c(filter).get();
			} catch (Exception ex) {
				log("Compilation failed for batched filter envelope at " + totalNotes + " notes: "
						+ ex.getClass().getSimpleName() + ": " + ex.getMessage());
				log("This is a finding — names what primitive is missing for batched filter envelope.");
				log("");
				continue;
			}
			long compileMs = System.currentTimeMillis() - compileStart;
			log("Compilation: " + compileMs + " ms");

			log("Warming up batched filter envelope...");
			for (int w = 0; w < WARMUP_RUNS; w++) batched.evaluate();
			log("Warmup complete.");

			long[] batchedTimesNs = runTimedIterations(batched, 1);
			reportBatchedStats(npm, totalNotes, batchedTimesNs, seqPerNoteMs[d]);
		}

		log("Filter envelope batching interpretation:");
		log("- E2 batched form requires per-row cutoff [N, NOTE_SIZE] flattened to [N*paddedNote].");
		log("- The lowPass primitive accepts a Producer cutoff signal; per-sample variation is");
		log("  driven by the cutoff tensor itself. Per-row independence is preserved by padding.");
		log("- If batched amortizes well below sequential, the per-note JNI cost dominates the");
		log("  filter envelope path just like it does for the resample/volume kernels.");
		log("==========================================================");
		log("Results appended to: " + RESULTS_PATH);
	}

	/**
	 * E3: combined batched envelope chain — per-row {@code [N, NOTE_SIZE]} audio →
	 * filter envelope (lowpass with per-row per-sample cutoff) → volume envelope multiply.
	 * Confirms that the two envelopes compose cleanly under batching.
	 *
	 * <p>Production stack per melodic note wraps the note audio with the filter envelope
	 * first, then the volume envelope on top (filter → volume order). This benchmark
	 * mirrors that order.</p>
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void benchmarkCombinedEnvelopeChainBatched() throws Exception {
		setupResultsListener();

		log("==========================================================");
		log("  ENVELOPE-3 — Combined filter + volume envelope chain, batched");
		log("  Chain: audio -> lowPass(per-row cutoff) -> × volume_envelope");
		log("==========================================================");
		log("");

		int padHalf = FILTER_ORDER / 2;
		int paddedNoteSize = NOTE_SIZE + 2 * padHalf;

		PackedCollection seqAudio = buildRandomSource(NOTE_SIZE);
		PackedCollection seqEnvelope = buildAdsrEnvelope();
		PackedCollection seqCutoff = buildAdsrCutoff(NOTE_SIZE);

		log("Compiling sequential per-note combined chain (lowPass -> × envelope)...");
		Evaluable<PackedCollection> seqChain;
		try {
			MultiOrderFilter seqFilter = lowPass(traverseEach(cp(seqAudio)), cp(seqCutoff),
					SAMPLE_RATE, FILTER_ORDER);
			seqChain = c(seqFilter).reshape(shape(NOTE_SIZE)).multiply(cp(seqEnvelope)).get();
		} catch (Exception ex) {
			log("Compilation failed for sequential combined chain: "
					+ ex.getClass().getSimpleName() + ": " + ex.getMessage());
			log("Skipping E3.");
			log("==========================================================");
			return;
		}

		warmupSequential("sequential combined envelope chain", seqChain);

		log("--- Sequential per-note baseline (for comparison) ---");
		log("");
		double[] seqPerNoteMs = new double[NOTES_PER_MEASURE_VALUES.length];
		for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {
			int npm = NOTES_PER_MEASURE_VALUES[d];
			int totalNotes = npm * MEASURES_PER_TICK;
			long[] timesNs = runTimedIterations(seqChain, totalNotes);
			seqPerNoteMs[d] = computeTimingStats(timesNs)[0] / totalNotes;
			reportStats(npm, totalNotes, timesNs);
		}

		log("--- Batched combined envelope chain: 1 evaluate() per density ---");
		log("");

		for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {
			int npm = NOTES_PER_MEASURE_VALUES[d];
			int totalNotes = npm * MEASURES_PER_TICK;

			log("Compiling batched combined envelope chain for " + totalNotes + " notes...");
			long compileStart;
			Evaluable<PackedCollection> batched;
			try {
				compileStart = System.currentTimeMillis();
				PackedCollection perRowAudio = buildPerRowAudio(totalNotes);
				PackedCollection perRowEnvelopes = buildPerRowVolumeEnvelopes(totalNotes);
				PackedCollection perRowCutoff = buildPerRowFilterCutoffs(totalNotes, 0, NOTE_SIZE);

				int totalSamples = totalNotes * NOTE_SIZE;
				int paddedTotal = totalNotes * paddedNoteSize;

				CollectionProducer paddedAudio2D = pad(cp(perRowAudio), 0, padHalf);
				CollectionProducer paddedCutoff2D = pad(cp(perRowCutoff), 0, padHalf);
				CollectionProducer flatPaddedAudio = paddedAudio2D.reshape(shape(paddedTotal));
				CollectionProducer flatPaddedCutoff = paddedCutoff2D.reshape(shape(paddedTotal));
				MultiOrderFilter filter = lowPass(traverseEach(flatPaddedAudio), flatPaddedCutoff,
						SAMPLE_RATE, FILTER_ORDER);
				CollectionProducer filtered2D = c(filter).reshape(shape(totalNotes, paddedNoteSize));

				CollectionProducer trimmed = subset(shape(totalNotes, NOTE_SIZE), filtered2D, 0, padHalf);

				CollectionProducer flatTrimmed = trimmed.reshape(shape(totalSamples));
				CollectionProducer flatEnv = cp(perRowEnvelopes).reshape(shape(totalSamples));
				batched = flatTrimmed.multiply(flatEnv).get();
			} catch (Exception ex) {
				log("Compilation failed for batched combined chain at " + totalNotes + " notes: "
						+ ex.getClass().getSimpleName() + ": " + ex.getMessage());
				log("");
				continue;
			}
			long compileMs = System.currentTimeMillis() - compileStart;
			log("Compilation: " + compileMs + " ms");

			log("Warming up batched combined chain...");
			for (int w = 0; w < WARMUP_RUNS; w++) batched.evaluate();
			log("Warmup complete.");

			long[] batchedTimesNs = runTimedIterations(batched, 1);
			reportBatchedStats(npm, totalNotes, batchedTimesNs, seqPerNoteMs[d]);
		}

		log("Combined envelope chain interpretation:");
		log("- E3 confirms the two envelopes compose under batching.");
		log("- If E3 batched cost ≈ E1 + E2 batched cost, the kernels add linearly.");
		log("- If E3 batched cost < E1 + E2, the framework is fusing envelope + filter.");
		log("==========================================================");
		log("Results appended to: " + RESULTS_PATH);
	}

	/**
	 * Builds a deterministic per-row audio tensor of shape {@code [batchSize, NOTE_SIZE]}.
	 * Each row gets a different sinusoidal content driven by row index.
	 */
	private PackedCollection buildPerRowAudio(int batchSize) {
		double[] data = new double[batchSize * NOTE_SIZE];
		for (int n = 0; n < batchSize; n++) {
			double freq = 220.0 + (n % 64) * 30.0;
			for (int i = 0; i < NOTE_SIZE; i++) {
				data[n * NOTE_SIZE + i] = Math.sin(2.0 * Math.PI * freq * i / SAMPLE_RATE);
			}
		}
		PackedCollection out = new PackedCollection(shape(batchSize, NOTE_SIZE));
		out.setMem(data);
		return out;
	}

	/**
	 * Builds a per-row volume envelope tensor of shape {@code [batchSize, NOTE_SIZE]}.
	 * Each row carries a distinct ADSR shape with attack/decay/release fractions and
	 * sustain level varying deterministically with row index.
	 */
	private PackedCollection buildPerRowVolumeEnvelopes(int batchSize) {
		double[] data = new double[batchSize * NOTE_SIZE];
		for (int n = 0; n < batchSize; n++) {
			double sustainLevel = 0.4 + (n % 16) * (0.5 / 16.0);
			double attackFrac = 0.02 + (n % 8) * 0.01;
			double decayFrac = 0.05 + (n % 8) * 0.01;
			double releaseFrac = 0.10 + (n % 8) * 0.02;
			fillAdsrShape(data, n * NOTE_SIZE, NOTE_SIZE,
					0.0, 1.0, sustainLevel, 0.0,
					attackFrac, decayFrac, releaseFrac);
		}
		PackedCollection out = new PackedCollection(shape(batchSize, NOTE_SIZE));
		out.setMem(data);
		return out;
	}

	/**
	 * Builds a single-row ADSR-shaped cutoff envelope of shape {@code [size]} for use as
	 * the sequential per-note filter cutoff. Cutoff varies from 200 Hz at the start up to
	 * a peak of 8000 Hz then back down to 400 Hz.
	 */
	private PackedCollection buildAdsrCutoff(int size) {
		double[] data = new double[size];
		fillAdsrShape(data, 0, size, 200.0, 8000.0, 1500.0, 400.0, 0.05, 0.10, 0.15);
		PackedCollection out = new PackedCollection(size);
		out.setMem(data);
		return out;
	}

	/**
	 * Builds a per-row filter cutoff envelope tensor of shape
	 * {@code [batchSize, paddedNoteSize]}. Each row's cutoff envelope has a different peak
	 * frequency and sustain level, exercising per-row independence. The ADSR shape occupies
	 * the central {@link #NOTE_SIZE} samples; the {@code padHalf} samples on each side
	 * stay zero. Call with {@code padHalf=0, paddedNoteSize=NOTE_SIZE} to produce the
	 * unpadded variant used by E2/E3.
	 */
	private PackedCollection buildPerRowFilterCutoffs(int batchSize, int padHalf, int paddedNoteSize) {
		double[] data = new double[batchSize * paddedNoteSize];
		for (int n = 0; n < batchSize; n++) {
			double peak = 4000.0 + (n % 16) * 600.0;
			double sustain = 800.0 + (n % 8) * 200.0;
			double base = 150.0 + (n % 4) * 50.0;
			double attackFrac = 0.03 + (n % 8) * 0.005;
			double decayFrac = 0.08 + (n % 8) * 0.005;
			double releaseFrac = 0.12 + (n % 8) * 0.005;
			fillAdsrShape(data, n * paddedNoteSize + padHalf, NOTE_SIZE,
					base, peak, sustain, base,
					attackFrac, decayFrac, releaseFrac);
		}
		PackedCollection out = new PackedCollection(shape(batchSize, paddedNoteSize));
		out.setMem(data);
		return out;
	}

	/**
	 * Addition 7: measures the Java-side cost of preparing the per-tick batched input
	 * tensors (Path B1 from the Phase 3 design document — per-note {@link System#arraycopy}
	 * from a pool of cached source buffers into the {@code [N, NOTE_SIZE + FILTER_ORDER]}
	 * audio tensor, plus building eleven {@code [N]} scalar tensors).
	 *
	 * <h2>Measurement 1 — B1 gather alone</h2>
	 * <p>Timed inner loop: gather each note's audio via {@code arraycopy}, push to the
	 * pre-allocated audio {@link PackedCollection} via {@code setMem}, and allocate and
	 * populate eleven {@code [N]} scalar tensors (pitch, vol/filter ADSR, automation, offset).</p>
	 *
	 * <h2>Measurement 2 — B1 gather + invoke E3-like batched chain</h2>
	 * <p>Same gather plus a single {@code evaluate()} on a pre-compiled E3-style chain
	 * (lowpass with per-sample cutoff, trim padding, multiply by per-row volume envelope).
	 * Per-row envelope tensors are pre-built outside the timed loop — this intentionally
	 * underestimates the realistic per-tick cost; a follow-on iteration measures the
	 * in-kernel envelope path once the pure-Producer envelope refactor is in place.</p>
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void benchmarkJavaSideGatherCostB1() throws Exception {
		setupResultsListener();

		int padHalf = FILTER_ORDER / 2;
		int paddedNoteSize = NOTE_SIZE + 2 * padHalf;

		log("==========================================================");
		log("  ADDITION 7 — Java-side gather cost (Path B1, per-note memcpy)");
		log("  Builds [N, " + paddedNoteSize + "] audio tensor + 11 [N] scalar tensors per tick");
		log("==========================================================");
		log("");
		log("Source pool: " + B1_SOURCE_POOL_SIZE + " buffers of varying length ("
				+ B1_SOURCE_MIN_SAMPLES + "-" + (B1_SOURCE_MIN_SAMPLES + B1_SOURCE_EXTRA_RANGE)
				+ " samples).");
		log("Padding: " + padHalf + " zero samples on each side of NOTE_SIZE=" + NOTE_SIZE
				+ " → paddedNoteSize=" + paddedNoteSize);
		log("");

		Random rng = new Random(54321L);
		int[] sourceLengths = new int[B1_SOURCE_POOL_SIZE];
		double[][] sourcePool = new double[B1_SOURCE_POOL_SIZE][];
		for (int s = 0; s < B1_SOURCE_POOL_SIZE; s++) {
			sourceLengths[s] = B1_SOURCE_MIN_SAMPLES + rng.nextInt(B1_SOURCE_EXTRA_RANGE);
			sourcePool[s] = new double[sourceLengths[s]];
			for (int i = 0; i < sourceLengths[s]; i++) {
				sourcePool[s][i] = rng.nextDouble() * 2.0 - 1.0;
			}
		}

		log("--- Measurement 1: B1 gather + scalar tensor build (no kernel invoke) ---");
		log("");

		for (int npm : NOTES_PER_MEASURE_VALUES) {
			int totalNotes = npm * MEASURES_PER_TICK;
			B1NoteMetadata md = buildB1NoteMetadata(totalNotes, sourceLengths, rng);

			PackedCollection audioBuf = new PackedCollection(shape(totalNotes, paddedNoteSize));
			double[] audioData = new double[totalNotes * paddedNoteSize];

			for (int w = 0; w < WARMUP_RUNS; w++) {
				runB1Gather(audioBuf, audioData, totalNotes, paddedNoteSize, padHalf, sourcePool, md);
			}

			long[] timesNs = new long[TIMED_RUNS];
			for (int r = 0; r < TIMED_RUNS; r++) {
				long t0 = System.nanoTime();
				runB1Gather(audioBuf, audioData, totalNotes, paddedNoteSize, padHalf, sourcePool, md);
				timesNs[r] = System.nanoTime() - t0;
			}
			reportStats(npm, totalNotes, timesNs);
		}

		log("--- Measurement 2: B1 gather + invoke E3-like batched chain ---");
		log("Chain: lowPass(audio, perRowCutoff) → trim → × perRowVolumeEnv");
		log("Per-row envelopes pre-built outside timed loop (in-kernel envelope generation");
		log("from scalar ADSR is deferred to a follow-on iteration — see Addition 7 memo).");
		log("");

		for (int npm : NOTES_PER_MEASURE_VALUES) {
			int totalNotes = npm * MEASURES_PER_TICK;
			B1NoteMetadata md = buildB1NoteMetadata(totalNotes, sourceLengths, rng);

			B1Chain compiled;
			try {
				long compileStart = System.currentTimeMillis();
				compiled = buildB1E3LikeChain(totalNotes, padHalf, paddedNoteSize);
				long compileMs = System.currentTimeMillis() - compileStart;
				log("notes_per_measure=" + npm + " (total=" + totalNotes + "): compile " + compileMs + " ms");
			} catch (Exception ex) {
				log("Compilation failed for B1+E3 chain at " + totalNotes + " notes: "
						+ ex.getClass().getSimpleName() + ": " + ex.getMessage());
				log("");
				continue;
			}

			double[] audioData = new double[totalNotes * paddedNoteSize];

			for (int w = 0; w < WARMUP_RUNS; w++) {
				runB1GatherAndInvoke(compiled, audioData, totalNotes, paddedNoteSize, padHalf,
						sourcePool, md);
			}

			long[] timesNs = new long[TIMED_RUNS];
			for (int r = 0; r < TIMED_RUNS; r++) {
				long t0 = System.nanoTime();
				runB1GatherAndInvoke(compiled, audioData, totalNotes, paddedNoteSize, padHalf,
						sourcePool, md);
				timesNs[r] = System.nanoTime() - t0;
			}
			reportStats(npm, totalNotes, timesNs);
		}

		log("B1 gather interpretation:");
		log("- Measurement 1 is the floor for any Path B sub-option: every variant has to");
		log("  hand the kernel a set of [N]-shaped inputs each tick. Lower bound on gather cost.");
		log("- Measurement 2 adds one batched kernel invocation. The delta between the two is");
		log("  the kernel cost on top of the prepared inputs — comparable to E3's batched ceiling.");
		log("- A single-digit ms total at production density (64 notes/m) confirms B1 is viable.");
		log("- If Measurement 1 alone is double-digit ms, B1's per-note memcpy is the bottleneck");
		log("  and B2/B3/B4 (gather indirection / pre-staged buffers / per-neighborhood compile)");
		log("  need to be measured next.");
		log("==========================================================");
		log("Results appended to: " + RESULTS_PATH);
	}

	/**
	 * Builds per-note metadata for one density level: for each of {@code totalNotes} notes,
	 * a random source index drawn from the pool, a random in-source offset that leaves at
	 * least {@code NOTE_SIZE} samples to gather, and randomised pitch/ADSR/automation/start
	 * values. Called once per density outside the timed loop.
	 */
	private B1NoteMetadata buildB1NoteMetadata(int totalNotes, int[] sourceLengths, Random rng) {
		B1NoteMetadata md = new B1NoteMetadata(totalNotes);
		for (int n = 0; n < totalNotes; n++) {
			md.sourceIdx[n] = rng.nextInt(B1_SOURCE_POOL_SIZE);
			int srcLen = sourceLengths[md.sourceIdx[n]];
			md.sourceOffset[n] = rng.nextInt(Math.max(1, srcLen - NOTE_SIZE));
			md.pitchRatio[n] = 0.5 + rng.nextDouble() * 1.5;
			md.volAttack[n] = 0.01 + rng.nextDouble() * 0.1;
			md.volDecay[n] = 0.05 + rng.nextDouble() * 0.15;
			md.volSustain[n] = 0.3 + rng.nextDouble() * 0.5;
			md.volRelease[n] = 0.05 + rng.nextDouble() * 0.2;
			md.filterAttack[n] = 0.01 + rng.nextDouble() * 0.1;
			md.filterDecay[n] = 0.05 + rng.nextDouble() * 0.15;
			md.filterSustain[n] = 0.3 + rng.nextDouble() * 0.5;
			md.filterRelease[n] = 0.05 + rng.nextDouble() * 0.2;
			md.automationLevel[n] = rng.nextDouble();
			md.tickStartOffset[n] = rng.nextInt(NOTE_SIZE);
		}
		return md;
	}

	/**
	 * Executes one B1 gather iteration: copies each note's audio from the source pool into
	 * row {@code n}'s central slot of {@code audioData} (leaving pad zones as zero), pushes
	 * the gathered buffer to the pre-allocated audio collection, and allocates and populates
	 * the eleven {@code [N]} scalar tensors that production would need per tick.
	 */
	private void runB1Gather(PackedCollection audioBuf, double[] audioData,
							 int totalNotes, int paddedNoteSize, int padHalf,
							 double[][] sourcePool, B1NoteMetadata md) {
		for (int n = 0; n < totalNotes; n++) {
			System.arraycopy(sourcePool[md.sourceIdx[n]], md.sourceOffset[n],
					audioData, n * paddedNoteSize + padHalf, NOTE_SIZE);
		}
		audioBuf.setMem(audioData);

		PackedCollection.of(md.pitchRatio);
		PackedCollection.of(md.volAttack);
		PackedCollection.of(md.volDecay);
		PackedCollection.of(md.volSustain);
		PackedCollection.of(md.volRelease);
		PackedCollection.of(md.filterAttack);
		PackedCollection.of(md.filterDecay);
		PackedCollection.of(md.filterSustain);
		PackedCollection.of(md.filterRelease);
		PackedCollection.of(md.automationLevel);
		PackedCollection.of(md.tickStartOffset);
	}

	/**
	 * Runs one Measurement 2 iteration: the same gather and scalar-tensor build as
	 * {@link #runB1Gather}, then a single {@code evaluate()} on the pre-compiled chain
	 * captured in {@code compiled}. The chain references {@code compiled.audioInput} by
	 * the {@code cp(...)} idiom, so the {@code setMem} call inside {@link #runB1Gather}
	 * replaces the contents the chain sees on the next invocation.
	 */
	private void runB1GatherAndInvoke(B1Chain compiled, double[] audioData,
									  int totalNotes, int paddedNoteSize, int padHalf,
									  double[][] sourcePool, B1NoteMetadata md) {
		runB1Gather(compiled.audioInput, audioData, totalNotes, paddedNoteSize, padHalf,
				sourcePool, md);
		compiled.chain.evaluate();
	}

	/**
	 * Builds the Measurement 2 chain: a single compiled {@link Evaluable} that takes a
	 * pre-padded {@code [N, paddedNoteSize]} audio buffer (captured by reference) and runs
	 * the E3-style filter+volume chain (lowpass with per-sample cutoff, trim padding,
	 * multiply by per-row volume envelope). Per-row cutoff and envelope tensors are built
	 * once and captured as constants.
	 */
	private B1Chain buildB1E3LikeChain(int totalNotes, int padHalf, int paddedNoteSize) {
		PackedCollection audioInput = new PackedCollection(shape(totalNotes, paddedNoteSize));
		PackedCollection paddedCutoff = buildPerRowFilterCutoffs(totalNotes, padHalf, paddedNoteSize);
		PackedCollection envelopes = buildPerRowVolumeEnvelopes(totalNotes);

		int totalSamples = totalNotes * NOTE_SIZE;
		int paddedTotal = totalNotes * paddedNoteSize;

		CollectionProducer flatPaddedAudio = cp(audioInput).reshape(shape(paddedTotal));
		CollectionProducer flatPaddedCutoff = cp(paddedCutoff).reshape(shape(paddedTotal));
		MultiOrderFilter filter = lowPass(traverseEach(flatPaddedAudio), flatPaddedCutoff,
				SAMPLE_RATE, FILTER_ORDER);
		CollectionProducer filtered2D = c(filter).reshape(shape(totalNotes, paddedNoteSize));
		CollectionProducer trimmed = subset(shape(totalNotes, NOTE_SIZE), filtered2D, 0, padHalf);
		CollectionProducer flatTrimmed = trimmed.reshape(shape(totalSamples));
		CollectionProducer flatEnv = cp(envelopes).reshape(shape(totalSamples));
		Evaluable<PackedCollection> chain = flatTrimmed.multiply(flatEnv).get();
		return new B1Chain(audioInput, chain);
	}
}
