package org.almostrealism.ml;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.function.Supplier;

public class OperationTests implements TestFeatures {
	int heads = 1;
	int seqLength = 20;

	private PackedCollection<?> copy(PackedCollection<?> input) {
		PackedCollection<?> output = new PackedCollection<>(input.getShape());
		output.fill(pos -> input.valueAt(pos));
		return output;
	}

	protected void compare(Supplier<Runnable> op, PackedCollection<?> dest, double values[]) {
		op.get().run();

		double out[] = dest.toArray(0, values.length);
		for (int i = 0; i < values.length; i++) {
			if (Math.abs(out[i] - values[i]) > 1e-5) {
				throw new AssertionError("Mismatch at " + i + ": " + out[i] + " != " + values[i]);
			}
		}
	}

	@Test
	public void softmaxTest() {
		int h = 0;

		PackedCollection<?> originalInput = new PackedCollection<>(shape(heads, seqLength));
		originalInput.fill(pos -> Math.random());
		PackedCollection<?> input = copy(originalInput);

		HardwareOperator.verboseLog(() -> {
			Producer<PackedCollection<?>> in = traverseEach(p(input));
			CollectionProducer<PackedCollection<?>> subset = c(subset(shape(1, seqLength), in, h, 0)).traverseEach();
			CollectionProducer<PackedCollection<?>> p = subset.exp().divide(subset.exp().traverse(0).sum());

			double values[] = originalInput.toArray(0, originalInput.getShape().getTotalSize());
			softmax(values, h * seqLength, seqLength);

			compare(a(subset, p), input, values);
		});
	}

	protected static void softmax(double[] x, int offset, int size) {
		double max = x[0 + offset];
		for (int i = 1; i < size; i++) {
			if (x[i + offset] > max) {
				max = x[i + offset];
			}
		}

		double sum = 0.0;

		for (int i = 0; i < size; i++) {
			x[i + offset] = Math.exp(x[i + offset] - max);
			sum += x[i + offset];
		}

		for (int i = 0; i < size; i++) {
			x[i + offset] /= sum;
		}
	}
}
