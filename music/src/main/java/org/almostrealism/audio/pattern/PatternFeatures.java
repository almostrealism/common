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
 * <h2>Real-Time Limitations</h2>
 *
 * <p><strong>Critical:</strong> The current implementation has several limitations
 * for real-time rendering:</p>
 * <ul>
 *   <li>Audio is evaluated inside {@link Heap#stage}, which may allocate memory</li>
 *   <li>Note offsets are absolute, not buffer-relative</li>
 *   <li>The method processes all elements without frame-range filtering</li>
 *   <li>No support for notes spanning multiple buffers</li>
 * </ul>
 *
 * <p>For real-time support, a frame-range-aware version would need to:</p>
 * <ul>
 *   <li>Filter elements to only those intersecting the current buffer</li>
 *   <li>Convert absolute offsets to buffer-relative offsets</li>
 *   <li>Handle notes that started in previous buffers (partial rendering)</li>
 *   <li>Pre-evaluate audio producers to avoid allocation during rendering</li>
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
}
