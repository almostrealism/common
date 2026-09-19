/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Random;

/**
 * Pins the behaviour of the SwiGLU feed-forward block built by
 * {@link FeedForwardFeatures#feedForward} from the {@code feed_forward.pdsl} asset against two
 * references that do not depend on the asset's implementation:
 * <ul>
 *   <li>{@link HostReference}, the same arithmetic written out in plain double precision on the
 *       host, so every stage (RMSNorm, gate projection, SiLU, up projection, element-wise
 *       product and down projection) is checked independently of the framework layers; and</li>
 *   <li>golden outputs captured from the Java {@code SequentialBlock} assembly that preceded the
 *       asset (the pre-migration {@code FeedForwardFeatures.feedForward} body, reconstructed in
 *       {@link #javaAssembly} here from the same primitives), with the seed and dimensions used
 *       below.</li>
 * </ul>
 *
 * <p>{@link #swigluAssetMatchesJavaAssembly} additionally builds the block both ways — the asset
 * via the loader and the Java assembly directly — and asserts they agree, so the migration is
 * pinned against the exact assembly it replaced and not only against the golden snapshot. Each
 * configuration uses a hidden dimension different from the model dimension so the gate/up/down
 * projections are exercised at their real shapes rather than a square special case.</p>
 */
public class FeedForwardAssetTest extends TestSuiteBase implements FeedForwardFeatures {

	/** Absolute tolerance between the single-precision block and the double-precision references. */
	private static final double TOLERANCE = 1e-4;

	/** RMSNorm epsilon shared by every configuration. */
	private static final double EPSILON = 1e-5;

	/**
	 * Output of the Java SwiGLU assembly for the primary configuration
	 * ({@code dim=4, hidden=8}, seed 7).
	 */
	private static final double[] STANDARD_GOLDEN = {
			0.0280957, 0.0067552, 0.0279426, -0.1303563 };

	/**
	 * The asset reproduces the {@link HostReference} oracle for the primary configuration:
	 * four model dimensions, eight hidden. Every stage of the SwiGLU is exercised.
	 */
	@Test(timeout = 300000)
	public void swigluAssetMatchesHostReference() {
		Weights w = new Weights(4, 8, 7L);
		assertClose("standard", new HostReference(w).forward(w.input()), runAsset(w));
	}

	/**
	 * The asset reproduces the oracle for a wider hidden dimension ({@code dim=6, hidden=16}),
	 * so the down projection reduces a longer hidden vector than the model dimension.
	 */
	@Test(timeout = 300000)
	public void swigluAssetMatchesHostReferenceWideHidden() {
		Weights w = new Weights(6, 16, 13L);
		assertClose("wide-hidden", new HostReference(w).forward(w.input()), runAsset(w));
	}

	/**
	 * The asset and the Java {@code SequentialBlock} assembly it replaced produce the same output
	 * for the same weights and input: the block is built both ways and the outputs are compared
	 * directly, so the migration is behaviour-preserving against the exact prior assembly.
	 */
	@Test(timeout = 300000)
	public void swigluAssetMatchesJavaAssembly() {
		Weights w = new Weights(4, 8, 7L);
		double[] asset = runAsset(w);
		double[] java = run(javaAssembly(w), w);
		log("java assembly -> " + literal(java));
		assertClose("asset vs java assembly", java, asset);
	}

	/** The asset reproduces the golden values captured from the Java assembly. */
	@Test(timeout = 300000)
	public void swigluAssetMatchesMasterGoldenValues() {
		Weights w = new Weights(4, 8, 7L);
		assertClose("standard golden", STANDARD_GOLDEN, runAsset(w));
	}

	/** Builds the asset-backed SwiGLU block for {@code w}, compiles it and runs one forward pass. */
	private double[] runAsset(Weights w) {
		return run(feedForward(w.rms, w.w1, w.w2, w.w3, EPSILON), w);
	}

	/**
	 * Reconstructs the pre-migration Java SwiGLU assembly (the {@code SequentialBlock} body that
	 * {@link FeedForwardFeatures#feedForward} built before it became an asset loader) from the
	 * same primitives, so the asset can be pinned against the exact structure it replaced.
	 *
	 * @param w the configuration
	 * @return the reference block
	 */
	private Block javaAssembly(Weights w) {
		TraversalPolicy shape = shape(1, w.dim);
		SequentialBlock feedForward = new SequentialBlock(shape);
		feedForward.add(rmsnorm(shape, w.rms, (PackedCollection) null, EPSILON));

		SequentialBlock hidden = new SequentialBlock(shape);
		hidden.add(dense(w.w1));
		hidden.add(silu());

		feedForward.product(dense(w.w3), hidden);
		feedForward.add(dense(w.w2));
		return feedForward;
	}

	/**
	 * Compiles {@code block} inside a {@link Model} shaped {@code (1, dim)} and runs one forward
	 * pass over {@code w}'s input, returning the output vector.
	 */
	private double[] run(Block block, Weights w) {
		Model model = new Model(shape(1, w.dim));
		model.add(block);
		CompiledModel compiled = model.compile();
		double[] output = compiled.forward(w.input()).toArray();
		log("swiglu dim=" + w.dim + " hidden=" + w.hiddenDim + " -> " + literal(output));
		return output;
	}

	/** Compares two vectors element-wise within {@link #TOLERANCE}. */
	private static void assertClose(String label, double[] expected, double[] actual) {
		Assert.assertEquals(label + " length", expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals(label + " element " + i, expected[i], actual[i], TOLERANCE);
		}
	}

	/** Formats a vector as a Java array literal, the form the golden values are recorded in. */
	private static String literal(double[] values) {
		StringBuilder sb = new StringBuilder("{ ");
		for (int i = 0; i < values.length; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.7f", values[i]));
		}
		return sb.append(" }").toString();
	}

	/**
	 * Deterministic weights and input for one feed-forward configuration, generated from a seed so
	 * the same tensors reach the block and the host reference.
	 */
	private final class Weights {
		/** Model dimension (input and output length). */
		private final int dim;
		/** Hidden (inner) dimension of the gate and up projections. */
		private final int hiddenDim;
		/** Pre-FFN RMSNorm scale, shape {@code [dim]}. */
		private final PackedCollection rms;
		/** Gate projection, shape {@code [hiddenDim, dim]}. */
		private final PackedCollection w1;
		/** Down projection, shape {@code [dim, hiddenDim]}. */
		private final PackedCollection w2;
		/** Up projection, shape {@code [hiddenDim, dim]}. */
		private final PackedCollection w3;
		/** Seeded source of every random tensor. */
		private final Random random;

		/**
		 * Generates the tensors for one configuration.
		 *
		 * @param dim       model dimension
		 * @param hiddenDim hidden dimension
		 * @param seed      seed of the random tensors
		 */
		Weights(int dim, int hiddenDim, long seed) {
			this.dim = dim;
			this.hiddenDim = hiddenDim;
			this.random = new Random(seed);
			this.rms = uniform(0.5, 1.5, dim);
			this.w1 = uniform(-0.5, 0.5, hiddenDim, dim);
			this.w2 = uniform(-0.5, 0.5, dim, hiddenDim);
			this.w3 = uniform(-0.5, 0.5, hiddenDim, dim);
		}

		/**
		 * The token vector, produced on the device: element {@code i} is
		 * {@code sin(1 + 0.7 i)}, a deterministic input with mixed signs and magnitudes.
		 *
		 * @return the {@code [1, dim]} input vector
		 */
		PackedCollection input() {
			CollectionProducer index = integers(0, dim);
			CollectionProducer values = sin(index.multiply(0.7).add(1.0));
			return values.reshape(shape(1, dim)).evaluate();
		}

		/**
		 * Draws a tensor of the given shape from the seeded source, uniformly in
		 * {@code [min, max)}, produced on the device.
		 */
		private PackedCollection uniform(double min, double max, int... dims) {
			TraversalPolicy shape = shape(dims);
			return rand(shape, random).multiply(max - min).add(min).reshape(shape).evaluate();
		}
	}

	/**
	 * The SwiGLU feed-forward arithmetic in plain double precision: an oracle for what one forward
	 * pass must produce, independent of the framework layers and of the asset.
	 */
	private static final class HostReference {
		/** The configuration being reproduced. */
		private final Weights w;

		/**
		 * Creates an oracle for {@code w}.
		 *
		 * @param w the configuration
		 */
		HostReference(Weights w) {
			this.w = w;
		}

		/**
		 * One forward pass: RMS-normalizes the input, projects it through the gate and up weights,
		 * gates the up projection by the SiLU of the gate projection, then projects the gated
		 * hidden vector back to the model dimension.
		 *
		 * @param token the input vector
		 * @return the feed-forward output
		 */
		double[] forward(PackedCollection token) {
			double[] normed = rmsnorm(token.toArray(), w.rms.toArray(), EPSILON, w.dim);
			double[] gate = project(w.w1, normed, w.hiddenDim);
			double[] up = project(w.w3, normed, w.hiddenDim);

			double[] hidden = new double[w.hiddenDim];
			for (int j = 0; j < w.hiddenDim; j++) {
				hidden[j] = up[j] * silu(gate[j]);
			}
			return project(w.w2, hidden, w.dim);
		}

		/** SiLU (a.k.a. Swish): {@code x * sigmoid(x)}. */
		private double silu(double x) {
			return x / (1.0 + Math.exp(-x));
		}

		/**
		 * RMS-normalizes {@code x} over its full extent by its own root mean square and scales
		 * element-wise by {@code weight}.
		 */
		private double[] rmsnorm(double[] x, double[] weight, double epsilon, int size) {
			double sumSq = 0;
			for (int i = 0; i < size; i++) {
				sumSq += x[i] * x[i];
			}
			double scale = 1.0 / Math.sqrt(sumSq / size + epsilon);
			double[] out = new double[size];
			for (int i = 0; i < size; i++) {
				out[i] = x[i] * scale * weight[i];
			}
			return out;
		}

		/** Dense projection {@code weights @ x} with weights of shape {@code [nodes, x.length]}. */
		private double[] project(PackedCollection weights, double[] x, int nodes) {
			double[] wv = weights.toArray();
			double[] out = new double[nodes];
			for (int n = 0; n < nodes; n++) {
				double acc = 0;
				for (int s = 0; s < x.length; s++) {
					acc += wv[n * x.length + s] * x[s];
				}
				out[n] = acc;
			}
			return out;
		}
	}
}
