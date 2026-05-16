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

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.BatchedPatternRenderer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.notes.NoteAudioContext;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Production integration boundary for the Phase 3 batched pattern rendering path.
 *
 * <p>This class is the alongside-the-per-note-path dispatch site activated by the
 * {@code AR_PATTERN_BATCHED} feature flag (see
 * {@link PatternLayerManager#enableBatched}). It collects all rendered notes for
 * a single {@code render} call (one per pattern repetition per
 * {@link org.almostrealism.music.notes.NoteAudioChoice}), buckets the note count
 * to a fixed set of {@link #BUCKETS}, and dispatches through
 * {@link BatchedPatternRenderer} when the underlying per-note inputs
 * (source buffer, pitch ratio, per-sample envelopes) are available.</p>
 *
 * <h2>Bucket caching</h2>
 *
 * <p>The compiled batched kernel is fixed-N at construction time. To avoid
 * per-tick recompilation, note counts are ceiling-rounded to one of
 * {@link #BUCKETS} = {@code {16, 32, 64, 128, 256, 512}}, and compiled renderers
 * are cached per bucket. Variable-N within a bucket is handled by padding
 * unused rows with silent (zero) inputs so they contribute nothing to the
 * accumulate-reduce kernel's output.</p>
 *
 * <h2>Java-side gather (B1)</h2>
 *
 * <p>For each note, the chain needs:</p>
 * <ul>
 *   <li>{@code [N, sourceLength]} source audio (per-note source via {@code System.arraycopy})</li>
 *   <li>{@code [N]} pitch ratios</li>
 *   <li>{@code [N, targetLength]} per-sample filter cutoff envelopes</li>
 *   <li>{@code [N, targetLength]} per-sample volume envelopes</li>
 * </ul>
 *
 * <p>Per the Phase 3 design's §1.7, B1 (per-note {@code System.arraycopy} from
 * cached resampled source buffers) is the gather strategy and was measured at
 * {@code ~5 ms} per tick at 64 notes/m on Mac/macOS-aarch64 — well below the
 * realtime threshold.</p>
 *
 * <h2>Current integration status</h2>
 *
 * <p>The per-note producer factory used by
 * {@link RenderedNoteAudio#getProducer(int)} encapsulates the full
 * resample → filter envelope → volume envelope chain as one
 * {@link Producer}, so the underlying inputs ({@code [N, sourceLength]} source
 * buffers, {@code [N]} pitch ratios, {@code [N, ...]} per-row ADSR/envelope
 * tensors) are not yet surfaced at this dispatch boundary. Until that plumbing
 * lands (designated as a follow-on of the §5.8 / §1.7 Phase 3 work), this
 * renderer's {@code render} method falls back to the per-note evaluation path
 * while still exercising the bucket-cache lookup and the
 * {@link BatchedPatternRenderer} instantiation per bucket.</p>
 *
 * <p>When the per-note input plumbing lands, the fallback is replaced by a
 * single {@link BatchedPatternRenderer#buildBatchedChain} dispatch on the
 * gathered tensors — no signature changes at this dispatch boundary.</p>
 *
 * @see BatchedPatternRenderer
 * @see PatternLayerManager#enableBatched
 */
public final class BatchedPatternLayerRenderer {

	/**
	 * Note-count buckets used to share compiled batched kernels across ticks
	 * with variable N. Each tick's note count is ceiling-rounded to the next
	 * bucket and the unused rows pad with silent (zero) inputs.
	 */
	public static final int[] BUCKETS = {16, 32, 64, 128, 256, 512};

	/**
	 * Bucket-keyed cache of compiled batched renderers. The cache key is the
	 * bucket-N (one of {@link #BUCKETS}); a single renderer per bucket is
	 * shared across all ticks. Concurrent access is supported for future
	 * multi-thread render orchestration.
	 */
	private final ConcurrentMap<Integer, BatchedPatternRenderer> rendererCache =
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
		return rendererCache.computeIfAbsent(bucket, b ->
				new BatchedPatternRenderer(b, sourceLength, targetLength,
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
	 * <p>Bucket-cache priming: each render call counts the destination notes
	 * after the standard {@link PatternElement#getNoteDestinations} flatMap and
	 * forces creation of the bucket's compiled renderer. The actual
	 * {@link BatchedPatternRenderer#buildBatchedChain} dispatch is gated on
	 * the per-note input plumbing — see the class javadoc.</p>
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

		// Count overlapping notes (those that intersect [startFrame, endFrame)).
		int overlappingCount = 0;
		for (RenderedNoteAudio note : destinations) {
			int noteStart = note.getOffset();
			if (note.getExpectedFrameCount() > 0) {
				int noteEstimatedEnd = noteStart + note.getExpectedFrameCount();
				if (noteEstimatedEnd <= startFrame || noteStart >= endFrame) continue;
			} else if (noteStart >= endFrame) {
				continue;
			}
			overlappingCount++;
		}

		// Prime the bucket cache for this tick's N. The compiled renderer is
		// reused across ticks with the same bucket-N (no per-tick recompile).
		if (overlappingCount > 0) {
			rendererFor(bucketFor(overlappingCount));
		}

		// Fallback to the per-note path. This preserves acoustic equivalence
		// while the per-note input plumbing (source buffer, pitch ratio, ADSR
		// scalars) is in development. When that lands, this fallback is
		// replaced by a single buildBatchedChain dispatch on the gathered
		// [N, ...] tensors, with the destination accumulating the reduced
		// [targetLength] output at the tick-relative offsets via the existing
		// sum-to-destination mechanism.
		features.renderPerNote(sceneContext, audioContext, elements, melodic, offset,
				startFrame, frameCount, cache);
	}
}
