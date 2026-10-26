/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.GradientRecorder;
import org.almostrealism.util.ModelTestFeatures;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.DoubleSupplier;

/**
 * Pins the learned-resampling transformer layer — the {@code resampling_transformer_layer} of
 * {@code /pdsl/resampling_transformer.pdsl}, which
 * {@link TransformerResamplingFeatures#resamplingLayerBlock} builds — against the Java assembly it
 * replaced, against a host reference computed in double precision, and against output values captured
 * from that Java assembly on master.
 *
 * <p>The layer is a DynamicTanh pre-norm, a fused five-section projection, two attention maps whose
 * queries and keys are DynamicTanh-normalized and rotated, their difference, the head merge and the
 * output projection, all inside a residual connection, followed by a DynamicTanh-normalized gated
 * feed-forward inside a second. The configurations keep every stage honest: three heads, a rotation
 * that turns half of every head row and passes the other half through, three attention chunks (each
 * its own batch entry of the attention), and, for the blocks, a midpoint-shifted stack whose two halves
 * run at different chunk counts.</p>
 *
 * <p>The weights and inputs are drawn from seeded generators, so the values captured on master
 * reproduce exactly the computation they were captured from.</p>
 *
 * <p>The backward pass is pinned against central finite differences. The Java assembly the asset
 * replaced failed that test: its projections were built as weightless cells over constant weight
 * tensors, and it copied the first keys, the values, the second queries and the second attention map
 * into collections that it read back as constants, so a backward pass delivered no gradient to its
 * fused projection at all.</p>
 */
public class ResamplingTransformerAssetTest extends SAMEResamplingTestBase implements ModelTestFeatures {

	/** The asset the layer is defined in. */
	private static final String ASSET = "/pdsl/resampling_transformer.pdsl";

	/** The layer of {@link #ASSET} under test. */
	private static final String LAYER = "resampling_transformer_layer";

	/** The weight key prefix of the block whose layers are tested. */
	private static final String PREFIX = "enc";

	/** The seed of the weights. */
	private static final long WEIGHT_SEED = 1026;

	/** The seed of the inputs. */
	private static final long INPUT_SEED = 2026;

	/** Attention chunks of the single-layer tests. */
	private static final int CHUNKS = 3;

	/** Standard deviation of the seeded weights. */
	private static final double WEIGHT_SCALE = 0.5;

	/** Agreement required between two float implementations, relative to the largest value compared. */
	private static final double PARITY_TOLERANCE = 1e-5;

	/** Agreement required with the double-precision host reference, relative to the largest value. */
	private static final double HOST_TOLERANCE = 1e-4;

	/** Step of the central finite differences. */
	private static final double FINITE_DIFFERENCE_STEP = 1e-2;

	/**
	 * Standard deviation of the weights of the gradient test: large enough that the queries and keys,
	 * squashed by their DynamicTanh, still sharpen the softmax, so that their sections of the fused
	 * projection carry gradients well above the float rounding of the finite differences.
	 */
	private static final double GRADIENT_WEIGHT_SCALE = 1.0;

	/** Agreement required with the finite differences, relative to the largest gradient compared. */
	private static final double GRADIENT_TOLERANCE = 0.02;

	/** Smallest largest-magnitude a gradient may have: below it the gradient is trivially zero. */
	private static final double MINIMUM_GRADIENT = 1e-3;

	/**
	 * Agreement required with values captured on master, relative to the largest value compared: the
	 * float arithmetic of one backend against another, a few cells deep.
	 */
	private static final double GOLDEN_TOLERANCE = 1e-5;

	/** Spacing of {@link #MASTER_LAYER_SAMPLES} among the layer's outputs. */
	private static final int SAMPLE_SPACING = 27;

	/**
	 * Every {@value #SAMPLE_SPACING}th of the 432 outputs of the layer of {@link #layerConfig()} over
	 * {@link #CHUNKS} chunks, for the weights of {@link #WEIGHT_SEED} at {@link #WEIGHT_SCALE} and the
	 * input of {@link #INPUT_SEED}, as the Java assembly in
	 * {@link TransformerResamplingFeatures#resamplingLayerBlock} computed them on master (Metal),
	 * before the asset replaced it.
	 */
	private static final double[] MASTER_LAYER_SAMPLES = {
			-5.162349343e-02, 2.575904131e-01, -1.654720426e+00, -3.476427555e+00,
			6.733064651e-01, -4.661199450e-02, -3.234533072e+00, 1.103745997e-01,
			-2.235391378e+00, 4.439762831e-01, 1.054180861e+00, -2.610326052e+00,
			3.941917419e-02, -1.013225198e+00, -5.717285872e-01, -1.827862859e-01
	};

	/** The sum of the magnitudes of all 432 outputs of the master run of {@link #MASTER_LAYER_SAMPLES}. */
	private static final double MASTER_LAYER_MAGNITUDE = 5.971904436e+02;

	/**
	 * The output of an encoder block of {@link #smallConfig(boolean)} over an input of length 8, for the
	 * weights of {@link #WEIGHT_SEED} at {@link #WEIGHT_SCALE} and the input of {@link #INPUT_SEED}, as
	 * {@link TransformerResamplingFeatures#transformerResamplingBlock} computed it on master (Metal)
	 * with the Java layer assembly.
	 */
	private static final double[] MASTER_ENCODER_BLOCK = {
			3.390501142e-01, 3.312149048e-01, 3.392866850e-01, 3.421983123e-01,
			-9.948670864e-02, -1.087584496e-01, -1.141974926e-01, -9.943097830e-02,
			8.407254219e-01, 8.234284520e-01, 8.265336156e-01, 8.422238231e-01,
			-5.297148824e-01, -5.427932739e-01, -5.427854061e-01, -5.294883251e-01,
			1.726989746e-01, 1.682542264e-01, 1.735299528e-01, 1.757163703e-01,
			-5.739846230e-01, -5.701042414e-01, -5.691766739e-01, -5.738379955e-01,
			-2.335773706e+00, -2.340801477e+00, -2.351278782e+00, -2.332825184e+00,
			-6.387194395e-01, -6.358613968e-01, -6.548860669e-01, -6.429911852e-01
	};

	/**
	 * The output of a decoder block of {@link #smallConfig(boolean)} over an input of length 4, for the
	 * same seeds, as {@link TransformerResamplingFeatures#transformerResamplingBlock} computed it on
	 * master (Metal) with the Java layer assembly.
	 */
	private static final double[] MASTER_DECODER_BLOCK = {
			-2.191220224e-01, -1.632654190e+00, -1.586781263e+00, -1.615762949e+00,
			-1.641606569e+00, -1.637064934e+00, -1.601189375e+00, -6.236140728e+00,
			-6.055890322e-01, -1.128144503e+00, -1.143759012e+00, -1.109228730e+00,
			-1.092651129e+00, -1.099695206e+00, -1.128185630e+00, -2.888364077e+00,
			5.900596142e+00, 3.326523542e+00, 3.368524551e+00, 3.350154877e+00,
			3.353232861e+00, 3.362771749e+00, 3.395291567e+00, 2.901637077e+00,
			2.338692665e+00, 3.410313129e+00, 3.404188871e+00, 3.357525587e+00,
			3.338962793e+00, 3.347340584e+00, 3.358062506e+00, -2.914866805e-02
	};

	/**
	 * A resampling configuration of three heads of eight features: the rotary embedding has two
	 * inverse frequencies, so it turns four of the eight features of every head row and passes the
	 * other four through. The width is 24, the chunks are six positions long and the feed-forward is
	 * 48 wide.
	 *
	 * @return the configuration
	 */
	private ResamplingConfig layerConfig() {
		return new ResamplingConfig(8, 24, 3, 8, 2, 4, 2,
				true, true, true, 2.0, 1, ResamplingConfig.AttentionWindow.CHUNKED);
	}

	/**
	 * The layer the asset defines and the Java assembly it replaced compute the same output for the
	 * same weights and input. While the assembly is in place this compares the two definitions; once it
	 * is gone, {@link TransformerResamplingFeatures#resamplingLayerBlock} builds the asset, and this
	 * compares its argument binding with the binding written out here.
	 */
	@Test(timeout = 300000)
	public void layerMatchesJavaAssembly() {
		ResamplingConfig config = layerConfig();
		StateDictionary weights = seededWeights(config, WEIGHT_SEED, WEIGHT_SCALE);
		String key = layerKey(PREFIX, 0);
		PackedCollection input = seeded(chunkShape(config, CHUNKS), INPUT_SEED);

		double[] assembly = values(evalBlock(resamplingLayerBlock(CHUNKS, config, weights, key), input));
		double[] asset = values(evalBlock(assetLayer(config, CHUNKS, weights, key), input));
		assertClose("asset vs Java assembly", assembly, asset, PARITY_TOLERANCE);
	}

	/**
	 * The layer the asset defines computes the layer the reference implementation defines, written out
	 * stage by stage on the host in double precision (see {@link #hostLayer}).
	 */
	@Test(timeout = 300000)
	public void layerMatchesHostReference() {
		ResamplingConfig config = layerConfig();
		StateDictionary weights = seededWeights(config, WEIGHT_SEED, WEIGHT_SCALE);
		String key = layerKey(PREFIX, 0);
		PackedCollection input = seeded(chunkShape(config, CHUNKS), INPUT_SEED);

		double[] asset = values(evalBlock(assetLayer(config, CHUNKS, weights, key), input));
		double[] host = hostLayer(config, weights, key, CHUNKS, values(input));
		assertClose("asset vs host reference", host, asset, HOST_TOLERANCE);
	}

	/**
	 * The backward pass delivers to every section of the fused projection, to the output projection,
	 * to the two projections of the feed-forward and to the input the gradients the forward pass
	 * implies, compared section by section against central finite differences of
	 * {@code L = sum(output * G)} for a fixed output gradient {@code G}. Each of the five sections
	 * reaches the output: the queries and keys of both maps and the values both maps weight. Against
	 * the Java assembly the asset replaced this test failed before any comparison, with no gradient
	 * recorded for the fused projection: that assembly built its projections as weightless cells over
	 * constant weight tensors, and it copied the first keys, the values, the second queries and the
	 * second map into collections that it read back as constants, so its backward pass reached none of
	 * its projections.
	 */
	@Test(timeout = 900000)
	public void everySectionOfTheFusedProjectionReceivesItsGradient() {
		ResamplingConfig config = smallConfig(true);
		int chunks = 2;
		int dim = config.getDim();
		int inner = config.getInnerFfDim();
		String key = layerKey(PREFIX, 0);
		StateDictionary weights = seededWeights(config, WEIGHT_SEED, GRADIENT_WEIGHT_SCALE);
		TraversalPolicy shape = chunkShape(config, chunks);
		PackedCollection input = seeded(shape, INPUT_SEED);
		PackedCollection outputGradient = seeded(shape, INPUT_SEED + 1);

		GradientRecorder recorder = new GradientRecorder();
		Model model = new Model(shape, recorder);
		model.add(resamplingLayerBlock(chunks, config, weights, key));
		try (CompiledModel compiled = model.compile(true, true)) {
			compiled.forward(input);
			double[] inputGradient = values(compiled.backward(outputGradient));
			double[] fusedGradient = recorder.gradientOfSize(5 * dim * dim);
			double[] outputProjectionGradient = recorder.gradientOfSize(dim * dim);
			double[] feedForwardInGradient = recorder.gradientOfSize(2 * inner * dim);
			double[] feedForwardOutGradient = recorder.gradientOfSize(dim * inner);

			Evaluable<PackedCollection> loss = multiply(cv(shape, 0), cv(shape, 1)).sum().get();
			DoubleSupplier objective = () -> loss.evaluate(compiled.forward(input), outputGradient).toDouble();

			double[] fusedNumeric = centralDifferences(weights.require(key + ".self_attn.to_qkv.weight"),
					FINITE_DIFFERENCE_STEP, objective);
			String[] sections = { "Q1", "K1", "V", "Q2", "K2" };
			int sectionSize = dim * dim;
			for (int s = 0; s < sections.length; s++) {
				assertGradientMatches(sections[s] + " section of the fused projection",
						Arrays.copyOfRange(fusedNumeric, s * sectionSize, (s + 1) * sectionSize),
						Arrays.copyOfRange(fusedGradient, s * sectionSize, (s + 1) * sectionSize),
						GRADIENT_TOLERANCE, MINIMUM_GRADIENT);
			}

			assertMatchesDifferences("output projection", weights.require(key + ".self_attn.to_out.weight"),
					outputProjectionGradient, objective);
			assertMatchesDifferences("feed-forward input projection", weights.require(key + ".ff.ff.0.proj.weight"),
					feedForwardInGradient, objective);
			assertMatchesDifferences("feed-forward output projection", weights.require(key + ".ff.ff.2.weight"),
					feedForwardOutGradient, objective);
			assertMatchesDifferences("input", input, inputGradient, objective);
		}
	}

	/**
	 * Asserts that the gradient a backward pass delivered for {@code values} matches the central finite
	 * differences of {@code objective} with respect to them, at this test's step and tolerances.
	 *
	 * @param label     what the gradient is of, for the messages
	 * @param values    the weight or input the gradient is of, perturbed in place by the differences
	 * @param analytic  the gradient the backward pass delivered
	 * @param objective the loss, evaluated by a forward pass
	 */
	private void assertMatchesDifferences(String label, PackedCollection values, double[] analytic,
								  DoubleSupplier objective) {
		assertGradientMatches(label, centralDifferences(values, FINITE_DIFFERENCE_STEP, objective),
				analytic, GRADIENT_TOLERANCE, MINIMUM_GRADIENT);
	}

	/**
	 * The layer reproduces the output the Java assembly computed on master for the same seeded weights
	 * and input: every {@value #SAMPLE_SPACING}th output, and the sum of the magnitudes of all of them.
	 */
	@Test(timeout = 300000)
	public void layerMatchesMasterGoldenValues() {
		ResamplingConfig config = layerConfig();
		StateDictionary weights = seededWeights(config, WEIGHT_SEED, WEIGHT_SCALE);
		PackedCollection input = seeded(chunkShape(config, CHUNKS), INPUT_SEED);
		double[] output = values(evalBlock(resamplingLayerBlock(CHUNKS, config, weights, layerKey(PREFIX, 0)), input));

		double[] samples = new double[MASTER_LAYER_SAMPLES.length];
		for (int i = 0; i < samples.length; i++) {
			samples[i] = output[i * SAMPLE_SPACING];
		}
		assertClose("layer samples vs master", MASTER_LAYER_SAMPLES, samples, GOLDEN_TOLERANCE);
		assertEquals("sum of the output magnitudes vs master", MASTER_LAYER_MAGNITUDE,
				Arrays.stream(output).map(Math::abs).sum(), GOLDEN_TOLERANCE * MASTER_LAYER_MAGNITUDE);
	}

	/**
	 * A whole encoder block — the channel mapping, the segmentation with learned tokens, a two-layer
	 * stack whose second layer runs on midpoint-shifted chunks of a different chunk count, and the
	 * extraction — reproduces the output it computed on master for the same seeded weights and input.
	 */
	@Test(timeout = 300000)
	public void encoderBlockMatchesMasterGoldenValues() {
		assertBlockMatchesMaster(true, 8, MASTER_ENCODER_BLOCK);
	}

	/**
	 * A whole decoder block — the segmentation, the midpoint-shifted two-layer stack, the extraction and
	 * the three-tap channel mapping — reproduces the output it computed on master for the same seeded
	 * weights and input.
	 */
	@Test(timeout = 300000)
	public void decoderBlockMatchesMasterGoldenValues() {
		assertBlockMatchesMaster(false, 4, MASTER_DECODER_BLOCK);
	}

	/**
	 * One layer at the dimensions of the released SAME-S encoder — twelve heads of 64, chunks of 34
	 * positions, a feed-forward 2304 wide — builds, compiles and computes the layer the double-precision
	 * host reference defines. Each stage of the layer is its own compiled cell, which is what keeps the
	 * kernels bounded at these dimensions; a stage that fused another into its kernel would show here as
	 * a compilation that does not finish.
	 */
	@Test(timeout = 900000)
	public void sameSizedLayerMatchesHostReference() {
		ResamplingConfig config = sameEncoderConfig();
		StateDictionary weights = seededWeights(config, WEIGHT_SEED, 1.0 / Math.sqrt(config.getDim()));
		String key = layerKey(PREFIX, 0);
		int chunks = 2;
		PackedCollection input = seeded(chunkShape(config, chunks), INPUT_SEED);

		double[] layer = values(evalBlock(resamplingLayerBlock(chunks, config, weights, key), input));
		double[] host = hostLayer(config, weights, key, chunks, values(input));
		assertClose("SAME-S layer vs host reference", host, layer, HOST_TOLERANCE);
	}

	/**
	 * Asserts that a whole resampling block of {@link #smallConfig(boolean)} reproduces the values it
	 * computed on master, for the seeded weights of {@link #WEIGHT_SEED} and the seeded input of
	 * {@link #INPUT_SEED}.
	 *
	 * @param encoder whether the block downsamples
	 * @param length  the block's input length
	 * @param master  the block's output on master, flattened
	 */
	private void assertBlockMatchesMaster(boolean encoder, int length, double[] master) {
		ResamplingConfig config = smallConfig(encoder);
		StateDictionary weights = seededWeights(config, WEIGHT_SEED, WEIGHT_SCALE);
		PackedCollection input = seeded(shape(1, config.getInChannels(), length), INPUT_SEED);
		double[] output = values(evalBlock(transformerResamplingBlock(1, length, config, weights, PREFIX), input));
		assertClose((encoder ? "encoder" : "decoder") + " block vs master", master, output, GOLDEN_TOLERANCE);
	}

	/**
	 * Builds the asset's layer the way a caller binds it, independently of
	 * {@link TransformerResamplingFeatures#resamplingLayerBlock}: the layer's checkpoint group, the
	 * groups of the two feed-forward projections and an all-ones mask.
	 *
	 * @param config  the resampling configuration
	 * @param chunks  the number of attention chunks
	 * @param weights the block's weights
	 * @param key     the layer's weight key prefix
	 * @return the layer
	 */
	private Block assetLayer(ResamplingConfig config, int chunks, StateDictionary weights, String key) {
		int chunkLength = config.getEffectiveChunkSize();
		Map<String, Object> args = new HashMap<>();
		args.put("chunks", chunks);
		args.put("chunk_len", chunkLength);
		args.put("heads", config.getHeads());
		args.put("head_dim", config.getDimHead());
		args.put("inner_dim", config.getInnerFfDim());
		args.put("weights", weights.group(key));
		args.put("ff_in", weights.group(key + ".ff.ff.0.proj"));
		args.put("ff_out", weights.group(key + ".ff.ff.2"));
		args.put("every_position", new PackedCollection(shape(chunks, chunkLength)).fill(1.0));

		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource(ASSET), LAYER, chunkShape(config, chunks), args);
	}

	/**
	 * The shape of the layer's input and output: {@code chunks} chunks of the effective chunk length.
	 *
	 * @param config the resampling configuration
	 * @param chunks the number of attention chunks
	 * @return {@code [chunks, chunk_len, dim]}
	 */
	private TraversalPolicy chunkShape(ResamplingConfig config, int chunks) {
		return shape(chunks, config.getEffectiveChunkSize(), config.getDim());
	}

	/**
	 * Draws every weight a resampling block of {@code config} reads under {@link #PREFIX} from a
	 * seeded Gaussian, in the insertion order of {@link ResamplingConfig#weightShapes}, so the same seed
	 * always yields the same weights.
	 *
	 * @param config the resampling configuration
	 * @param seed   the seed
	 * @param scale  the standard deviation of the weights
	 * @return the weights
	 */
	private StateDictionary seededWeights(ResamplingConfig config, long seed, double scale) {
		Random random = new Random(seed);
		Map<String, PackedCollection> weights = new HashMap<>();
		blockWeightShapes(config, PREFIX).forEach((name, dims) ->
				weights.put(name, randn(shape(dims), 0.0, scale, random).evaluate()));
		return new StateDictionary(weights);
	}

	/**
	 * A collection of standard Gaussian values drawn from a seeded generator.
	 *
	 * @param shape the shape
	 * @param seed  the seed
	 * @return the collection
	 */
	private PackedCollection seeded(TraversalPolicy shape, long seed) {
		return new PackedCollection(shape).randnFill(new Random(seed));
	}

	/**
	 * The values of a collection, flattened.
	 *
	 * @param collection the collection
	 * @return its values
	 */
	private double[] values(PackedCollection collection) {
		return collection.doubleStream().toArray();
	}

	/**
	 * The values of one weight, flattened.
	 *
	 * @param weights the weights
	 * @param key     the weight's key
	 * @return its values
	 */
	private double[] values(StateDictionary weights, String key) {
		return values(weights.require(key));
	}

	/**
	 * Asserts that two value sets agree element by element to within {@code tolerance} of the largest
	 * magnitude among the expected values.
	 *
	 * @param label     what is compared, for the messages
	 * @param expected  the reference values
	 * @param actual    the values under test
	 * @param tolerance the permitted largest difference, relative to the largest expected magnitude
	 */
	private void assertClose(String label, double[] expected, double[] actual, double tolerance) {
		assertEquals(label + ": value count", expected.length, actual.length);
		double magnitude = 0.0;
		double difference = 0.0;
		int worst = 0;
		for (int i = 0; i < expected.length; i++) {
			magnitude = Math.max(magnitude, Math.abs(expected[i]));
			double d = Math.abs(expected[i] - actual[i]);
			if (!(d <= difference)) {
				difference = d;
				worst = i;
			}
		}
		log(label + ": largest |value|=" + magnitude + " largestDifference=" + difference + " at " + worst);
		assertTrue(label + ": element " + worst + " differs by " + difference + " (expected "
				+ expected[worst] + ", got " + actual[worst] + ")", difference <= tolerance * magnitude);
	}

	/**
	 * The resampling transformer layer on the host, in double precision, written out from the
	 * reference definition rather than from either implementation: a DynamicTanh pre-norm, the fused
	 * projection, the two attention maps over the sections {@code Q1 K1 V} and {@code Q2 K2 V}, their
	 * difference with the heads merged, the output projection and the residual; then the DynamicTanh
	 * feed-forward norm, the fused projection, the SwiGLU gate {@code a * silu(gate)}, the output
	 * projection and the residual.
	 *
	 * @param config  the resampling configuration
	 * @param weights the block's weights
	 * @param key     the layer's weight key prefix
	 * @param chunks  the number of attention chunks
	 * @param x       the {@code [chunks, chunk_len, dim]} input, flattened
	 * @return the layer's output, flattened
	 */
	private double[] hostLayer(ResamplingConfig config, StateDictionary weights, String key,
							   int chunks, double[] x) {
		int dim = config.getDim();
		double[] normed = hostDynamicTanh(x, weights, key + ".pre_norm", dim);
		double[] qkv = hostLinear(normed, values(weights, key + ".self_attn.to_qkv.weight"), null, dim);
		double[] first = hostAttentionMap(config, weights, key, chunks, qkv, 0, 1, 2);
		double[] second = hostAttentionMap(config, weights, key, chunks, qkv, 3, 4, 2);
		double[] attended = hostAdd(x, hostLinear(hostMergeDifference(config, chunks, first, second),
				values(weights, key + ".self_attn.to_out.weight"), null, dim));

		int inner = config.getInnerFfDim();
		double[] projected = hostLinear(hostDynamicTanh(attended, weights, key + ".ff_norm", dim),
				values(weights, key + ".ff.ff.0.proj.weight"), values(weights, key + ".ff.ff.0.proj.bias"), dim);
		double[] gated = new double[projected.length / 2];
		for (int row = 0; row < gated.length / inner; row++) {
			for (int i = 0; i < inner; i++) {
				double linear = projected[row * 2 * inner + i];
				double gate = projected[row * 2 * inner + inner + i];
				gated[row * inner + i] = linear * gate / (1.0 + Math.exp(-gate));
			}
		}
		return hostAdd(attended, hostLinear(gated, values(weights, key + ".ff.ff.2.weight"),
				values(weights, key + ".ff.ff.2.bias"), inner));
	}

	/**
	 * DynamicTanh on the host: {@code gamma ⊙ tanh(alpha · x) + beta} over runs of {@code size}
	 * features.
	 *
	 * @param x       the input, flattened
	 * @param weights the block's weights
	 * @param prefix  the key prefix of the norm's {@code alpha}, {@code gamma} and {@code beta}
	 * @param size    the number of features each run holds
	 * @return the normalized values
	 */
	private double[] hostDynamicTanh(double[] x, StateDictionary weights, String prefix, int size) {
		double[] alpha = values(weights, prefix + ".alpha");
		double[] gamma = values(weights, prefix + ".gamma");
		double[] beta = values(weights, prefix + ".beta");
		double[] y = new double[x.length];
		for (int i = 0; i < x.length; i++) {
			int feature = i % size;
			double a = alpha.length == 1 ? alpha[0] : alpha[feature];
			y[i] = gamma[feature] * Math.tanh(a * x[i]) + beta[feature];
		}
		return y;
	}

	/**
	 * A linear projection on the host, {@code y = x Wᵀ + b} for a weight laid out {@code [out, in]}.
	 *
	 * @param x      the input, rows of {@code in} features, flattened
	 * @param weight the weight, flattened
	 * @param bias   the bias, or {@code null} for none
	 * @param in     the number of input features
	 * @return the projected rows, flattened
	 */
	private double[] hostLinear(double[] x, double[] weight, double[] bias, int in) {
		int out = weight.length / in;
		int rows = x.length / in;
		double[] y = new double[rows * out];
		for (int row = 0; row < rows; row++) {
			for (int o = 0; o < out; o++) {
				double sum = bias == null ? 0.0 : bias[o];
				for (int i = 0; i < in; i++) {
					sum += x[row * in + i] * weight[o * in + i];
				}
				y[row * out + o] = sum;
			}
		}
		return y;
	}

	/**
	 * One attention map on the host: the queries of one section of the fused projection attend over
	 * the keys of a second and the values of a third, within each chunk. The query and key rows are
	 * normalized with their DynamicTanh and rotated by their position within the chunk.
	 *
	 * @param config   the resampling configuration
	 * @param weights  the block's weights
	 * @param key      the layer's weight key prefix
	 * @param chunks   the number of attention chunks
	 * @param qkv      the fused projection, {@code [chunks, chunk_len, 5, dim]}, flattened
	 * @param query    the section holding the queries
	 * @param keys     the section holding the keys
	 * @param value    the section holding the values
	 * @return the attended rows, {@code [chunks, heads, chunk_len, head_dim]}, flattened
	 */
	private double[] hostAttentionMap(ResamplingConfig config, StateDictionary weights, String key,
									  int chunks, double[] qkv, int query, int keys, int value) {
		int headDim = config.getDimHead();
		int length = config.getEffectiveChunkSize();
		double[] invFreq = values(weights, key + ".rope.inv_freq");
		double[] q = hostRotate(hostDynamicTanh(hostHeadRows(config, chunks, qkv, query), weights,
				key + ".self_attn.q_norm", headDim), invFreq, headDim, length);
		double[] k = hostRotate(hostDynamicTanh(hostHeadRows(config, chunks, qkv, keys), weights,
				key + ".self_attn.k_norm", headDim), invFreq, headDim, length);
		double[] v = hostHeadRows(config, chunks, qkv, value);

		double[] out = new double[q.length];
		double scale = 1.0 / Math.sqrt(headDim);
		for (int group = 0; group < chunks * config.getHeads(); group++) {
			for (int i = 0; i < length; i++) {
				double[] attention = new double[length];
				double max = Double.NEGATIVE_INFINITY;
				for (int j = 0; j < length; j++) {
					double score = 0.0;
					for (int d = 0; d < headDim; d++) {
						score += q[(group * length + i) * headDim + d] * k[(group * length + j) * headDim + d];
					}
					attention[j] = score * scale;
					max = Math.max(max, attention[j]);
				}
				double total = 0.0;
				for (int j = 0; j < length; j++) {
					attention[j] = Math.exp(attention[j] - max);
					total += attention[j];
				}
				for (int d = 0; d < headDim; d++) {
					double sum = 0.0;
					for (int j = 0; j < length; j++) {
						sum += attention[j] / total * v[(group * length + j) * headDim + d];
					}
					out[(group * length + i) * headDim + d] = sum;
				}
			}
		}
		return out;
	}

	/**
	 * One section of the fused projection laid out one row per head, on the host.
	 *
	 * @param config  the resampling configuration
	 * @param chunks  the number of attention chunks
	 * @param qkv     the fused projection, {@code [chunks, chunk_len, 5, dim]}, flattened
	 * @param section the section
	 * @return the section's rows, {@code [chunks, heads, chunk_len, head_dim]}, flattened
	 */
	private double[] hostHeadRows(ResamplingConfig config, int chunks, double[] qkv, int section) {
		int heads = config.getHeads();
		int headDim = config.getDimHead();
		int dim = config.getDim();
		int length = config.getEffectiveChunkSize();
		double[] rows = new double[chunks * heads * length * headDim];
		for (int c = 0; c < chunks; c++) {
			for (int p = 0; p < length; p++) {
				for (int h = 0; h < heads; h++) {
					for (int d = 0; d < headDim; d++) {
						rows[((c * heads + h) * length + p) * headDim + d] =
								qkv[((c * length + p) * 5 + section) * dim + h * headDim + d];
					}
				}
			}
		}
		return rows;
	}

	/**
	 * The rotary embedding on the host: in every row, feature {@code i} and feature {@code i + n}
	 * ({@code n} being the number of inverse frequencies) are rotated together by the angle
	 * {@code position * invFreq[i]}, the position being the row's index within its chunk; the features
	 * beyond the first {@code 2n} pass through.
	 *
	 * @param rows    rows of {@code headDim} features, {@code chunk_len} consecutive rows per chunk and head
	 * @param invFreq the inverse frequencies
	 * @param headDim the features per row
	 * @param length  the rows per chunk and head
	 * @return the rotated rows
	 */
	private double[] hostRotate(double[] rows, double[] invFreq, int headDim, int length) {
		int n = invFreq.length;
		double[] out = rows.clone();
		for (int row = 0; row < rows.length / headDim; row++) {
			int position = row % length;
			for (int i = 0; i < n; i++) {
				double angle = position * invFreq[i];
				double x1 = rows[row * headDim + i];
				double x2 = rows[row * headDim + i + n];
				out[row * headDim + i] = x1 * Math.cos(angle) - x2 * Math.sin(angle);
				out[row * headDim + i + n] = x2 * Math.cos(angle) + x1 * Math.sin(angle);
			}
		}
		return out;
	}

	/**
	 * The difference of two attention maps with the heads of every position joined, on the host.
	 *
	 * @param config the resampling configuration
	 * @param chunks the number of attention chunks
	 * @param first  the first map, {@code [chunks, heads, chunk_len, head_dim]}, flattened
	 * @param second the second map, the same shape
	 * @return {@code first - second} laid out {@code [chunks, chunk_len, dim]}, flattened
	 */
	private double[] hostMergeDifference(ResamplingConfig config, int chunks,
												double[] first, double[] second) {
		int heads = config.getHeads();
		int headDim = config.getDimHead();
		int length = config.getEffectiveChunkSize();
		double[] merged = new double[first.length];
		for (int c = 0; c < chunks; c++) {
			for (int h = 0; h < heads; h++) {
				for (int p = 0; p < length; p++) {
					for (int d = 0; d < headDim; d++) {
						int row = ((c * heads + h) * length + p) * headDim + d;
						merged[(c * length + p) * heads * headDim + h * headDim + d] = first[row] - second[row];
					}
				}
			}
		}
		return merged;
	}

	/**
	 * Element-wise sum on the host.
	 *
	 * @param a the first values
	 * @param b the second values
	 * @return {@code a + b}
	 */
	private double[] hostAdd(double[] a, double[] b) {
		double[] sum = new double[a.length];
		for (int i = 0; i < a.length; i++) {
			sum[i] = a[i] + b[i];
		}
		return sum;
	}
}
