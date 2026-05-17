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

package org.almostrealism.music.pattern;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.audio.BatchedPatternRenderer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.notes.NoteAudioContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production integration boundary for the Phase 3 batched pattern rendering path.
 *
 * <p>This class is the alongside-the-per-note-path dispatch site activated by the
 * {@code AR_PATTERN_BATCHED} feature flag (see
 * {@link PatternLayerManager#enableBatched}). It collects all rendered notes for
 * a single {@code render} call (one per pattern repetition per
 * {@link org.almostrealism.music.notes.NoteAudioChoice}), groups them by start
 * frame, buckets each group's note count to a fixed set of {@link #BUCKETS}, and
 * dispatches through {@link BatchedPatternRenderer#buildBatchedChain} on the
 * gathered per-note inputs.</p>
 *
 * <h2>Bucket caching</h2>
 *
 * <p>The compiled batched kernel is fixed-N at construction time. To avoid
 * per-tick recompilation, note counts are ceiling-rounded to one of
 * {@link #BUCKETS} = {@code {16, 32, 64, 128, 256, 512}}, and compiled renderers
 * are cached per bucket. Variable-N within a bucket is handled by padding
 * unused rows with silent (zero-source, zero-volume) inputs so they contribute
 * nothing to the accumulate-reduce kernel's output (worst-case padding /
 * Option A from the Phase 3 design).</p>
 *
 * <h2>Per-note input gather (B1)</h2>
 *
 * <p>Each overlapping note must populate
 * {@link RenderedNoteAudio#getBatchedSource()},
 * {@link RenderedNoteAudio#getBatchedFilterCutoffEnvelope()}, and
 * {@link RenderedNoteAudio#getBatchedVolumeEnvelope()} before the renderer can
 * dispatch through {@link BatchedPatternRenderer#buildBatchedChain}. Per-note
 * pitch ratio defaults to {@code 1.0} when not set. The gather is grouped by
 * {@link RenderedNoteAudio#getOffset()} so the {@code [targetLength]} reduced
 * output of each batched dispatch is summed into the destination buffer at the
 * shared per-group start frame.</p>
 *
 * <h2>Fallback behaviour</h2>
 *
 * <p>When one or more overlapping notes have not yet been populated with
 * batched inputs, this renderer falls back to
 * {@link PatternFeatures#renderPerNote} for the entire {@code render} call and
 * logs a one-time-per-pattern-layer warning. The warning makes the fallback
 * explicit (no silent acoustic-equivalence-by-tautology trap) and identifies
 * the missing plumbing so the downstream caller can populate the batched
 * inputs and engage the kernel chain. The fallback is enabled by default;
 * setting {@code AR_PATTERN_BATCHED_STRICT=true} converts the fallback to an
 * {@link IllegalStateException}.</p>
 *
 * @see BatchedPatternRenderer
 * @see PatternLayerManager#enableBatched
 */
public final class BatchedPatternLayerRenderer implements ConsoleFeatures {

	/**
	 * Note-count buckets used to share compiled batched kernels across ticks
	 * with variable N. Each tick's note count is ceiling-rounded to the next
	 * bucket and the unused rows pad with silent (zero) inputs.
	 */
	public static final int[] BUCKETS = {16, 32, 64, 128, 256, 512};

	/**
	 * Process-wide counter of {@code render} calls that fell back to
	 * {@link PatternFeatures#renderPerNote} because one or more overlapping
	 * notes did not populate the {@link RenderedNoteAudio} batched inputs.
	 * Verification tests assert this counter does (or does not) advance to
	 * confirm the batched dispatch path engaged. The counter is reset to zero
	 * by tests that want to observe its delta over a specific operation.
	 */
	public static final AtomicLong fallbackCount = new AtomicLong();

	/**
	 * Bucket-keyed cache of compiled batched renderers together with their
	 * persistent input {@link PackedCollection} buffers. The cache key is the
	 * bucket-N (one of {@link #BUCKETS}); a single {@link BucketBuffers}
	 * entry per bucket is shared across all ticks so the compiled batched
	 * kernel hits the framework's kernel cache by stable {@link PackedCollection}
	 * identity instead of recompiling every dispatch. Concurrent access is
	 * supported for future multi-thread render orchestration.
	 */
	private final ConcurrentMap<Integer, BucketBuffers> bucketCache =
			new ConcurrentHashMap<>();

	/** Source samples per note before resampling. */
	private final int sourceLength;

	/** Target samples per note after resampling. */
	private final int targetLength;

	/** Audio sample rate in Hz. */
	private final int sampleRate;

	/** FIR filter order matching the production per-channel filter. */
	private final int filterOrder;

	/**
	 * One-time-per-instance latch tracking whether the per-note input plumbing
	 * gap warning has already been emitted. Prevents log spam across every tick
	 * of a long arrangement when batched inputs are not yet wired through.
	 */
	private volatile boolean fallbackWarningEmitted;

	/**
	 * Constructs a batched layer renderer with the given fixed parameters.
	 * Per-bucket {@link BatchedPatternRenderer} instances are lazily created
	 * on first dispatch to each bucket.
	 *
	 * @param sourceLength source samples per note before resampling
	 * @param targetLength target samples per note after resampling
	 * @param sampleRate   audio sample rate in Hz
	 * @param filterOrder  FIR filter order for the per-row lowpass kernel
	 */
	public BatchedPatternLayerRenderer(int sourceLength, int targetLength,
									   int sampleRate, int filterOrder) {
		this.sourceLength = sourceLength;
		this.targetLength = targetLength;
		this.sampleRate = sampleRate;
		this.filterOrder = filterOrder;
	}

	/**
	 * Returns the smallest bucket N {@code >=} the given note count, or the
	 * largest bucket when the count exceeds {@link #BUCKETS}.
	 *
	 * @param n raw note count for the current tick
	 * @return the chosen bucket N
	 */
	public static int bucketFor(int n) {
		for (int b : BUCKETS) {
			if (b >= n) return b;
		}
		return BUCKETS[BUCKETS.length - 1];
	}

	/**
	 * Returns the cached {@link BatchedPatternRenderer} for the given bucket,
	 * lazily constructing one if absent.
	 *
	 * @param bucket the bucket-N (one of {@link #BUCKETS}, or anything else if
	 *               oversized)
	 * @return the renderer compiled for that bucket
	 */
	public BatchedPatternRenderer rendererFor(int bucket) {
		return buffersFor(bucket).renderer;
	}

	/**
	 * Returns the cached {@link BucketBuffers} for the given bucket, lazily
	 * constructing it on first access. Subsequent dispatches reuse the same
	 * input {@link PackedCollection} instances so the compiled kernel chain
	 * hits the framework's kernel cache instead of recompiling per dispatch.
	 *
	 * @param bucket the bucket-N
	 * @return the cached buffers and renderer for that bucket
	 */
	private BucketBuffers buffersFor(int bucket) {
		return bucketCache.computeIfAbsent(bucket, b ->
				new BucketBuffers(b, sourceLength, targetLength,
						sampleRate, filterOrder));
	}

	/** Returns the source samples per note configured for this renderer. */
	public int getSourceLength() { return sourceLength; }

	/** Returns the target samples per note after resampling. */
	public int getTargetLength() { return targetLength; }

	/** Returns the configured audio sample rate in Hz. */
	public int getSampleRate() { return sampleRate; }

	/** Returns the configured FIR filter order. */
	public int getFilterOrder() { return filterOrder; }

	/**
	 * Renders a layer's pattern elements via the batched dispatch boundary.
	 *
	 * <p>Signature mirrors {@link PatternFeatures#render} so this method can be
	 * called as a drop-in replacement when {@link PatternLayerManager#enableBatched}
	 * is set. The {@code features} argument supplies the per-note fallback path
	 * via {@link PatternFeatures#renderPerNote}.</p>
	 *
	 * <p>Per-note inputs ({@link RenderedNoteAudio#getBatchedSource()},
	 * {@link RenderedNoteAudio#getBatchedPitchRatio()},
	 * {@link RenderedNoteAudio#getBatchedFilterCutoffEnvelope()},
	 * {@link RenderedNoteAudio#getBatchedVolumeEnvelope()}) are gathered and
	 * grouped by start frame; each group is bucket-rounded, padded with zero
	 * rows, and dispatched through
	 * {@link BatchedPatternRenderer#buildBatchedChain}. The reduced
	 * {@code [targetLength]} output is summed into the destination at the
	 * shared group start frame.</p>
	 *
	 * @param features     the {@link PatternFeatures} instance providing the
	 *                     fallback per-note path
	 * @param sceneContext scene context containing the destination buffer
	 * @param audioContext note audio context
	 * @param elements     elements to render
	 * @param melodic      whether to use melodic or percussive rendering
	 * @param offset       measure offset for pattern positioning
	 * @param startFrame   starting frame of the target range (absolute position)
	 * @param frameCount   number of frames in the target range
	 * @param cache        optional cache for evaluated note audio (may be null)
	 */
	public void render(PatternFeatures features,
					   AudioSceneContext sceneContext, NoteAudioContext audioContext,
					   List<PatternElement> elements, boolean melodic, double offset,
					   int startFrame, int frameCount, NoteAudioCache cache) {
		PackedCollection destination = sceneContext.getDestination();
		if (destination == null) {
			throw new IllegalArgumentException("Destination buffer is null");
		}

		int endFrame = startFrame + frameCount;

		List<RenderedNoteAudio> destinations = elements.stream()
				.map(e -> e.getNoteDestinations(melodic, offset, sceneContext, audioContext))
				.flatMap(List::stream)
				.toList();

		List<RenderedNoteAudio> overlapping = new ArrayList<>();
		for (RenderedNoteAudio note : destinations) {
			int noteStart = note.getOffset();
			if (note.getExpectedFrameCount() > 0) {
				int noteEstimatedEnd = noteStart + note.getExpectedFrameCount();
				if (noteEstimatedEnd <= startFrame || noteStart >= endFrame) continue;
			} else if (noteStart >= endFrame) {
				continue;
			}
			overlapping.add(note);
		}

		if (overlapping.isEmpty()) {
			return;
		}

		// Check whether the per-note input plumbing is populated for every
		// overlapping note. When any overlapping note has not yet been wired
		// for batched dispatch, fall back to renderPerNote with a one-time
		// warning (or fail loud when AR_PATTERN_BATCHED_STRICT=true).
		boolean anyMissing = false;
		for (RenderedNoteAudio note : overlapping) {
			if (!note.hasBatchedInputs()) {
				anyMissing = true;
				break;
			}
		}

		if (anyMissing) {
			emitFallbackWarning();
			if (PatternLayerManager.enableBatchedStrict) {
				throw new IllegalStateException(
						"BatchedPatternLayerRenderer cannot dispatch through buildBatchedChain"
								+ " — overlapping note(s) lack populated batched inputs"
								+ " (source/filterCutoffEnvelope/volumeEnvelope)."
								+ " Configure RenderedNoteAudio at note-creation time"
								+ " or disable AR_PATTERN_BATCHED_STRICT to fall back"
								+ " to renderPerNote.");
			}
			features.renderPerNote(sceneContext, audioContext, elements, melodic, offset,
					startFrame, frameCount, cache);
			return;
		}

		// Group overlapping notes by start frame. The batched kernel reduces
		// across the note axis assuming all rows are time-aligned at sample 0
		// of the [targetLength] output, so notes with different start frames
		// must be dispatched in separate groups.
		Map<Integer, List<RenderedNoteAudio>> byStartFrame = new LinkedHashMap<>();
		for (RenderedNoteAudio note : overlapping) {
			byStartFrame.computeIfAbsent(note.getOffset(),
					k -> new ArrayList<>()).add(note);
		}

		for (Map.Entry<Integer, List<RenderedNoteAudio>> entry : byStartFrame.entrySet()) {
			int groupStartFrame = entry.getKey();
			List<RenderedNoteAudio> group = entry.getValue();
			dispatchGroup(features, destination, groupStartFrame, group,
					startFrame, endFrame, frameCount);
		}
	}

	/**
	 * Dispatches one bucket-padded {@link BatchedPatternRenderer#buildBatchedChain}
	 * call for the given group of notes that share a start frame and sums the
	 * reduced output into the destination at the shared start frame.
	 *
	 * <p>Group size is ceiling-rounded to the next bucket size; padding rows
	 * are zero-filled across source, filter, and volume inputs so they
	 * contribute nothing to the accumulate-reduce kernel's output. The pitch
	 * ratio for padding rows is set to {@code 1.0} to avoid zero-division or
	 * out-of-range indices in the resample gather.</p>
	 *
	 * <p>The compiled-kernel dispatch (the {@code .evaluate()}) is routed
	 * through {@link PatternFeatures#dispatchBatchedGroup} so the pipeline
	 * boundary lives on {@link PatternFeatures}, mirroring the per-note
	 * dispatch path in {@link PatternFeatures#renderPerNote}.</p>
	 *
	 * @param features        owner of the pipeline boundary that runs the
	 *                        batched chain through {@code .evaluate()}
	 * @param destination     destination buffer to sum the reduced output into
	 * @param groupStartFrame absolute frame at which the group's notes start
	 * @param group           notes sharing {@code groupStartFrame}; every entry
	 *                        must have {@link RenderedNoteAudio#hasBatchedInputs()}
	 *                        true
	 * @param tickStartFrame  absolute start frame of the current tick range
	 * @param tickEndFrame    absolute end frame of the current tick range
	 *                        (exclusive)
	 * @param tickFrameCount  {@code tickEndFrame - tickStartFrame}
	 */
	private void dispatchGroup(PatternFeatures features,
							   PackedCollection destination, int groupStartFrame,
							   List<RenderedNoteAudio> group,
							   int tickStartFrame, int tickEndFrame, int tickFrameCount) {
		int actualN = group.size();
		int bucket = bucketFor(actualN);
		BucketBuffers buffers = buffersFor(bucket);

		double[] ratioArr = buffers.ratioScratch;

		for (int i = 0; i < actualN; i++) {
			RenderedNoteAudio note = group.get(i);
			PackedCollection src = note.getBatchedSource();
			PackedCollection cut = note.getBatchedFilterCutoffEnvelope();
			PackedCollection vol = note.getBatchedVolumeEnvelope();

			if (src.getMemLength() != sourceLength) {
				throw new IllegalStateException(
						"Note batched source length " + src.getMemLength()
								+ " does not match renderer sourceLength " + sourceLength);
			}
			if (cut.getMemLength() != targetLength) {
				throw new IllegalStateException(
						"Note batched filter envelope length " + cut.getMemLength()
								+ " does not match renderer targetLength " + targetLength);
			}
			if (vol.getMemLength() != targetLength) {
				throw new IllegalStateException(
						"Note batched volume envelope length " + vol.getMemLength()
								+ " does not match renderer targetLength " + targetLength);
			}

			// Bulk memory-to-memory copy into the cached per-bucket buffers.
			// Same buffer identity across dispatches → stable compiled-kernel
			// signature → kernel cache hit on the second and subsequent calls.
			buffers.batchedSource.setMem(i * sourceLength, src, 0, sourceLength);
			buffers.filterCutoffs.setMem(i * targetLength, cut, 0, targetLength);
			buffers.volumeEnvelopes.setMem(i * targetLength, vol, 0, targetLength);
			ratioArr[i] = note.getBatchedPitchRatio();
		}

		// Padding rows: zero the volume envelope so each padding row contributes
		// nothing to the accumulate-reduce regardless of stale source / cutoff
		// data left from a previous (larger) dispatch in the same bucket. The
		// pitch ratio is set to 1.0 to keep the resample gather well-defined.
		// Iterate only when the previous dispatch in this bucket wrote a row
		// beyond the current actualN; if every prior dispatch had at most the
		// current actualN, the volume rows beyond actualN are already zero from
		// construction and need no rewrite.
		int prevMax = buffers.maxWrittenRow;
		int zeroUpper = Math.max(prevMax, actualN);
		for (int i = actualN; i < zeroUpper; i++) {
			buffers.volumeEnvelopes.setMem(i * targetLength, buffers.zeroTargetRow, 0, targetLength);
		}
		buffers.maxWrittenRow = actualN;
		for (int i = actualN; i < bucket; i++) {
			ratioArr[i] = 1.0;
		}
		buffers.ratios.setMem(0, ratioArr, 0, bucket);

		features.dispatchBatchedGroup(buffers.compiledEvaluable(), destination,
				groupStartFrame, tickStartFrame, tickEndFrame, tickFrameCount);
	}

	/**
	 * Cached per-bucket inputs and renderer. The same instances are reused
	 * across every dispatch to the same bucket so the framework's kernel
	 * cache hits by stable {@link PackedCollection} identity, avoiding the
	 * per-dispatch recompile that fresh allocation would force.
	 */
	private static final class BucketBuffers {
		/** Compiled batched renderer sized for this bucket-N. */
		final BatchedPatternRenderer renderer;
		/** Cached {@code [bucket, sourceLength]} batched source buffer. */
		final PackedCollection batchedSource;
		/** Cached {@code [bucket]} per-row pitch ratio buffer. */
		final PackedCollection ratios;
		/** Cached {@code [bucket, targetLength]} per-sample filter cutoff buffer. */
		final PackedCollection filterCutoffs;
		/** Cached {@code [bucket, targetLength]} per-sample volume envelope buffer. */
		final PackedCollection volumeEnvelopes;
		/** Reusable Java-side scratch array for the ratios row before {@code setMem}. */
		final double[] ratioScratch;
		/** Reusable zero-filled Java-side row used to zero padding rows of length {@code targetLength}. */
		final double[] zeroTargetRow;
		/** Highest row index written by the most recent dispatch; used to bound the padding-zero loop. */
		int maxWrittenRow;
		/**
		 * Lazily-built compiled {@link Evaluable} for the four-kernel batched
		 * chain bound to this bucket's cached input buffers. Initialised on
		 * first dispatch (so the construction of buffers is cheap) and reused
		 * across every subsequent dispatch to this bucket.
		 */
		private volatile Evaluable<PackedCollection> evaluable;

		/**
		 * Allocates the persistent per-bucket buffers and constructs the
		 * matching {@link BatchedPatternRenderer}.
		 *
		 * @param bucket       the bucket-N this buffer set serves
		 * @param sourceLength source samples per note before resampling
		 * @param targetLength target samples per note after resampling
		 * @param sampleRate   audio sample rate in Hz
		 * @param filterOrder  FIR filter order for the per-row lowpass kernel
		 */
		BucketBuffers(int bucket, int sourceLength, int targetLength,
					  int sampleRate, int filterOrder) {
			this.renderer = new BatchedPatternRenderer(bucket, sourceLength,
					targetLength, sampleRate, filterOrder);
			this.batchedSource =
					new PackedCollection(new TraversalPolicy(bucket, sourceLength));
			this.ratios = new PackedCollection(bucket);
			this.filterCutoffs =
					new PackedCollection(new TraversalPolicy(bucket, targetLength));
			this.volumeEnvelopes =
					new PackedCollection(new TraversalPolicy(bucket, targetLength));
			this.ratioScratch = new double[bucket];
			this.zeroTargetRow = new double[targetLength];
			this.maxWrittenRow = 0;
		}

		/**
		 * Returns the compiled {@link Evaluable} for this bucket, building and
		 * caching it on first call. Subsequent calls return the same instance
		 * so the framework's kernel cache is not consulted on every dispatch.
		 *
		 * @return the cached compiled batched chain
		 */
		Evaluable<PackedCollection> compiledEvaluable() {
			Evaluable<PackedCollection> ev = evaluable;
			if (ev == null) {
				synchronized (this) {
					ev = evaluable;
					if (ev == null) {
						ev = renderer
								.buildBatchedChain(batchedSource, ratios,
										filterCutoffs, volumeEnvelopes)
								.get();
						evaluable = ev;
					}
				}
			}
			return ev;
		}
	}

	/**
	 * Emits a single one-time-per-instance warning describing the per-note
	 * input plumbing gap that forced the renderer to fall back to
	 * {@link PatternFeatures#renderPerNote}.
	 */
	private void emitFallbackWarning() {
		fallbackCount.incrementAndGet();
		if (fallbackWarningEmitted) return;
		synchronized (this) {
			if (fallbackWarningEmitted) return;
			fallbackWarningEmitted = true;
		}
		warn("Falling back to renderPerNote — overlapping note(s) lack populated "
				+ "RenderedNoteAudio batched inputs (source, filterCutoffEnvelope, "
				+ "volumeEnvelope). Phase 3 4-kernel chain not invoked for this "
				+ "render call. Set AR_PATTERN_BATCHED_STRICT=true to convert "
				+ "this fallback to a hard error.");
	}
}
