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

import org.almostrealism.audio.BatchedPatternRenderer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.notes.NoteAudioContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production dispatch site for the batched pattern rendering path.
 *
 * <p>This class is the batched dispatch boundary activated by the
 * {@code AR_PATTERN_BATCHED} feature flag (see
 * {@link PatternLayerManager#enableBatched}, on by default). For a single
 * {@code render} call it gathers the overlapping notes, classifies them, and
 * dispatches the batchable ones through {@link BatchedPatternRenderer}:</p>
 * <ul>
 *   <li><strong>Melodic SSS</strong> notes go through {@link #dispatchWindow}
 *       and {@link BatchedPatternRenderer#buildBatchedSssChainPlacedFromScalars}.</li>
 *   <li><strong>Percussion</strong> notes go through
 *       {@link #dispatchWindowPercussion} and
 *       {@link BatchedPatternRenderer#buildBatchedPercussionChainPlaced}.</li>
 * </ul>
 *
 * <p>Only continuing notes (within-note sampling offset {@code > 0}) and
 * non-batchable note shapes fall to the per-note path
 * ({@link PatternFeatures#renderPerNote}) by design.</p>
 *
 * <h2>Shared compiled kernel</h2>
 *
 * <p>The compiled batched kernel is fixed-shape at construction time. To share
 * one kernel across ticks, note counts are ceiling-rounded to one of
 * {@link #BUCKETS} = {@code {64, 128, 256, 512}} and per-dispatch source
 * lengths are rounded up to {@link #SOURCE_BUCKET}, so every tick reuses the
 * same compiled renderer (cached per resulting shape). Variable-N within a
 * bucket is handled by padding unused rows with silent (zero) inputs so they
 * contribute nothing to the accumulate-reduce kernel's output.</p>
 *
 * <h2>Gather and envelopes</h2>
 *
 * <p>Melodic gathers are memoized ({@link #gatherCache}) because their note
 * sources are stable raw sample references. Percussion is not cached — each
 * gather builds {@code fit()} source copies that are freed between ticks — so
 * percussion re-gathers fresh on every tick with a future-side window filter.
 * Per-row ADSR envelopes are generated in-kernel from {@code [N]} ADSR scalar
 * columns (filter and volume); there are no per-sample envelope inputs.</p>
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
	public static final int[] BUCKETS = {64, 128, 256, 512};

	/**
	 * Granularity to which the per-dispatch source length is rounded up so a small
	 * set of compiled kernels is shared across windows whose source requirements
	 * differ only slightly.
	 */
	public static final int SOURCE_BUCKET = 16384;

	/**
	 * Maximum per-dispatch render window in frames. A render call wider than this is
	 * split into consecutive sub-windows so the compiled kernel (whose intermediate
	 * buffers scale with {@code bucketN * windowWidth}) stays bounded regardless of
	 * the caller's frame count. The production real-time path renders one audio
	 * buffer at a time (well under this), so the split is a robustness bound for
	 * large offline render spans; it also drives note continuation across windows.
	 */
	public static final int MAX_WINDOW = 8192;

	/**
	 * Cache of compiled batched renderers keyed by {@code [bucketN, sourceLength,
	 * targetLength]}. The kernel is fixed-shape at construction, so a distinct
	 * renderer is compiled per note-count bucket, per source-length bucket, and per
	 * render-window width; each is shared across all ticks of the same shape.
	 * Concurrent access is supported for future multi-thread render orchestration.
	 */
	private final ConcurrentMap<List<Integer>, BatchedPatternRenderer> rendererCache =
			new ConcurrentHashMap<>();

	/**
	 * Identifies one melodic element's gathered note destinations within a cache epoch.
	 * Melodic gathers depend only on element, repetition offset, voicing and stereo channel
	 * (scale/automation geometry is constant within an epoch), and their note sources are
	 * stable raw sample references (no per-gather copy), so they are safe to memoize across
	 * ticks. Percussion is excluded — it builds per-gather {@code fit()} source copies that are
	 * freed between ticks, so caching them would dangle.
	 *
	 * @param element the source pattern element (identity-compared)
	 * @param offset  the repetition measure offset
	 * @param voicing the target voicing
	 * @param channel the target stereo channel
	 */
	private record GatherKey(PatternElement element, double offset,
							 ChannelInfo.Voicing voicing, ChannelInfo.StereoChannel channel) {
	}

	/**
	 * Memoized melodic gathered note destinations, valid within {@link #gatherEpoch}. Removes the
	 * dominant per-buffer host cost of re-running {@code getNoteDestinations} for every melodic
	 * element on every buffer of the same repetition. Cleared when the cache epoch advances
	 * (genome/arrangement swap) — the same staleness contract as the note-audio cache. Holds only
	 * metadata plus stable raw-sample references, never per-tick-freeable buffers.
	 */
	private final ConcurrentMap<GatherKey, List<RenderedNoteAudio>> gatherCache =
			new ConcurrentHashMap<>();

	/** The {@link PatternLayerManager#currentCacheEpoch()} value {@link #gatherCache} reflects. */
	private volatile int gatherEpoch = PatternLayerManager.currentCacheEpoch();

	/** Count of batched-kernel dispatches; instrumentation for the realtime gate. */
	public static final AtomicLong batchedDispatchCount = new AtomicLong();

	/** Count of fallbacks to the per-note path; must be 0 at production density when batched. */
	public static final AtomicLong fallbackCount = new AtomicLong();

	/** Cumulative host-side input-marshalling time (ns): buffer clear + source/scalar writes. */
	public static final AtomicLong marshalNanos = new AtomicLong();

	/** Cumulative device-side time (ns): cached-kernel re-evaluation plus the accumulate sum. */
	public static final AtomicLong evalNanos = new AtomicLong();

	/** Cumulative note-generation + per-note gather time (ns): {@code getNoteDestinations}. */
	public static final AtomicLong gatherNanos = new AtomicLong();

	/** Resets the dispatch instrumentation counters. */
	public static void resetCounters() {
		batchedDispatchCount.set(0);
		fallbackCount.set(0);
		marshalNanos.set(0);
		evalNanos.set(0);
		gatherNanos.set(0);
	}

	/** Finite placeholder note duration (seconds) for silent padded batch rows. */
	private static final double PAD_DURATION = 0.01;

	/** Audio sample rate in Hz. */
	private final int sampleRate;

	/** FIR filter order matching the production per-channel filter. */
	private final int filterOrder;

	/**
	 * Constructs a batched layer renderer. The per-note source length and the
	 * per-note row (target) length are not fixed here — they are sized per dispatch
	 * to the render window and the notes present, and compiled kernels are cached
	 * per resulting shape.
	 *
	 * @param sampleRate  audio sample rate in Hz
	 * @param filterOrder FIR filter order for the per-row lowpass kernel
	 */
	public BatchedPatternLayerRenderer(int sampleRate, int filterOrder) {
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
	 * Returns the cached {@link BatchedPatternRenderer} for the given shape, lazily
	 * compiling one if absent.
	 *
	 * @param bucket       the bucket-N (one of {@link #BUCKETS}, or larger if oversized)
	 * @param sourceLength per-note source buffer length (already source-bucketed)
	 * @param targetLength per-note row length (the render window width)
	 * @return the renderer compiled for that shape
	 */
	public BatchedPatternRenderer rendererFor(int bucket, int sourceLength, int targetLength) {
		return rendererCache.computeIfAbsent(List.of(bucket, sourceLength, targetLength), k ->
				new BatchedPatternRenderer(bucket, sourceLength, targetLength,
						sampleRate, filterOrder));
	}

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
	 * <p>Each render call gathers the destination notes via the standard
	 * {@link PatternElement#getNoteDestinations} flatMap, classifies the notes
	 * overlapping the window, and dispatches the batchable ones through the
	 * shared compiled kernel (melodic via
	 * {@link BatchedPatternRenderer#buildBatchedSssChainPlacedFromScalars},
	 * percussion via
	 * {@link BatchedPatternRenderer#buildBatchedPercussionChainPlaced}) — see
	 * the class javadoc.</p>
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

		long genStart = System.nanoTime();
		List<RenderedNoteAudio> destinations;
		if (melodic) {
			// Melodic note sources are stable raw sample references (resolveSourceAndRatio uses
			// wave.getChannelData directly, with no per-gather copy), so the gathered destinations
			// are safe to memoize across ticks within a cache epoch — removing the dominant
			// per-buffer gather cost on the dense melodic channels. Cleared when the cache epoch
			// advances (genome/arrangement swap), the same staleness contract as the note-audio cache.
			int epoch = PatternLayerManager.currentCacheEpoch();
			if (epoch != gatherEpoch) {
				gatherCache.clear();
				gatherEpoch = epoch;
			}
			ChannelInfo.Voicing voicing = audioContext.getVoicing();
			ChannelInfo.StereoChannel channel = audioContext.getAudioChannel();
			destinations = elements.stream()
					.map(e -> gatherCache.computeIfAbsent(
							new GatherKey(e, offset, voicing, channel),
							k -> e.getNoteDestinations(true, offset, sceneContext, audioContext)))
					.flatMap(List::stream)
					.toList();
		} else {
			// Percussion builds per-gather fit() source copies that are freed between ticks, so its
			// destinations cannot be cached. Re-gather fresh, skipping (future-side, provably safe)
			// elements whose earliest note begins at/after this window: an element's earliest note
			// is at measure (offset + getPosition()) and repeats only move later, so skipping it
			// cannot drop an overlapping note. The one-buffer margin absorbs frame rounding.
			long futureCutoff = (long) endFrame + frameCount;
			destinations = elements.stream()
					.filter(e -> sceneContext.frameForPosition(offset + e.getPosition()) < futureCutoff)
					.map(e -> e.getNoteDestinations(false, offset, sceneContext, audioContext))
					.flatMap(List::stream)
					.toList();
		}
		gatherNanos.addAndGet(System.nanoTime() - genStart);

		// Collect notes overlapping [startFrame, endFrame). The batched path can
		// dispatch when every overlapping note carries a melodic-SSS input record;
		// a note continuing from an earlier tick is rendered from its within-note
		// sampling offset (computed per note in dispatchBatched).
		List<RenderedNoteAudio> batchNow = new ArrayList<>();
		List<RenderedNoteAudio> perNote = new ArrayList<>();
		for (RenderedNoteAudio note : destinations) {
			int noteStart = note.getOffset();
			if (note.getExpectedFrameCount() > 0) {
				int noteEstimatedEnd = noteStart + note.getExpectedFrameCount();
				if (noteEstimatedEnd <= startFrame || noteStart >= endFrame) continue;
			} else if (noteStart >= endFrame) {
				continue;
			}
			// Batch only notes that START in this window (sampling offset == 0): their per-window
			// kernel shape is fixed, so the compiled kernel is reused across ticks. A note continuing
			// from an earlier window reads from a growing within-note offset; batching it bloats the
			// shared per-dispatch source length (every row pays the longest continuation's read),
			// which measured a net loss, so continuing notes are rendered per-note (once, then cached).
			if (note.getBatchedInputs() != null && noteStart >= startFrame) {
				batchNow.add(note);
			} else {
				perNote.add(note);
			}
		}

		if (!batchNow.isEmpty()) {
			dispatchBatched(features, batchNow, startFrame, frameCount, destination);
			batchedDispatchCount.incrementAndGet();
		}
		if (!perNote.isEmpty()) {
			fallbackCount.incrementAndGet();
			features.renderNotes(sceneContext, perNote, startFrame, frameCount, cache);
		}
	}

	/**
	 * Dispatches the batched melodic-SSS path for the given notes (all of which
	 * carry a {@link BatchedNoteInputs} record), splitting the render range into
	 * {@link #MAX_WINDOW}-bounded sub-windows so the compiled kernel size stays
	 * bounded regardless of {@code frameCount}. Each sub-window renders the notes
	 * overlapping it via {@link #dispatchWindow}; a note spanning a sub-window
	 * boundary is continued in the next sub-window from its advancing sampling
	 * offset.
	 *
	 * @param features    the features instance providing the evaluate boundary
	 * @param notes       the notes to render (size {@code >= 1})
	 * @param startFrame  the tick's absolute start frame
	 * @param frameCount  the tick's frame count (the full output window width)
	 * @param destination the per-tick destination buffer to accumulate into
	 */
	private void dispatchBatched(PatternFeatures features, List<RenderedNoteAudio> notes, int startFrame,
								 int frameCount, PackedCollection destination) {
		for (int ws = 0; ws < frameCount; ws += MAX_WINDOW) {
			int subWidth = Math.min(MAX_WINDOW, frameCount - ws);
			int subStart = startFrame + ws;
			int subEnd = subStart + subWidth;

			List<RenderedNoteAudio> sub = new ArrayList<>();
			for (RenderedNoteAudio note : notes) {
				int noteStart = note.getOffset();
				if (note.getExpectedFrameCount() > 0) {
					int noteEnd = noteStart + note.getExpectedFrameCount();
					if (noteEnd <= subStart || noteStart >= subEnd) continue;
				} else if (noteStart >= subEnd) {
					continue;
				}
				sub.add(note);
			}
			if (!sub.isEmpty()) {
				dispatchWindow(features, sub, subStart, subWidth, destination, ws);
			}
		}
	}

	/**
	 * Dispatches one bounded sub-window: builds the padded batch tensors for the
	 * notes overlapping {@code [windowStart, windowStart + windowWidth)}, runs the
	 * fused melodic-SSS kernel sized to this window, and accumulates the placed,
	 * summed output into {@code destination} starting at {@code destBaseOffset}.
	 *
	 * @param features      the features instance providing the evaluate boundary
	 * @param notes         the notes overlapping this sub-window (size {@code >= 1})
	 * @param windowStart   the sub-window's absolute start frame
	 * @param windowWidth   the sub-window's frame count (per-note row length)
	 * @param destination   the per-tick destination buffer to accumulate into
	 * @param destBaseOffset the frame offset into {@code destination} for this sub-window
	 */
	private void dispatchWindow(PatternFeatures features, List<RenderedNoteAudio> notes, int windowStart,
								int windowWidth, PackedCollection destination, int destBaseOffset) {
		// A channel is homogeneous (all melodic OR all percussion), so the first note's
		// kind classifies the whole window; percussion takes the strict-subset path.
		if (notes.get(0).getBatchedInputs().isPercussion()) {
			dispatchWindowPercussion(features, notes, windowStart, windowWidth, destination, destBaseOffset);
			return;
		}

		int count = notes.size();
		int bucketN = bucketFor(count);
		int layers = BatchedNoteInputs.LAYERS;
		int startFrame = windowStart;
		int targetLength = windowWidth;

		double[][] ratios = new double[layers][bucketN];
		double[][][] layerParams = new double[layers][8][bucketN];
		double[][] filterAdsr = new double[5][bucketN];
		double[][] volumeAdsr = new double[5][bucketN];
		double[] destOffsets = new double[bucketN];
		double[] samplingOffsets = new double[bucketN];

		// A note beginning in this window is placed at its in-window offset and read
		// from sample 0; a note continuing from an earlier window is placed at offset
		// 0 and read (and enveloped) from its within-note position, so output frame k
		// maps to absolute frame startFrame + k either way. Size the per-note source
		// row to hold the frames this window reads: samplingOffset + targetLength.
		int sourceLength = sourceLengthFor(notes, count, layers, startFrame, targetLength);

		// Compile-once: the renderer holds the compiled kernel and its bound input
		// buffers for this shape; per tick we write the gathered data into those
		// buffers and re-run the cached kernel (cp() rereads buffer memory), so no
		// kernel is rebuilt or recompiled per dispatch.
		BatchedPatternRenderer renderer = rendererFor(bucketN, sourceLength, targetLength);
		renderer.sssDispatch(layers);

		long marshalStart = System.nanoTime();

		// Finite placeholder scalars for all rows; real rows overwrite below.
		double[] layerDefaults = { PAD_DURATION, 0.3, 0.6, 1.0, 0.5, 0.5, 0.5, 0.5 };
		double[] adsrDefaults = { 0.002, 0.002, 0.5, 0.003, PAD_DURATION };
		for (int row = 0; row < bucketN; row++) {
			for (int l = 0; l < layers; l++) {
				ratios[l][row] = 1.0;
				for (int p = 0; p < 8; p++) layerParams[l][p][row] = layerDefaults[p];
			}
			for (int p = 0; p < 5; p++) {
				filterAdsr[p][row] = adsrDefaults[p];
				volumeAdsr[p][row] = adsrDefaults[p];
			}
		}

		// Zero the bound source buffers so padded (and previously-used) rows contribute
		// nothing, then copy each real note's source into its row. The clear spans the
		// full [bucketN, sourceLength] buffer deliberately: bucketN and sourceLength are
		// fixed for a cached renderer, so this clear compiles once and is reused every
		// tick. It is batched across all layers into one compile-once assignment
		// (renderer.clearSssSources) so the per-layer zeroing shares a single Metal
		// command-buffer commit instead of forcing one synchronous host wait per layer.
		PackedCollection[] boundSources = renderer.getSssSources();
		renderer.clearSssSources();
		for (int row = 0; row < count; row++) {
			BatchedNoteInputs in = notes.get(row).getBatchedInputs();
			for (int l = 0; l < layers; l++) {
				PackedCollection src = in.getSources()[l];
				copyRow(boundSources[l], row * sourceLength, src,
						Math.min(src.getMemLength(), sourceLength));
				ratios[l][row] = in.getRatios()[l];
				for (int p = 0; p < 8; p++) layerParams[l][p][row] = in.getLayerParams()[l][p];
			}
			for (int p = 0; p < 5; p++) {
				filterAdsr[p][row] = in.getFilterAdsr()[p];
				volumeAdsr[p][row] = in.getVolumeAdsr()[p];
			}
			int noteStart = notes.get(row).getOffset();
			destOffsets[row] = Math.max(0, noteStart - startFrame);
			samplingOffsets[row] = Math.max(0, startFrame - noteStart);
		}

		// Write the assembled per-note scalar columns into the kernel's bound buffers.
		PackedCollection[] boundRatios = renderer.getSssRatios();
		PackedCollection[][] boundLayerEnv = renderer.getSssLayerEnv();
		for (int l = 0; l < layers; l++) {
			writeColumn(boundRatios[l], ratios[l]);
			for (int p = 0; p < 8; p++) {
				writeColumn(boundLayerEnv[l][p], layerParams[l][p]);
			}
		}
		PackedCollection[] boundFilter = renderer.getSssFilterAdsr();
		PackedCollection[] boundVolume = renderer.getSssVolumeAdsr();
		for (int p = 0; p < 5; p++) {
			writeColumn(boundFilter[p], filterAdsr[p]);
			writeColumn(boundVolume[p], volumeAdsr[p]);
		}
		writeColumn(renderer.getSssDestOffsets(), destOffsets);
		writeColumn(renderer.getSssSamplingOffsets(), samplingOffsets);

		marshalNanos.addAndGet(System.nanoTime() - marshalStart);

		// The compiled kernel rereads the bound buffers; PatternFeatures re-evaluates
		// it at the pipeline boundary and accumulates the window into the destination.
		long evalStart = System.nanoTime();
		features.accumulateBatchedOutput(renderer.sssDispatch(layers),
				destination, destBaseOffset, targetLength);
		evalNanos.addAndGet(System.nanoTime() - evalStart);
	}

	/**
	 * Dispatches one bounded sub-window of percussion notes — the strict subset of
	 * {@link #dispatchWindow}. Percussion notes carry no per-layer envelope and no
	 * filter envelope, and a volume envelope only on the wet voicing, so this path
	 * marshals only per-layer sources, pitch ratios, destination/sampling offsets,
	 * and (when wet) the volume ADSR scalars into the renderer's percussion buffers,
	 * then runs the fused percussion kernel sized to this window and accumulates the
	 * placed, summed output into {@code destination}.
	 *
	 * @param features       the features instance providing the evaluate boundary
	 * @param notes          the notes overlapping this sub-window (size {@code >= 1})
	 * @param windowStart    the sub-window's absolute start frame
	 * @param windowWidth    the sub-window's frame count (per-note row length)
	 * @param destination    the per-tick destination buffer to accumulate into
	 * @param destBaseOffset the frame offset into {@code destination} for this sub-window
	 */
	private void dispatchWindowPercussion(PatternFeatures features, List<RenderedNoteAudio> notes, int windowStart,
										  int windowWidth, PackedCollection destination, int destBaseOffset) {
		int count = notes.size();
		int bucketN = bucketFor(count);
		int layers = BatchedNoteInputs.LAYERS;
		int startFrame = windowStart;
		int targetLength = windowWidth;
		boolean wet = notes.get(0).getBatchedInputs().isWet();

		double[][] ratios = new double[layers][bucketN];
		double[][] volumeAdsr = wet ? new double[5][bucketN] : null;
		double[] destOffsets = new double[bucketN];
		double[] samplingOffsets = new double[bucketN];

		int sourceLength = sourceLengthFor(notes, count, layers, startFrame, targetLength);

		BatchedPatternRenderer renderer = rendererFor(bucketN, sourceLength, targetLength);
		renderer.percDispatch(layers, wet);

		long marshalStart = System.nanoTime();

		// Finite placeholder scalars for all rows; real rows overwrite below. The dry
		// voicing has no volume envelope, so its rows need no defaults.
		double[] adsrDefaults = { 0.002, 0.002, 0.5, 0.003, PAD_DURATION };
		for (int row = 0; row < bucketN; row++) {
			for (int l = 0; l < layers; l++) ratios[l][row] = 1.0;
			if (wet) {
				for (int p = 0; p < 5; p++) volumeAdsr[p][row] = adsrDefaults[p];
			}
		}

		// Zero the bound source buffers (full fixed shape, compiles once) so padded and
		// previously-used rows contribute nothing, then copy each real note's source.
		// Batched across layers into one compile-once assignment (renderer.clearPercSources)
		// so the zeroing shares a single command-buffer commit instead of one host wait per layer.
		PackedCollection[] boundSources = renderer.getPercSources();
		renderer.clearPercSources();
		for (int row = 0; row < count; row++) {
			BatchedNoteInputs in = notes.get(row).getBatchedInputs();
			for (int l = 0; l < layers; l++) {
				PackedCollection src = in.getSources()[l];
				copyRow(boundSources[l], row * sourceLength, src,
						Math.min(src.getMemLength(), sourceLength));
				ratios[l][row] = in.getRatios()[l];
			}
			if (wet) {
				for (int p = 0; p < 5; p++) volumeAdsr[p][row] = in.getVolumeAdsr()[p];
			}
			int noteStart = notes.get(row).getOffset();
			destOffsets[row] = Math.max(0, noteStart - startFrame);
			samplingOffsets[row] = Math.max(0, startFrame - noteStart);
		}

		// Write the assembled per-note scalar columns into the kernel's bound buffers.
		PackedCollection[] boundRatios = renderer.getPercRatios();
		for (int l = 0; l < layers; l++) {
			writeColumn(boundRatios[l], ratios[l]);
		}
		if (wet) {
			PackedCollection[] boundVolume = renderer.getPercVolumeAdsr();
			for (int p = 0; p < 5; p++) {
				writeColumn(boundVolume[p], volumeAdsr[p]);
			}
		}
		writeColumn(renderer.getPercDestOffsets(), destOffsets);
		writeColumn(renderer.getPercSamplingOffsets(), samplingOffsets);

		marshalNanos.addAndGet(System.nanoTime() - marshalStart);

		long evalStart = System.nanoTime();
		features.accumulateBatchedOutput(renderer.percDispatch(layers, wet),
				destination, destBaseOffset, targetLength);
		evalNanos.addAndGet(System.nanoTime() - evalStart);
	}

	/**
	 * Computes the per-note source-row length for one dispatch: large enough to hold
	 * the highest raw-source frame any note's resample reads this window. The kernel
	 * reads raw source position {@code (samplingOffset + sampleIdx) * ratio}, so the
	 * highest read is {@code ceil((samplingOffset + targetLength) * ratio) + 1} (the
	 * {@code +1} covering the linear-interpolation neighbor). Reads past a note's
	 * actual raw length land in the cleared (zero) tail of the row. The result is
	 * rounded up to {@link #SOURCE_BUCKET} so kernels are shared across similar
	 * windows.
	 *
	 * @param notes        the notes being dispatched
	 * @param count        the number of real (non-padded) notes
	 * @param layers       the source layers per note
	 * @param startFrame   the window's absolute start frame
	 * @param targetLength the per-note row length (window width)
	 * @return the source-bucketed per-note source length
	 */
	private static int sourceLengthFor(List<RenderedNoteAudio> notes, int count, int layers,
									   int startFrame, int targetLength) {
		int required = targetLength + 1;
		for (int row = 0; row < count; row++) {
			int samplingOffset = Math.max(0, startFrame - notes.get(row).getOffset());
			BatchedNoteInputs in = notes.get(row).getBatchedInputs();
			for (int l = 0; l < layers; l++) {
				int read = (int) Math.ceil((samplingOffset + targetLength) * in.getRatios()[l]) + 1;
				required = Math.max(required, read);
			}
		}
		return ((required + SOURCE_BUCKET - 1) / SOURCE_BUCKET) * SOURCE_BUCKET;
	}

	/**
	 * Writes a per-note scalar column into a bound kernel-input buffer. This is a
	 * bulk host-to-collection marshalling copy assembling the batch input, not
	 * element-wise host computation.
	 *
	 * @param dest   the bound {@code [n]} kernel-input buffer
	 * @param values the per-note scalar values to write
	 */
	private void writeColumn(PackedCollection dest, double[] values) {
		dest.setMem(values);
	}

	/**
	 * Copies a per-note source buffer into one row of the batched source buffer.
	 * This is a bulk collection-to-collection marshalling copy assembling the
	 * batch input, not element-wise host computation.
	 *
	 * @param dest        the batched {@code [bucketN, sourceLength]} source buffer
	 * @param frameOffset the flat destination offset ({@code row * sourceLength})
	 * @param src         the note's source buffer of length {@code sourceLength}
	 * @param length      the number of samples to copy
	 */
	private void copyRow(PackedCollection dest, int frameOffset, PackedCollection src, int length) {
		dest.setMem(frameOffset, src, 0, length);
	}
}
