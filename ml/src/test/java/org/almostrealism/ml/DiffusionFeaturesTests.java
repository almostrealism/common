package org.almostrealism.ml;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.model.Block;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

public class DiffusionFeaturesTests extends TestSuiteBase implements DiffusionFeatures {
	@Test
	public void upsample() {
		int batchSize = 4;
		int inputChannels = 56;
		int h = 14;
		int w = 14;

		PackedCollection input =
				new PackedCollection(shape(batchSize, inputChannels, h, w)).randFill();

		Cell.CaptureReceptor<PackedCollection> receptor =
				new Cell.CaptureReceptor<>();

		Block upsample = upsample(inputChannels, inputChannels).apply(input.getShape());
		upsample.getForward().setReceptor(receptor);

		OperationList op = new OperationList();
		op.add(upsample.setup());
		op.add(upsample.forward(cp(input)));
		op.run();

		receptor.getReceipt().evaluate().print();
	}
}
