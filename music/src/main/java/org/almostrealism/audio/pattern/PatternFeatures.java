package org.almostrealism.audio.pattern;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.arrange.AudioSceneContext;
import org.almostrealism.audio.arrange.PatternRenderContext;
import org.almostrealism.audio.filter.AudioProcessingUtils;
import org.almostrealism.audio.notes.NoteAudioContext;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.io.DistributionMetric;

import java.util.List;
import java.util.function.Function;

/**
 * Interface providing core pattern rendering functionality.
 *
 * <p>{@code PatternFeatures} defines the {@link #render} method that is the heart
 * of the pattern audio generation system. This method converts pattern elements
 * into audio and sums them to a destination buffer.</p>
 *
 * <h2>Rendering Process</h2>
 *
 * <p>The {@link #render} method:</p>
 * <ol>
 *   <li>Iterates through all provided pattern elements</li>
 *   <li>Calls {@link PatternElement#getNoteDestinations} to convert each element
 *       to {@link RenderedNoteAudio} instances with frame offsets</li>
 *   <li>For each rendered note:
 *     <ul>
 *       <li>Evaluates the audio producer inside {@link Heap#stage}</li>
 *       <li>Sums the audio to the destination buffer at the note's offset</li>
 *       <li>Clips audio that extends beyond the destination buffer</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h2>Real-Time Rendering</h2>
 *
 * <p>For real-time streaming, use the {@link #renderRange} method which:</p>
 * <ul>
 *   <li>Filters elements to only those intersecting the current buffer</li>
 *   <li>Converts absolute offsets to buffer-relative offsets</li>
 *   <li>Handles notes that started in previous buffers (partial rendering)</li>
 *   <li>Bypasses {@link Heap#stage} for lower latency</li>
 * </ul>
 *
 * @see PatternElement#getNoteDestinations
 * @see RenderedNoteAudio
 * @see PatternLayerManager#sum
 * @see AudioProcessingUtils#getSum()
 *
 * @author Michael Murray
 */
public interface PatternFeatures extends CodeFeatures {
	DistributionMetric sizes = CellFeatures.console.distribution("patternSizes");

	/**
	 * Renders all pattern elements to the destination buffer.
	 *
	 * <p>This is the original full-buffer rendering method, used for non-real-time
	 * audio generation. All elements are rendered to their full extent with
	 * absolute frame offsets.</p>
	 *
	 * @param sceneContext Scene context containing destination buffer
	 * @param audioContext Note audio context
	 * @param elements Elements to render
	 * @param melodic Whether to use melodic or percussive rendering
	 * @param offset Measure offset for pattern positioning
	 */
	default void render(AudioSceneContext sceneContext, NoteAudioContext audioContext,
						List<PatternElement> elements, boolean melodic, double offset) {
		PackedCollection destination = sceneContext.getDestination();
		if (destination == null) {
			throw new IllegalArgumentException();
		}

		elements.stream()
				.map(e -> e.getNoteDestinations(melodic, offset, sceneContext, audioContext))
				.flatMap(List::stream)
				.forEach(note -> {
					if (note.getOffset() >= destination.getShape().length(0)) return;

					Function<PackedCollection, PackedCollection> process = audio -> {
						int frames = Math.min(audio.getShape().getCount(),
								destination.getShape().length(0) - note.getOffset());
						sizes.addEntry(frames);

						TraversalPolicy shape = shape(frames);
						return AudioProcessingUtils.getSum().sum(destination.range(shape, note.getOffset()), audio.range(shape));
					};

					Heap.stage(() ->
							process.apply(traverse(1, note.getProducer()).get().evaluate()));
				});
	}

	/**
	 * Renders pattern elements to a specific frame range in the destination buffer.
	 *
	 * <p>This method handles the complexity of rendering notes that may:</p>
	 * <ul>
	 *   <li>Start before the frame range but extend into it</li>
	 *   <li>Start within the frame range</li>
	 *   <li>End after the frame range</li>
	 * </ul>
	 *
	 * <p>Only the portion of each note that overlaps with the frame range is
	 * rendered to the destination buffer.</p>
	 *
	 * <h3>Offset Calculations</h3>
	 * <ul>
	 *   <li><strong>noteAbsoluteStart</strong>: The absolute frame position where the note begins</li>
	 *   <li><strong>overlapStart</strong>: The first frame of the note that falls within the buffer</li>
	 *   <li><strong>sourceOffset</strong>: How many frames into the note's audio to start reading</li>
	 *   <li><strong>destOffset</strong>: Where in the destination buffer to write</li>
	 * </ul>
	 *
	 * <h3>Note on Heap.stage()</h3>
	 * <p>This method bypasses {@link Heap#stage} for the initial real-time implementation.
	 * If heap memory management proves beneficial for performance, it can be reintroduced later.</p>
	 *
	 * @param context Render context with frame range information
	 * @param audioContext Note audio context
	 * @param elements Elements to render
	 * @param melodic Whether to use melodic or percussive rendering
	 * @param measureOffset Measure offset for this pattern repetition
	 * @param startFrame Starting frame of buffer (absolute position)
	 * @param frameCount Size of destination buffer
	 *
	 * @see PatternRenderContext
	 * @see RenderedNoteAudio
	 */
	default void renderRange(PatternRenderContext context, NoteAudioContext audioContext,
							 List<PatternElement> elements, boolean melodic,
							 double measureOffset, int startFrame, int frameCount) {
		PackedCollection destination = context.getDestination();
		if (destination == null) {
			throw new IllegalArgumentException("Destination buffer is null");
		}

		int endFrame = startFrame + frameCount;

		elements.stream()
				.map(e -> e.getNoteDestinations(melodic, measureOffset, context, audioContext))
				.flatMap(List::stream)
				.forEach(note -> {
					int noteAbsoluteStart = note.getOffset();

					// Evaluate the note audio (bypassing Heap.stage for real-time)
					PackedCollection audio;
					try {
						audio = traverse(1, note.getProducer()).get().evaluate();
					} catch (Exception e) {
						// Skip notes that fail to evaluate
						return;
					}

					if (audio == null) return;

					int noteLength = audio.getShape().getCount();
					int noteAbsoluteEnd = noteAbsoluteStart + noteLength;

					// Check if note overlaps with frame range
					if (noteAbsoluteEnd <= startFrame || noteAbsoluteStart >= endFrame) {
						return;  // No overlap
					}

					// Calculate overlap region
					int overlapStart = Math.max(noteAbsoluteStart, startFrame);
					int overlapEnd = Math.min(noteAbsoluteEnd, endFrame);
					int overlapLength = overlapEnd - overlapStart;

					if (overlapLength <= 0) return;

					// Calculate offsets
					int sourceOffset = overlapStart - noteAbsoluteStart;  // Offset within note audio
					int destOffset = overlapStart - startFrame;            // Offset within destination buffer

					// Validate ranges
					if (sourceOffset < 0 || sourceOffset + overlapLength > noteLength) {
						return;  // Source range out of bounds
					}
					if (destOffset < 0 || destOffset + overlapLength > frameCount) {
						return;  // Dest range out of bounds
					}

					// Sum overlapping portion to destination
					try {
						TraversalPolicy shape = shape(overlapLength);
						sizes.addEntry(overlapLength);
						AudioProcessingUtils.getSum().sum(
								destination.range(shape, destOffset),
								audio.range(shape, sourceOffset)
						);
					} catch (Exception e) {
						// Skip notes that fail during summation
					}
				});
	}
}
