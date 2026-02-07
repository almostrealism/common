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
