package org.almostrealism.audio.pattern;

import io.almostrealism.collect.TraversalPolicy;
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
 * <h2>Unified Render Path</h2>
 *
 * <p>A single {@link #render} method handles both full-buffer (offline) and
 * frame-range (real-time) rendering. The {@code startFrame} and {@code frameCount}
 * parameters define which portion of the arrangement to render:</p>
 * <ul>
 *   <li><strong>Full render:</strong> {@code startFrame=0, frameCount=destination.length}</li>
 *   <li><strong>Real-time buffer:</strong> {@code startFrame=currentPosition, frameCount=bufferSize}</li>
 * </ul>
 *
 * <h2>Optimizations</h2>
 * <ul>
 *   <li><strong>Pre-filtering:</strong> Notes with a known {@code expectedFrameCount}
 *       are skipped before the expensive {@code evaluate()} call if they do not
 *       overlap with the target frame range.</li>
 *   <li><strong>Caching:</strong> When a {@link NoteAudioCache} is provided, evaluated
 *       note audio is cached and reused across consecutive buffer ticks. Notes that
 *       span multiple buffers are evaluated only once.</li>
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
	 *   <li>Evaluates the note's audio producer if not cached</li>
	 *   <li>Computes the overlap region and sums audio to the destination buffer</li>
	 * </ol>
	 *
	 * <h3>Full-buffer rendering</h3>
	 * <p>For offline rendering of the entire arrangement, pass {@code startFrame=0}
	 * and {@code frameCount=destination.getShape().length(0)}. The overlap logic
	 * degenerates to simple clipping at the buffer end, matching the original
	 * full-render behavior.</p>
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

					// Check cache before expensive evaluate()
					PackedCollection audio = (cache != null) ? cache.get(noteStart) : null;

					if (audio == null) {
						PackedCollection[] evaluated = {null};
						try {
							Heap.stage(() ->
									evaluated[0] = traverse(1, note.getProducer()).get().evaluate());
						} catch (Exception e) {
							return;
						}

						audio = evaluated[0];
						if (audio == null) return;

						// Store in cache for reuse across buffer ticks
						if (cache != null) {
							cache.put(noteStart, audio);
						}
					}

					int noteLength = audio.getShape().getCount();
					int noteAbsoluteEnd = noteStart + noteLength;

					// Post-evaluate overlap check (safety net for inaccurate estimates)
					if (noteAbsoluteEnd <= startFrame || noteStart >= endFrame) {
						return;
					}

					// Calculate overlap region
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
						// Skip notes that fail during summation
					}
				});
	}
}
