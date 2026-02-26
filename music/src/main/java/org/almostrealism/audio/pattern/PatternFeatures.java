package org.almostrealism.audio.pattern;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.arrange.AudioSceneContext;
import org.almostrealism.audio.filter.AudioProcessingUtils;
import org.almostrealism.audio.notes.NoteAudioContext;
import org.almostrealism.collect.PackedCollection;
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
	DistributionMetric sizes = CellFeatures.console.distribution("patternSizes");

	/**
	 * Renders pattern elements to a destination buffer for a specific frame range.
	 *
	 * <p>This is the single render path shared by both offline and real-time modes.
	 * For each element, it:</p>
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
	default void render(AudioSceneContext sceneContext, NoteAudioContext audioContext,
						List<PatternElement> elements, boolean melodic, double offset,
						int startFrame, int frameCount, NoteAudioCache cache) {
		PackedCollection destination = sceneContext.getDestination();
		if (destination == null) {
			throw new IllegalArgumentException("Destination buffer is null");
		}

		int endFrame = startFrame + frameCount;

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
						return;
					}

					// When a cache is available, evaluate the full note so future
					// buffer ticks get a cache hit. Without a cache, evaluate only
					// the overlap region for minimal work.
					if (cache != null) {
						note.getOffsetArg().setMem(0, 0);
						try {
							PackedCollection[] fullResult = {null};
							Heap.stage(() -> {
								Producer<PackedCollection> fullProducer =
										note.getProducer(note.getExpectedFrameCount());
								fullResult[0] =
										traverse(1, fullProducer).get().evaluate();
							});
							if (fullResult[0] != null) {
								cache.put(noteStart, fullResult[0]);
								sumToDestination(destination, fullResult[0], noteStart,
										startFrame, endFrame, frameCount);
							}
						} catch (Exception e) {
							warn("Note evaluation failed at frame " + noteStart + ": " + e.getMessage());
						}
					} else {
						int overlapStart = Math.max(noteStart, startFrame);
						int overlapEnd = (note.getExpectedFrameCount() > 0)
								? Math.min(noteStart + note.getExpectedFrameCount(), endFrame)
								: endFrame;
						int overlapLength = overlapEnd - overlapStart;
						int sourceOffset = overlapStart - noteStart;
						int destOffset = overlapStart - startFrame;

						if (overlapLength <= 0) return;

						note.getOffsetArg().setMem(0, sourceOffset);

						try {
							PackedCollection[] evalResult = {null};
							Heap.stage(() -> {
								Producer<PackedCollection> producer = note.getProducer(overlapLength);
								evalResult[0] = traverse(1, producer).get().evaluate();
							});

							if (evalResult[0] == null) return;

							int actualLen = Math.min(overlapLength,
									evalResult[0].getShape().getCount());
							if (actualLen > 0 && destOffset >= 0
									&& destOffset + actualLen <= frameCount) {
								TraversalPolicy shape = shape(actualLen);
								sizes.addEntry(actualLen);
								AudioProcessingUtils.getSum().sum(
										destination.range(shape, destOffset),
										evalResult[0].range(shape, 0));
							}
						} catch (Exception e) {
							warn("Partial note evaluation failed at frame " + noteStart + ": " + e.getMessage());
						}
					}
				});
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
