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

package org.almostrealism.music.pattern.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.audio.BatchedPatternRenderer;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.notes.NoteAudioContext;
import org.almostrealism.music.pattern.BatchedPatternLayerRenderer;
import org.almostrealism.music.pattern.PatternElement;
import org.almostrealism.music.pattern.PatternFeatures;
import org.almostrealism.music.pattern.PatternLayerManager;
import org.almostrealism.music.pattern.RenderedNoteAudio;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Production-shaped verification that the {@code AR_PATTERN_BATCHED} feature
 * flag actually engages the {@link BatchedPatternRenderer#buildBatchedChain}
 * four-kernel chain when the per-note inputs are populated.
 *
 * <p>The earlier Phase 3 acoustic-equivalence test
 * (<code>BatchedPatternRendererTest</code>) compares the kernel chain to a
 * per-note reference at the renderer level. It does not — and cannot —
 * detect whether {@link BatchedPatternLayerRenderer#render} actually
 * dispatches through that kernel chain in production code paths, because
 * the dispatch boundary is one level higher. This test closes that gap by
 * asserting on a sentinel counter
 * ({@link BatchedPatternRenderer#batchedDispatchCount}) that is incremented
 * exclusively inside {@link BatchedPatternRenderer#buildBatchedChain}.</p>
 *
 * <p>Three scenarios are exercised:</p>
 * <ol>
 *   <li><b>Flag-off baseline:</b> {@code enableBatched=false}. The sentinel
 *       counter must NOT advance.</li>
 *   <li><b>Flag-on with populated batched inputs:</b> {@code enableBatched=true}
 *       and every note populates {@link RenderedNoteAudio#hasBatchedInputs()}.
 *       The sentinel counter must advance (≥ 1).</li>
 *   <li><b>Flag-on with missing batched inputs (fallback):</b>
 *       {@code enableBatched=true} but notes have NOT populated batched
 *       inputs. The fallback path is exercised and
 *       {@link BatchedPatternLayerRenderer#fallbackCount} advances. The
 *       sentinel counter does NOT advance (the kernel chain is not invoked).</li>
 * </ol>
 *
 * @see BatchedPatternRenderer#batchedDispatchCount
 * @see BatchedPatternLayerRenderer#render
 * @see PatternLayerManager#enableBatched
 */
public class PatternRenderingBatchedVerificationTest extends TestSuiteBase
		implements PatternFeatures {

	/** Source samples per synthetic note before resampling. */
	private static final int SOURCE_LENGTH = 256;

	/** Target samples per synthetic note after resampling. */
	private static final int TARGET_LENGTH = 128;

	/** Sample rate used by the synthetic renderer. */
	private static final int SAMPLE_RATE = 44100;

	/** FIR filter order used by the synthetic renderer. */
	private static final int FILTER_ORDER = 20;

	/** Number of synthetic notes per test (kept below the smallest bucket). */
	private static final int NOTE_COUNT = 4;

	/** Tick frame count — large enough to accommodate the reduced output. */
	private static final int TICK_FRAME_COUNT = TARGET_LENGTH * 2;

	/** Production source samples per note (matches PatternLayerManager default). */
	private static final int PROD_SOURCE_LENGTH = 2048;

	/** Production target samples per note (matches PatternLayerManager default). */
	private static final int PROD_TARGET_LENGTH = 1024;

	/** Production FIR order (matches MultiOrderFilterEnvelopeProcessor.filterOrder). */
	private static final int PROD_FILTER_ORDER = 40;

	/**
	 * Note count for the wall-clock measurement scenario — chosen to fall into
	 * the 64-note bucket exactly, matching the realtime memo's reference
	 * working point.
	 */
	private static final int PROD_NOTE_COUNT = 64;

	/** Tick frame count for the production-shaped measurement scenario. */
	private static final int PROD_TICK_FRAMES = PROD_TARGET_LENGTH;

	/** Warmup iterations for the wall-clock measurement. */
	private static final int PROD_WARMUP_RUNS = 3;

	/** Timed iterations for the wall-clock measurement. */
	private static final int PROD_TIMED_RUNS = 10;

	private BatchedPatternLayerRenderer renderer;
	private boolean savedEnableBatched;
	private boolean savedEnableBatchedStrict;

	@Override
	public BatchedPatternLayerRenderer getBatchedLayerRenderer() {
		return renderer;
	}

	@Before
	public void setUp() {
		savedEnableBatched = PatternLayerManager.enableBatched;
		savedEnableBatchedStrict = PatternLayerManager.enableBatchedStrict;
		renderer = new BatchedPatternLayerRenderer(
				SOURCE_LENGTH, TARGET_LENGTH, SAMPLE_RATE, FILTER_ORDER);
		BatchedPatternRenderer.batchedDispatchCount.set(0);
		BatchedPatternLayerRenderer.fallbackCount.set(0);
	}

	@After
	public void tearDown() {
		PatternLayerManager.enableBatched = savedEnableBatched;
		PatternLayerManager.enableBatchedStrict = savedEnableBatchedStrict;
	}

	/**
	 * Flag-off baseline: the {@link BatchedPatternRenderer#batchedDispatchCount}
	 * sentinel must not advance when {@code AR_PATTERN_BATCHED=false}, because
	 * the per-note path does not call {@link BatchedPatternRenderer#buildBatchedChain}.
	 */
	@Test(timeout = 60_000)
	public void flagOff_doesNotInvokeBatchedKernel() {
		PatternLayerManager.enableBatched = false;
		List<PatternElement> elements = syntheticElements(NOTE_COUNT, SOURCE_LENGTH, TARGET_LENGTH, true);
		AudioSceneContext ctx = minimalContext(TICK_FRAME_COUNT);

		long before = BatchedPatternRenderer.batchedDispatchCount.get();
		render(ctx, null, elements, false, 0.0, 0, TICK_FRAME_COUNT, null);
		long after = BatchedPatternRenderer.batchedDispatchCount.get();

		Assert.assertEquals(
				"With AR_PATTERN_BATCHED=false the batched dispatch counter "
						+ "must not advance — saw delta " + (after - before),
				before, after);
	}

	/**
	 * Flag-on with populated batched inputs: the batched dispatch counter
	 * must advance (≥ 1), confirming the four-kernel chain ran.
	 */
	@Test(timeout = 60_000)
	public void flagOn_withBatchedInputs_invokesBatchedKernel() {
		PatternLayerManager.enableBatched = true;
		List<PatternElement> elements = syntheticElements(NOTE_COUNT, SOURCE_LENGTH, TARGET_LENGTH, true);
		AudioSceneContext ctx = minimalContext(TICK_FRAME_COUNT);

		long before = BatchedPatternRenderer.batchedDispatchCount.get();
		long fallbackBefore = BatchedPatternLayerRenderer.fallbackCount.get();
		render(ctx, null, elements, false, 0.0, 0, TICK_FRAME_COUNT, null);
		long after = BatchedPatternRenderer.batchedDispatchCount.get();
		long fallbackAfter = BatchedPatternLayerRenderer.fallbackCount.get();

		Assert.assertTrue(
				"With AR_PATTERN_BATCHED=true and populated batched inputs, "
						+ "the batched dispatch counter must advance at least once; "
						+ "delta=" + (after - before),
				after > before);
		Assert.assertEquals(
				"No fallback warnings expected on the happy batched path; "
						+ "delta=" + (fallbackAfter - fallbackBefore),
				fallbackBefore, fallbackAfter);
	}

	/**
	 * Flag-on without batched inputs: the renderer falls back to
	 * {@link PatternFeatures#renderPerNote} (explicit, not silent). The
	 * batched dispatch counter must not advance and the fallback counter
	 * must advance.
	 */
	@Test(timeout = 60_000)
	public void flagOn_withoutBatchedInputs_fallsBackExplicitly() {
		PatternLayerManager.enableBatched = true;
		PatternLayerManager.enableBatchedStrict = false;
		List<PatternElement> elements = syntheticElements(NOTE_COUNT, SOURCE_LENGTH, TARGET_LENGTH, false);
		AudioSceneContext ctx = minimalContext(TICK_FRAME_COUNT);

		long batchedBefore = BatchedPatternRenderer.batchedDispatchCount.get();
		long fallbackBefore = BatchedPatternLayerRenderer.fallbackCount.get();
		// Per-note path may surface its own warnings; we ignore them. The
		// notes' producer factories return a constant audio buffer below.
		render(ctx, null, elements, false, 0.0, 0, TICK_FRAME_COUNT, null);
		long batchedAfter = BatchedPatternRenderer.batchedDispatchCount.get();
		long fallbackAfter = BatchedPatternLayerRenderer.fallbackCount.get();

		Assert.assertEquals(
				"Without batched inputs the batched dispatch counter must not "
						+ "advance — delta=" + (batchedAfter - batchedBefore),
				batchedBefore, batchedAfter);
		Assert.assertTrue(
				"Fallback counter must advance when batched inputs are missing; "
						+ "delta=" + (fallbackAfter - fallbackBefore),
				fallbackAfter > fallbackBefore);
	}

	/**
	 * Flag-on, strict mode, missing batched inputs: the renderer must throw
	 * {@link IllegalStateException} instead of silently falling back.
	 */
	@Test(timeout = 60_000)
	public void flagOnStrict_withoutBatchedInputs_failsLoud() {
		PatternLayerManager.enableBatched = true;
		PatternLayerManager.enableBatchedStrict = true;
		List<PatternElement> elements = syntheticElements(NOTE_COUNT, SOURCE_LENGTH, TARGET_LENGTH, false);
		AudioSceneContext ctx = minimalContext(TICK_FRAME_COUNT);

		try {
			render(ctx, null, elements, false, 0.0, 0, TICK_FRAME_COUNT, null);
			Assert.fail("Strict batched dispatch should have thrown IllegalStateException");
		} catch (IllegalStateException e) {
			Assert.assertTrue(
					"Strict-mode message must reference the missing batched inputs: "
							+ e.getMessage(),
					e.getMessage().contains("batched inputs"));
		}
	}

	/**
	 * Production-shaped wall-clock measurement. Uses the same renderer
	 * dimensions ({@code SOURCE_LENGTH=2048}, {@code TARGET_LENGTH=1024},
	 * sample rate from {@link org.almostrealism.audio.line.OutputLine}, FIR
	 * order 40) as the production
	 * {@link PatternLayerManager#getBatchedLayerRenderer()} default and 64
	 * batched notes per dispatch, then asserts the compute-to-audio time
	 * ratio is well below 1.0 across timed iterations.
	 *
	 * <p>The compute time is summed across {@link #PROD_TIMED_RUNS} timed
	 * dispatches; the audio time is the produced
	 * {@code targetLength / sampleRate × runs} duration. The ratio is logged
	 * and asserted to be below 1.0 — the task threshold ("anything better
	 * than 1:1 is acceptable for this iteration").</p>
	 */
	@Test(timeout = 600_000)
	@TestDepth(2)
	public void productionShapedWallClockMeasurement() {
		PatternLayerManager.enableBatched = true;
		PatternLayerManager.enableBatchedStrict = false;

		// Replace the @Before-installed small renderer with a production-sized
		// one for this test only. JUnit creates a new test instance per @Test
		// method, so this does not affect other tests.
		renderer = new BatchedPatternLayerRenderer(
				PROD_SOURCE_LENGTH, PROD_TARGET_LENGTH,
				OutputLine.sampleRate,
				PROD_FILTER_ORDER);

		List<PatternElement> elements = syntheticElements(PROD_NOTE_COUNT,
				PROD_SOURCE_LENGTH, PROD_TARGET_LENGTH, true);
		AudioSceneContext ctx = minimalContext(PROD_TICK_FRAMES);

		// Warmup: compile bucket kernel, warm caches.
		for (int w = 0; w < PROD_WARMUP_RUNS; w++) {
			render(ctx, null, elements, false, 0.0, 0, PROD_TICK_FRAMES, null);
		}

		long totalComputeNanos = 0L;
		for (int i = 0; i < PROD_TIMED_RUNS; i++) {
			long t0 = System.nanoTime();
			render(ctx, null, elements, false, 0.0, 0, PROD_TICK_FRAMES, null);
			totalComputeNanos += System.nanoTime() - t0;
		}

		long sampleRate = OutputLine.sampleRate;
		long audioNanosPerRun = (long) (PROD_TICK_FRAMES * 1_000_000_000.0 / sampleRate);
		long totalAudioNanos = audioNanosPerRun * PROD_TIMED_RUNS;
		double ratio = totalComputeNanos / (double) totalAudioNanos;
		double meanComputeMs = totalComputeNanos / 1_000_000.0 / PROD_TIMED_RUNS;
		double audioMs = audioNanosPerRun / 1_000_000.0;

		log("productionShapedWallClockMeasurement"
				+ " noteCount=" + PROD_NOTE_COUNT
				+ " sourceLength=" + PROD_SOURCE_LENGTH
				+ " targetLength=" + PROD_TARGET_LENGTH
				+ " runs=" + PROD_TIMED_RUNS
				+ " totalComputeNanos=" + totalComputeNanos
				+ " totalAudioNanos=" + totalAudioNanos
				+ " ratio=" + String.format("%.4f", ratio)
				+ " meanComputeMs=" + String.format("%.3f", meanComputeMs)
				+ " audioMs=" + String.format("%.3f", audioMs));

		Assert.assertTrue(
				"Production-shaped batched dispatch must achieve ratio < 1.0; "
						+ "totalComputeNanos=" + totalComputeNanos
						+ " totalAudioNanos=" + totalAudioNanos
						+ " ratio=" + ratio
						+ " meanComputeMs/run=" + meanComputeMs
						+ " audioMs/run=" + audioMs,
				ratio < 1.0);
	}

	/**
	 * Builds a list of single-element pattern entries, each yielding one
	 * {@link RenderedNoteAudio}. When {@code withBatchedInputs} is true, the
	 * note's batched source / filter cutoff envelope / volume envelope fields
	 * are populated with synthetic data shaped to match the renderer
	 * dimensions; otherwise they are left null and the renderer must fall
	 * back (or throw in strict mode).
	 *
	 * @param count             number of notes/elements to build
	 * @param sourceLength      source samples per note before resampling
	 * @param targetLength      target samples per note after resampling
	 * @param withBatchedInputs whether to populate the batched-form fields
	 * @return list of pattern elements
	 */
	private List<PatternElement> syntheticElements(int count, int sourceLength,
												   int targetLength,
												   boolean withBatchedInputs) {
		List<PatternElement> elements = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			final int idx = i;
			PatternElement element = new PatternElement() {
				@Override
				public List<RenderedNoteAudio> getNoteDestinations(boolean melodic,
																   double offset,
																   AudioSceneContext context,
																   NoteAudioContext audioContext) {
					return List.of(buildNote(idx, sourceLength, targetLength, withBatchedInputs));
				}
			};
			elements.add(element);
		}
		return elements;
	}

	/**
	 * Builds a single synthetic {@link RenderedNoteAudio}. All notes share
	 * the same start frame so they land in a single batched-dispatch group.
	 * The producer factory returns a zero-filled buffer for the per-note
	 * fallback path — it does not need to match the batched chain output for
	 * this verification (the assertion is on the sentinel, not the audio).
	 */
	private RenderedNoteAudio buildNote(int idx, int sourceLength, int targetLength,
										boolean withBatchedInputs) {
		// All notes share frame 0 so they batch together.
		RenderedNoteAudio note = new RenderedNoteAudio(0, targetLength);
		note.setOffsetArg(new PackedCollection(1));
		note.setProducerFactory(frameCount ->
				cp(new PackedCollection(Math.max(targetLength, frameCount > 0 ? frameCount : targetLength))));

		if (withBatchedInputs) {
			PackedCollection src = new PackedCollection(sourceLength);
			double[] sData = new double[sourceLength];
			for (int s = 0; s < sourceLength; s++) {
				sData[s] = 0.1 * Math.sin(2.0 * Math.PI * (idx + 1) * s / sourceLength);
			}
			src.setMem(sData);

			PackedCollection cutoff = new PackedCollection(new TraversalPolicy(targetLength));
			double[] cData = new double[targetLength];
			for (int s = 0; s < targetLength; s++) {
				cData[s] = 2000.0 + idx * 100.0;
			}
			cutoff.setMem(cData);

			PackedCollection vol = new PackedCollection(new TraversalPolicy(targetLength));
			double[] vData = new double[targetLength];
			for (int s = 0; s < targetLength; s++) {
				vData[s] = 0.5;
			}
			vol.setMem(vData);

			note.setBatchedSource(src);
			note.setBatchedPitchRatio(1.0);
			note.setBatchedFilterCutoffEnvelope(cutoff);
			note.setBatchedVolumeEnvelope(vol);
		}

		return note;
	}

	/**
	 * Builds a minimal {@link AudioSceneContext} with a writable destination
	 * buffer sized for one tick. The context does not configure measures,
	 * frameForPosition, or other scene-level metadata — those are not
	 * consulted by the batched dispatch path on these synthetic inputs.
	 *
	 * @param tickFrames length of the destination buffer / tick frame count
	 */
	private AudioSceneContext minimalContext(int tickFrames) {
		AudioSceneContext ctx = new AudioSceneContext();
		ctx.setDestination(new PackedCollection(tickFrames));
		ctx.setFrames(tickFrames);
		ctx.setMeasures(1);
		return ctx;
	}
}
