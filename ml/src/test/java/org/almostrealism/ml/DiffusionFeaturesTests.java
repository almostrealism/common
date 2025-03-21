package org.almostrealism.ml;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.model.Block;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class DiffusionFeaturesTests implements DiffusionFeatures, TestFeatures {
	@Test
	public void upsample() {
		int batchSize = 4;
		int inputChannels = 3;
		int h = 2;
		int w = 2;

		PackedCollection<?> input =
				new PackedCollection<>(shape(batchSize, inputChannels, h, w)).randFill();

		Cell.CaptureReceptor<PackedCollection<?>> receptor =
				new Cell.CaptureReceptor<>();

		Block upsample = upsample(inputChannels, inputChannels).apply(input.getShape());
		upsample.getForward().setReceptor(receptor);
		upsample.forward(input).run();

		receptor.getReceipt().evaluate().print();
	}
}
