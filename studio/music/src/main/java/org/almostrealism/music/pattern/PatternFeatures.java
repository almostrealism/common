package org.almostrealism.music.pattern;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.audio.filter.AudioProcessingUtils;
import org.almostrealism.music.notes.NoteAudioContext;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperatorPoolExhaustedException;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.io.DistributionMetric;

import java.util.ArrayList;
import java.util.List;

/**
 * Interface providing core pattern rendering functionality.
 *
 * <p>{@code PatternFeatures} defines the {@link #render} method that is the heart
 * of the pattern audio generation system. This method converts pattern elements
 * into audio and sums them to a destination buffer.</p>
 *
 * <h2>Render Path</h2>
 *
 * <p>Each {@link RenderedNoteAudio} carries a producer factory that creates
 * frame-count-specific producers via {@link RenderedNoteAudio#getProducer(int)}.
 * The caller sets the start frame offset in the note's {@code offsetArg} before
 * evaluation. This is the single render path for both offline and real-time modes.</p>
 *
 * <h2>Optimizations</h2>
 * <ul>
 *   <li><strong>Pre-filtering:</strong> Notes with a known {@code expectedFrameCount}
 *       are skipped before the expensive {@code evaluate()} call if they do not
 *       overlap with the target frame range.</li>
 *   <li><strong>Caching:</strong> When a {@link NoteAudioCache} is provided, evaluated
 *       note audio is cached and reused across consecutive buffer ticks. Notes that
 *       span multiple buffers are evaluated only once.</li>
 *   <li><strong>Signature independence:</strong> The computation signature is independent
 *       of the start frame position (the offset is a runtime data argument), so the
 *       compiled kernel is reused across different frame positions.</li>
 * </ul>
 *
 * @see PatternElement#getNoteDestinations
 * @see RenderedNoteAudio
 * @see PatternLayerManager#sum
 * @see AudioProcessingUtils#getSum()
 * @see NoteAudioCache
 *
 * @author Michael Murray
 */
public interface PatternFeatures extends CodeFeatures {
	/** Distribution metric tracking the sizes of rendered pattern audio segments. */
	DistributionMetric sizes = CellFeatures.console.distribution("patternSizes");

	/**
	 * Maximum number of consecutive note-evaluation failures tolerated by
	 * {@link #renderPerNote} before it aborts the render with an
	 * {@link IllegalStateException}. Beyond this threshold every additional
	 * attempt is wasted work and the warning stream drowns useful diagnostics.
	 */
	int MAX_CONSECUTIVE_NOTE_FAILURES = 5;

	/**
	 * Renders pattern elements to a destination buffer for a specific frame range.
	 *
	 * <p>Dispatches to one of two rendering paths based on the
	 * {@link PatternLayerManager#enableBatched} feature flag
	 * ({@code AR_PATTERN_BATCHED}):</p>
	 * <ul>
	 *   <li><strong>Batched path</strong> (flag on, default): the integration via
	 *       {@link BatchedPatternLayerRenderer#render}. Per-tick gathers notes
	 *       into bucket-N tensors and dispatches through
	 *       {@link org.almostrealism.audio.BatchedPatternRenderer}. Non-batchable
	 *       note shapes and continuing notes fall back to the per-note path by
	 *       design.</li>
	 *   <li><strong>Per-note path</strong> (flag off): the legacy
	 *       sequential per-note dispatch via {@link #renderPerNote}. One
	 *       {@code evaluate()} per note; the existing acoustic baseline.</li>
	 * </ul>
	 *
	 * <p>See {@link #renderPerNote} for the per-note rendering semantics.</p>
	 *
	 * @param sceneContext scene context containing destination buffer
	 * @param audioContext note audio context
	 * @param elements elements to render
	 * @param melodic whether to use melodic or percussive rendering
	 * @param offset measure offset for pattern positioning
	 * @param startFrame starting frame of the target range (absolute position)
	 * @param frameCount number of frames in the target range
	 * @param cache optional cache for evaluated note audio (may be null)
	 */
	default void render(AudioSceneContext sceneContext, NoteAudioContext audioContext,
						List<PatternElement> elements, boolean melodic, double offset,
						int startFrame, int frameCount, NoteAudioCache cache) {
		if (PatternLayerManager.enableBatched) {
			BatchedPatternLayerRenderer batchedRenderer = getBatchedLayerRenderer();
			if (batchedRenderer != null) {
				batchedRenderer.render(this, sceneContext, audioContext, elements,
						melodic, offset, startFrame, frameCount, cache);
				return;
			}
		}

		renderPerNote(sceneContext, audioContext, elements, melodic, offset,
				startFrame, frameCount, cache);
	}

	/**
	 * Returns the {@link BatchedPatternLayerRenderer} associated with this
	 * features instance, or {@code null} when no batched dispatch site is
	 * available.
	 *
	 * <p>Implementations that participate in the batched rendering path
	 * (currently {@link PatternLayerManager}) return their per-pattern bucket
	 * cache so the {@code AR_PATTERN_BATCHED} flag can route through it. The
	 * default implementation returns {@code null}, which keeps the
	 * per-note path active for arbitrary {@link PatternFeatures} usages outside
	 * the production pattern-layer pipeline.</p>
	 *
	 * @return the batched renderer for this features instance, or {@code null}
	 *         if the per-note path should be used
	 */
	default BatchedPatternLayerRenderer getBatchedLayerRenderer() {
		return null;
	}

	/**
	 * Evaluates a batched render output producer and accumulates it into the
	 * destination buffer. This is the pipeline boundary where the batched pattern
	 * dispatch built by {@link BatchedPatternLayerRenderer} is materialized — kept
	 * here so the batched path shares the per-note path's evaluate boundary.
	 *
	 * @param output      the placed, summed window producer of shape {@code [frameCount]}
	 * @param destination the per-tick destination buffer to accumulate into
	 * @param frameCount  the number of frames in the output window
	 */
	default void accumulateBatchedOutput(Producer<PackedCollection> output,
										 PackedCollection destination, int frameCount) {
		accumulateBatchedOutput(output, destination, 0, frameCount);
	}

	/**
	 * Evaluates a batched render output producer and accumulates it into the
	 * destination buffer starting at {@code destOffset}. Used by the dispatch when a
	 * large render window is split into bounded sub-windows that each accumulate into
	 * their slice of the destination.
	 *
	 * @param output      the placed, summed window producer of shape {@code [frameCount]}
	 * @param destination the per-tick destination buffer to accumulate into
	 * @param destOffset  the frame offset into {@code destination} to accumulate at
	 * @param frameCount  the number of frames in the output window
	 */
	default void accumulateBatchedOutput(Producer<PackedCollection> output,
										 PackedCollection destination, int destOffset, int frameCount) {
		accumulateBatchedOutput(output.get().evaluate(), destination, destOffset, frameCount);
	}

	/**
	 * Accumulates an already-evaluated batched render window into the destination
	 * buffer starting at {@code destOffset}. This is the boundary used by the
	 * compile-once dispatch, which re-evaluates its cached kernel to a
	 * {@link PackedCollection} and accumulates it directly (no per-tick producer
	 * compilation).
	 *
	 * @param output      the placed, summed window of shape {@code [frameCount]}
	 * @param destination the per-tick destination buffer to accumulate into
	 * @param destOffset  the frame offset into {@code destination} to accumulate at
	 * @param frameCount  the number of frames in the output window
	 */
	default void accumulateBatchedOutput(PackedCollection output,
										 PackedCollection destination, int destOffset, int frameCount) {
		AudioProcessingUtils.getSum().sum(
				destination.range(new TraversalPolicy(frameCount), destOffset), output);
	}

	/**
	 * Re-evaluates a compiled batched dispatch and accumulates its output window
	 * into the destination at {@code destOffset}. The dispatch is evaluated here, at
	 * the pipeline boundary, so the compile-once kernel is reused each tick without
	 * per-tick recompilation.
	 *
	 * @param dispatch    the compiled, reusable dispatch evaluable
	 * @param destination the per-tick destination buffer to accumulate into
	 * @param destOffset  the frame offset into {@code destination} to accumulate at
	 * @param frameCount  the number of frames in the output window
	 */
	default void accumulateBatchedOutput(Evaluable<PackedCollection> dispatch,
										 PackedCollection destination, int destOffset, int frameCount) {
		accumulateBatchedOutput(dispatch.evaluate(), destination, destOffset, frameCount);
	}

	/**
	 * Renders pattern elements using the legacy per-note dispatch path.
	 *
	 * <p>This is the original rendering implementation shared by both offline and
	 * real-time modes. For each element, it:</p>
	 * <ol>
	 *   <li>Creates {@link RenderedNoteAudio} instances via
	 *       {@link PatternElement#getNoteDestinations}</li>
	 *   <li>Pre-filters notes by {@code expectedFrameCount} to skip notes that
	 *       cannot overlap the frame range (avoids expensive {@code evaluate()})</li>
	 *   <li>Checks the {@link NoteAudioCache} for previously evaluated audio</li>
	 *   <li>Evaluates the note via {@link RenderedNoteAudio#getProducer(int)} for
	 *       the overlap frame count</li>
	 *   <li>Computes the overlap region and sums audio to the destination buffer</li>
	 * </ol>
	 *
	 * <h3>Offset Calculations</h3>
	 * <ul>
	 *   <li><strong>noteStart</strong>: The absolute frame position where the note begins</li>
	 *   <li><strong>overlapStart</strong>: {@code max(noteStart, startFrame)}</li>
	 *   <li><strong>sourceOffset</strong>: {@code overlapStart - noteStart} (frames into note audio)</li>
	 *   <li><strong>destOffset</strong>: {@code overlapStart - startFrame} (position in destination)</li>
	 * </ul>
	 *
	 * @param sceneContext scene context containing destination buffer
	 * @param audioContext note audio context
	 * @param elements elements to render
	 * @param melodic whether to use melodic or percussive rendering
	 * @param offset measure offset for pattern positioning
	 * @param startFrame starting frame of the target range (absolute position)
	 * @param frameCount number of frames in the target range
	 * @param cache optional cache for evaluated note audio (may be null)
	 */
	default void renderPerNote(AudioSceneContext sceneContext, NoteAudioContext audioContext,
							   List<PatternElement> elements, boolean melodic, double offset,
							   int startFrame, int frameCount, NoteAudioCache cache) {
		List<RenderedNoteAudio> notes = new ArrayList<>();
		for (PatternElement element : elements) {
			notes.addAll(element.getNoteDestinations(melodic, offset, sceneContext, audioContext));
		}
		renderNotes(sceneContext, notes, startFrame, frameCount, cache);
	}

	/**
	 * Renders a specific list of {@link RenderedNoteAudio} into the scene destination via the
	 * per-note dispatch path. Shared by {@link #renderPerNote} (which gathers every element's
	 * notes) and the batched dispatch site (which passes only the notes it cannot batch — those
	 * not of a batchable shape, or continuing from an earlier window), so those notes render
	 * per-note without falling the whole window back. The per-note body below is unchanged from
	 * the original {@code renderPerNote} loop.
	 *
	 * @param sceneContext scene context containing the destination buffer
	 * @param notes        the notes to render
	 * @param startFrame   starting frame of the target range (absolute position)
	 * @param frameCount   number of frames in the target range
	 * @param cache        optional cache for evaluated note audio (may be null)
	 */
	default void renderNotes(AudioSceneContext sceneContext, List<RenderedNoteAudio> notes,
							 int startFrame, int frameCount, NoteAudioCache cache) {
		PackedCollection destination = sceneContext.getDestination();
		if (destination == null) {
			throw new IllegalArgumentException("Destination buffer is null");
		}

		int endFrame = startFrame + frameCount;
		int[] consecutiveFailures = {0};

		notes.forEach(note -> {
					int noteStart = note.getOffset();

					// Pre-filter: skip notes that cannot overlap the frame range
					if (note.getExpectedFrameCount() > 0) {
						int noteEstimatedEnd = noteStart + note.getExpectedFrameCount();
						if (noteEstimatedEnd <= startFrame || noteStart >= endFrame) {
							return;
						}
					} else if (noteStart >= endFrame) {
						return;
					}

					// Check cache first (fastest path for real-time rendering)
					PackedCollection audio = (cache != null) ? cache.get(noteStart) : null;

					if (audio != null) {
						// Cache hit: sum cached audio to destination
						sumToDestination(destination, audio, noteStart, startFrame,
								endFrame, frameCount);
						consecutiveFailures[0] = 0;
						return;
					}

					// When a cache is available, evaluate the full note so future
					// buffer ticks get a cache hit.  Use getProducer(-1) so all
					// notes share the same compilation signature regardless of
					// duration (frameCount <= 0 signals null offset in the factory).
					// This avoids per-note compilation that would occur if each
					// note's expectedFrameCount were used as a structural parameter.
					if (cache != null) {
						try {
							PackedCollection[] fullResult = {null};
							Heap.stage(() -> {
								Producer<PackedCollection> fullProducer =
										note.getProducer(-1);
								PackedCollection evaluated =
										traverse(1, fullProducer).get().evaluate();
								if (evaluated != null) {
									// Copy the rendered note audio into a fresh standalone
									// PackedCollection (PackedCollection does not allocate
									// from the Heap arena, and a plain construction is not
									// registered with the active stage) so it survives the
									// stage pop that frees the per-evaluation intermediates
									// — including the evaluated result itself. The cache owns
									// this copy and destroys it on eviction. Copying (rather
									// than detaching the evaluated result) avoids any chance
									// of a double free between the cache and the Heap stage.
									fullResult[0] = new PackedCollection(evaluated.getShape());
									fullResult[0].setMem(0, evaluated);
									cache.put(noteStart, fullResult[0]);
									sumToDestination(destination, fullResult[0], noteStart,
											startFrame, endFrame, frameCount);
								}
							});
							consecutiveFailures[0] = 0;
						} catch (Exception e) {
							consecutiveFailures[0]++;
							handleNoteEvaluationFailure(noteStart, consecutiveFailures[0], e);
						}
					} else {
						if (note.getExpectedFrameCount() <= 0
								&& endFrame - noteStart <= 0) {
							return;
						}

						try {
							Heap.stage(() -> {
								Producer<PackedCollection> producer = note.getProducer(-1);
								PackedCollection evalResult = traverse(1, producer).get().evaluate();
								if (evalResult != null) {
									sumToDestination(destination, evalResult, noteStart,
											startFrame, endFrame, frameCount);
								}
							});
							consecutiveFailures[0] = 0;
						} catch (Exception e) {
							consecutiveFailures[0]++;
							handleNoteEvaluationFailure(noteStart, consecutiveFailures[0], e);
						}
					}
				});
	}

	/**
	 * Reacts to a single failed note evaluation. Logs the failure as a warning,
	 * and escalates to {@link IllegalStateException} when the failure is either
	 * (a) an exhaustion-class condition — any {@link OperatorPoolExhaustedException}
	 * in the cause chain — or (b) the
	 * {@value #MAX_CONSECUTIVE_NOTE_FAILURES}<sup>th</sup> consecutive failure
	 * since the most recent successful evaluation. Either condition indicates
	 * that further attempts are guaranteed to fail and the render must stop
	 * promptly with a single, diagnosable exception instead of thousands of
	 * warnings.
	 *
	 * @param noteStart           the frame offset of the note that failed
	 * @param consecutiveFailures running count of failures since the last success
	 * @param cause               the exception thrown by the evaluation
	 */
	private void handleNoteEvaluationFailure(int noteStart, int consecutiveFailures,
											 Exception cause) {
		warn("Note evaluation failed at frame " + noteStart + ": " + cause.getMessage());

		if (isExhaustionFailure(cause)) {
			throw new IllegalStateException(
					"Pattern rendering aborted at frame " + noteStart
							+ " — runtime native-lib template class pool exhausted"
							+ " (consecutiveFailures=" + consecutiveFailures
							+ ", causeClass=" + cause.getClass().getName() + ")",
					cause);
		}

		if (consecutiveFailures >= MAX_CONSECUTIVE_NOTE_FAILURES) {
			throw new IllegalStateException(
					"Pattern rendering aborted at frame " + noteStart
							+ " after " + consecutiveFailures
							+ " consecutive note-evaluation failures"
							+ " (causeClass=" + cause.getClass().getName() + ")",
					cause);
		}
	}

	/**
	 * Walks the cause chain of the given throwable and returns {@code true} if
	 * any link is an {@link OperatorPoolExhaustedException}.
	 *
	 * @param t the throwable to inspect
	 * @return {@code true} when the chain contains an operator-pool exhaustion cause
	 */
	private static boolean isExhaustionFailure(Throwable t) {
		Throwable cursor = t;
		while (cursor != null) {
			if (cursor instanceof OperatorPoolExhaustedException) {
				return true;
			}
			cursor = cursor.getCause();
		}
		return false;
	}

	/**
	 * Sums cached or fully-evaluated note audio to the destination buffer,
	 * computing the overlap region between the note and the target frame range.
	 *
	 * @param destination buffer to sum audio into
	 * @param audio the fully-evaluated note audio
	 * @param noteStart absolute frame position where the note begins
	 * @param startFrame starting frame of the target range
	 * @param endFrame ending frame of the target range (exclusive)
	 * @param frameCount length of the target range ({@code endFrame - startFrame})
	 */
	private void sumToDestination(PackedCollection destination, PackedCollection audio,
								  int noteStart, int startFrame, int endFrame, int frameCount) {
		int noteLength = audio.getShape().getCount();
		int noteAbsoluteEnd = noteStart + noteLength;

		if (noteAbsoluteEnd <= startFrame || noteStart >= endFrame) return;

		int overlapStart = Math.max(noteStart, startFrame);
		int overlapEnd = Math.min(noteAbsoluteEnd, endFrame);
		int overlapLength = overlapEnd - overlapStart;

		if (overlapLength <= 0) return;

		int sourceOffset = overlapStart - noteStart;
		int destOffset = overlapStart - startFrame;

		if (sourceOffset < 0 || sourceOffset + overlapLength > noteLength) return;
		if (destOffset < 0 || destOffset + overlapLength > frameCount) return;

		try {
			TraversalPolicy shape = shape(overlapLength);
			sizes.addEntry(overlapLength);
			AudioProcessingUtils.getSum().sum(
					destination.range(shape, destOffset),
					audio.range(shape, sourceOffset));
		} catch (Exception e) {
			warn("Note summation failed at offset " + sourceOffset + ": " + e.getMessage());
		}
	}
}
