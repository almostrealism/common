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

package org.almostrealism.ml.audio;

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
 * Pins the two-layer SiLU MLP built from the {@code silu_mlp.pdsl} asset (see
 * {@link org.almostrealism.ml.DiffusionFeatures#siluMlp}) against two references that do not
 * depend on the asset's implementation:
 * <ul>
 *   <li>{@link HostReference}, the same {@code linear -> SiLU -> linear} arithmetic written out in
 *       plain double precision on the host, so every stage is checked independently of the
 *       framework layers; and</li>
 *   <li>the Java {@link SequentialBlock} assembly that preceded the asset (the pre-migration
 *       {@code dense -> silu -> dense} body of {@link DiffusionTransformerFeatures#timestepEmbedding}
 *       and {@link org.almostrealism.ml.DiffusionFeatures#timestepEmbeddings}, reconstructed in
 *       {@link #mlpAssembly} from the same primitives), so the migration is pinned against the exact
 *       assembly it replaced.</li>
 * </ul>
 *
 * <p>{@link #timestepEmbeddingMatchesJavaAssembly} covers the full caller path
 * {@link DiffusionTransformerFeatures#timestepEmbedding}, where the Fourier feature block is
 * prepended to the asset-backed MLP, against the pre-migration assembly of the same structure, so
 * the integration of the injected Fourier block with the asset is pinned too. Each configuration
 * uses an input, hidden and output width that all differ, so the two projections are exercised at
 * their real shapes rather than a square special case.</p>
 */
public class SiluMlpAssetTest extends TestSuiteBase implements DiffusionTransformerFeatures {

	/** Absolute tolerance between the single-precision block and the double-precision references. */
	private static final double TOLERANCE = 1e-4;

	/**
	 * The asset reproduces the {@link HostReference} oracle for a configuration whose input, hidden
	 * and output widths all differ ({@code in=6, hidden=8, out=4}).
	 */
	@Test(timeout = 300000)
	public void siluMlpAssetMatchesHostReference() {
		Weights w = new Weights(6, 8, 4, 7L);
		assertClose("standard", new HostReference(w).forward(w.input()), runAsset(w));
	}

	/**
	 * The asset reproduces the oracle for a wider hidden width ({@code in=4, hidden=16, out=5}), so
	 * the first projection expands and the second contracts a longer hidden vector.
	 */
	@Test(timeout = 300000)
	public void siluMlpAssetMatchesHostReferenceWideHidden() {
		Weights w = new Weights(4, 16, 5, 13L);
		assertClose("wide-hidden", new HostReference(w).forward(w.input()), runAsset(w));
	}

	/**
	 * The asset and the Java {@link SequentialBlock} assembly it replaced produce the same output
	 * for the same weights and input: the MLP is built both ways and the outputs are compared, so
	 * the migration is behaviour-preserving against the exact prior assembly.
	 */
	@Test(timeout = 300000)
	public void siluMlpAssetMatchesJavaAssembly() {
		Weights w = new Weights(6, 8, 4, 7L);
		double[] asset = runAsset(w);
		double[] java = run(mlpAssembly(w), w.input(), shape(1, w.in));
		log("mlp java assembly -> " + literal(java));
		assertClose("asset vs java assembly", java, asset);
	}

	/**
	 * The full {@link DiffusionTransformerFeatures#timestepEmbedding} path — the Fourier feature
	 * block followed by the asset-backed MLP — reproduces the pre-migration assembly, which placed
	 * the same Fourier block ahead of a {@code dense -> silu -> dense} {@link SequentialBlock}. The
	 * Fourier block is unchanged by the migration; this pins that the injected block composes with
	 * the asset exactly as it did with the Java assembly.
	 */
	@Test(timeout = 300000)
	public void timestepEmbeddingMatchesJavaAssembly() {
		int batch = 1;
		int fourierDim = 8;
		int embedDim = 5;

		Weights w = new Weights(fourierDim, embedDim, embedDim, 29L);
		PackedCollection freq = w.uniform(-0.5, 0.5, fourierDim / 2, 1);
		PackedCollection timestep = PackedCollection.of(0.37).reshape(shape(batch, 1));

		Block migrated = timestepEmbedding(batch, embedDim,
				fourierFeatures(batch, 1, fourierDim, freq), w.wIn, w.bIn, w.wOut, w.bOut);

		SequentialBlock assembly = new SequentialBlock(shape(batch, 1));
		assembly.add(fourierFeatures(batch, 1, fourierDim, freq));
		assembly.add(dense(w.wIn, w.bIn));
		assembly.add(silu(shape(batch, embedDim)));
		assembly.add(dense(w.wOut, w.bOut));

		double[] migratedOut = run(migrated, timestep, shape(batch, 1));
		double[] assemblyOut = run(assembly, timestep, shape(batch, 1));
		log("timestepEmbedding migrated -> " + literal(migratedOut));
		log("timestepEmbedding assembly -> " + literal(assemblyOut));
		assertClose("timestepEmbedding vs java assembly", assemblyOut, migratedOut);
	}

	/**
	 * {@link org.almostrealism.ml.DiffusionFeatures#timestepEmbeddings} builds through the asset and
	 * produces a finite embedding of the expected width. Its weights are randomly initialised, so no
	 * value can be pinned; this exercises the caller-#2 conversion end to end.
	 */
	@Test(timeout = 300000)
	public void timestepEmbeddingsBuildsThroughAsset() {
		int inputCount = 6;
		int timeLen = 10;
		int outLen = 12;

		Block embedding = timestepEmbeddings(inputCount, timeLen, outLen);
		PackedCollection input = new PackedCollection(shape(inputCount)).randnFill();
		double[] out = run(embedding, input, shape(inputCount));

		Assert.assertEquals("output width", outLen, out.length);
		for (int i = 0; i < out.length; i++) {
			Assert.assertTrue("finite element " + i, Double.isFinite(out[i]));
		}
	}

	/** Builds the asset-backed SiLU MLP for {@code w}, compiles it and runs one forward pass. */
	private double[] runAsset(Weights w) {
		return run(siluMlp(shape(1, w.in), w.wIn, w.bIn, w.wOut, w.bOut), w.input(), shape(1, w.in));
	}

	/**
	 * Reconstructs the pre-migration Java MLP assembly (the {@link SequentialBlock} body that
	 * {@link DiffusionTransformerFeatures#timestepEmbedding} and
	 * {@link org.almostrealism.ml.DiffusionFeatures#timestepEmbeddings} built before it became an
	 * asset loader) from the same primitives, so the asset is pinned against the exact structure it
	 * replaced.
	 *
	 * @param w the configuration
	 * @return the reference block
	 */
	private Block mlpAssembly(Weights w) {
		SequentialBlock mlp = new SequentialBlock(shape(1, w.in));
		mlp.add(dense(w.wIn, w.bIn));
		mlp.add(silu(shape(1, w.hidden)));
		mlp.add(dense(w.wOut, w.bOut));
		return mlp;
	}

	/**
	 * Compiles {@code block} inside a {@link Model} of the given input shape and runs one forward
	 * pass over {@code input}, returning the output vector.
	 */
	private double[] run(Block block, PackedCollection input, TraversalPolicy inputShape) {
		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();
		return compiled.forward(input).toArray();
	}

	/** Compares two vectors element-wise within {@link #TOLERANCE}. */
	private static void assertClose(String label, double[] expected, double[] actual) {
		Assert.assertEquals(label + " length", expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals(label + " element " + i, expected[i], actual[i], TOLERANCE);
		}
	}

	/** Formats a vector as a Java array literal. */
	private static String literal(double[] values) {
		StringBuilder sb = new StringBuilder("{ ");
		for (int i = 0; i < values.length; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.7f", values[i]));
		}
		return sb.append(" }").toString();
	}

	/**
	 * Deterministic weights and input for one MLP configuration, generated from a seed so the same
	 * tensors reach the block and the host reference.
	 */
	private final class Weights {
		/** Input width of the first projection. */
		private final int in;
		/** Hidden width between the two projections. */
		private final int hidden;
		/** Output width of the second projection. */
		private final int out;
		/** First projection, shape {@code [hidden, in]}. */
		private final PackedCollection wIn;
		/** First bias, shape {@code [hidden]}. */
		private final PackedCollection bIn;
		/** Second projection, shape {@code [out, hidden]}. */
		private final PackedCollection wOut;
		/** Second bias, shape {@code [out]}. */
		private final PackedCollection bOut;
		/** Seeded source of every random tensor. */
		private final Random random;

		/**
		 * Generates the tensors for one configuration.
		 *
		 * @param in     input width
		 * @param hidden hidden width
		 * @param out    output width
		 * @param seed   seed of the random tensors
		 */
		Weights(int in, int hidden, int out, long seed) {
			this.in = in;
			this.hidden = hidden;
			this.out = out;
			this.random = new Random(seed);
			this.wIn = uniform(-0.5, 0.5, hidden, in);
			this.bIn = uniform(-0.2, 0.2, hidden);
			this.wOut = uniform(-0.5, 0.5, out, hidden);
			this.bOut = uniform(-0.2, 0.2, out);
		}

		/**
		 * The input vector, produced on the device: element {@code i} is {@code sin(1 + 0.7 i)}, a
		 * deterministic input with mixed signs and magnitudes.
		 *
		 * @return the {@code [1, in]} input vector
		 */
		PackedCollection input() {
			CollectionProducer index = integers(0, in);
			CollectionProducer values = sin(index.multiply(0.7).add(1.0));
			return values.reshape(shape(1, in)).evaluate();
		}

		/**
		 * Draws a tensor of the given shape from the seeded source, uniformly in {@code [min, max)},
		 * produced on the device.
		 */
		private PackedCollection uniform(double min, double max, int... dims) {
			TraversalPolicy shape = shape(dims);
			return rand(shape, random).multiply(max - min).add(min).reshape(shape).evaluate();
		}
	}

	/**
	 * The {@code linear -> SiLU -> linear} arithmetic in plain double precision: an oracle for what
	 * one forward pass must produce, independent of the framework layers and of the asset.
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
		 * One forward pass: the first affine projection, the SiLU gate, then the second affine
		 * projection.
		 *
		 * @param token the input vector
		 * @return the MLP output
		 */
		double[] forward(PackedCollection token) {
			double[] hidden = project(w.wIn, w.bIn, token.toArray(), w.hidden);
			double[] activated = new double[w.hidden];
			for (int j = 0; j < w.hidden; j++) {
				activated[j] = silu(hidden[j]);
			}
			return project(w.wOut, w.bOut, activated, w.out);
		}

		/** SiLU (a.k.a. Swish): {@code x * sigmoid(x)}. */
		private double silu(double x) {
			return x / (1.0 + Math.exp(-x));
		}

		/**
		 * Affine projection {@code weights @ x + bias} with weights of shape {@code [nodes, x.length]}
		 * and bias of shape {@code [nodes]}.
		 */
		private double[] project(PackedCollection weights, PackedCollection bias, double[] x, int nodes) {
			double[] wv = weights.toArray();
			double[] bv = bias.toArray();
			double[] out = new double[nodes];
			for (int n = 0; n < nodes; n++) {
				double acc = bv[n];
				for (int s = 0; s < x.length; s++) {
					acc += wv[n * x.length + s] * x[s];
				}
				out[n] = acc;
			}
			return out;
		}
	}
}
