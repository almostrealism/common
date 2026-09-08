package org.almostrealism.ml;

import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.model.Block;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

/**
 * Tests for diffusion model features including upsampling layers.
 *
 * @see DiffusionFeatures
 */
public class DiffusionFeaturesTests extends TestSuiteBase implements DiffusionFeatures {

	/**
	 * Tests the upsampling layer for diffusion models.
	 * Verifies that a simple upsampling block can be set up and executed.
	 */
	@Test(timeout = 7 * 60000)
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

	/**
	 * Runs {@link #upsample()} under an operation profile and saves it to
	 * {@code results/upsample.xml} for inspection with the profile analyzer.
	 *
	 * <p>The forward convolution of that test compiles a kernel over 175,616 indices
	 * whose compile time is dominated by the {@code kernelSeries} stages of expression
	 * simplification. This profile records those stages per operation, so the cost of
	 * kernel series detection on the forward path can be read directly rather than
	 * inferred from wall-clock time.</p>
	 */
	@Test(timeout = 7 * 60000)
	public void upsampleProfile() throws IOException {
		OperationProfileNode profile = initKernelMetrics(new OperationProfileNode("upsample"));

		try {
			upsample();
		} finally {
			profile.save("results/upsample.xml");
		}

		Assert.assertFalse("No operations were recorded by the profile",
				profile.getChildren().isEmpty());
	}
}
