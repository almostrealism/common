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
	 *   <li><strong>Per-note path</strong> (flag off, default): the legacy
	 *       sequential per-note dispatch via {@link #renderPerNote}. One
	 *       {@code evaluate()} per note; the existing acoustic baseline.</li>
	 *   <li><strong>Batched path</strong> (flag on): the Phase 3 integration via
	 *       {@link BatchedPatternLayerRenderer#render}. Per-tick gathers notes
	 *       into bucket-N tensors and dispatches through
	 *       {@link org.almostrealism.audio.BatchedPatternRenderer}. The batched
	 *       path falls back to the per-note path for cases its input gather
	 *       cannot yet handle.</li>
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
	 * Dispatches the Phase 3 four-kernel batched chain for a single
	 * start-frame-aligned group of notes and sums the reduced
	 * {@code [targetLength]} output into the destination buffer.
	 *
	 * <p>The {@code .evaluate()} call here is the pipeline boundary for the
	 * batched dispatch (one compiled-kernel dispatch per note group per tick).
	 * It lives on {@link PatternFeatures} so the per-note and per-group
	 * dispatch paths share the same "evaluate at pipeline boundary" idiom.
	 * The {@link BatchedPatternLayerRenderer}'s per-bucket cache supplies the
	 * compiled {@link Evaluable} so this method does not rebuild the producer
	 * graph or recompile the kernel on every dispatch.</p>
	 *
	 * @param compiledChain   compiled batched-chain {@link Evaluable} from the
	 *                        per-bucket cache
	 * @param destination     destination buffer for the summed output
	 * @param groupStartFrame absolute frame at which the group's notes start
	 * @param tickStartFrame  absolute start frame of the current tick range
	 * @param tickEndFrame    absolute end frame of the current tick range (exclusive)
	 * @param tickFrameCount  {@code tickEndFrame - tickStartFrame}
	 */
	default void dispatchBatchedGroup(Evaluable<PackedCollection> compiledChain,
									  PackedCollection destination,
									  int groupStartFrame,
									  int tickStartFrame,
									  int tickEndFrame,
									  int tickFrameCount) {
		PackedCollection reduced = compiledChain.evaluate();
		sumToDestination(destination, reduced, groupStartFrame,
				tickStartFrame, tickEndFrame, tickFrameCount);
	}

	/**
	 * Materialises a {@link Producer} to its {@link PackedCollection} value.
	 * Used by the Phase 3 batched dispatch population path
	 * ({@link ScaleTraversalStrategy} per-note construction) to extract the
	 * leaf source audio and the scalar automation level from the producer
	 * chain without putting the {@code .evaluate()} literal in a
	 * non-whitelisted source file.
	 *
	 * <p>The expected callers are:</p>
	 * <ul>
	 *   <li>The leaf audio source producer from
	 *       {@code PatternNoteAudio.getAudio(target, channel, audioSelection)},
	 *       whose underlying
	 *       {@link org.almostrealism.audio.notes.NoteAudioProvider}
	 *       {@code audioCache} returns the same {@link PackedCollection}
	 *       instance on every call. The first {@code .evaluate()} computes the
	 *       resampled audio; subsequent calls are cache hits.</li>
	 *   <li>The automation-level scalar producer
	 *       {@code automationLevel.getResultant(c(0.0))}, used to read the
	 *       automation level at note start-time. The caller extracts the scalar
	 *       via {@link PackedCollection#toDouble(int)} in a
	 *       {@code .toDouble}-whitelisted file.</li>
	 * </ul>
	 *
	 * @param producer the producer to evaluate
	 * @return the materialised {@link PackedCollection}, or {@code null} when
	 *         {@code producer} is {@code null}
	 */
	static PackedCollection materialiseProducer(Producer<PackedCollection> producer) {
		if (producer == null) return null;
		return producer.get().evaluate();
	}

	/**
	 * Returns the {@link BatchedPatternLayerRenderer} associated with this
	 * features instance, or {@code null} when no batched dispatch site is
	 * available.
	 *
	 * <p>Implementations that participate in the Phase 3 batched rendering path
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
		PackedCollection destination = sceneContext.getDestination();
		if (destination == null) {
			throw new IllegalArgumentException("Destination buffer is null");
		}

		int endFrame = startFrame + frameCount;
		int[] consecutiveFailures = {0};

		elements.stream()
				.map(e -> e.getNoteDestinations(melodic, offset, sceneContext, audioContext))
				.flatMap(List::stream)
				.forEach(note -> {
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
								fullResult[0] =
										traverse(1, fullProducer).get().evaluate();
								if (fullResult[0] != null) {
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
