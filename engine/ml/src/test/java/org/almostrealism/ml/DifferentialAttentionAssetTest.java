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
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.AdapterConfig;
import org.almostrealism.layers.LoRALinear;
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * Holds the layers of {@code /pdsl/differential_attention.pdsl}, which
 * {@link DifferentialAttentionFeatures#differentialSequenceAttention} builds, to the Java assembly they
 * replaced: differential self-attention, two attention maps over one sequence with the second subtracted
 * from the first in proportion to a learned per-head lambda.
 *
 * <p>Every configuration is computed four ways over the same weights and input: by the feature method;
 * by the asset layer built directly through {@link PdslLoader} with the arguments its header documents;
 * by a fixed copy of the Java assembly the feature method built before it loaded the asset
 * ({@link Weights#javaAssembly}), which pins the forward pass to the behavior it had; and by a reference
 * computed on the host in double precision ({@link Weights#hostReference}), which uses no framework block
 * and so pins the numerical contract whatever builds the attention. The framework computations must
 * agree to within float rounding, and with the host reference.</p>
 *
 * <p>The lambdas differ between the heads, so a per-head weighting applied along the wrong axis cannot
 * pass. The configurations cover the three query/key normalizations (none, LayerNorm with biases, RMSNorm
 * with and without biases), full and partial rotation, the value padding mask, and a lambda computed from
 * its learned re-parameterization. Further tests run several forward passes through one compiled block,
 * pass a projection factory that wraps the projections in low-rank adapters, and supply arguments the
 * attention rejects.</p>
 *
 * <p>The backward pass is pinned against central finite differences: each of the five sections of the
 * fused projection, the output projection and the input must receive the gradient the forward pass
 * implies. The Java assembly read its keys, its values and its second map back from stores the branches
 * wrote, so the gradient never reached four of the five sections.</p>
 */
public class DifferentialAttentionAssetTest extends TestSuiteBase
		implements DifferentialAttentionFeatures, ModelTestFeatures {

	/** Classpath location of the differential attention asset. */
	private static final String DIFFERENTIAL_ATTENTION_ASSET = "/pdsl/differential_attention.pdsl";

	/** Batch size the parallel attention supports. */
	private static final int BATCH = 1;

	/** Sequence length of the scaled-down configuration. */
	private static final int SEQ_LEN = 5;

	/** Head count: two, so the per-head layout and the per-head lambda are exercised. */
	private static final int HEADS = 2;

	/** Dimension per head. */
	private static final int DIM_HEAD = 4;

	/** Model dimension. */
	private static final int DIM = HEADS * DIM_HEAD;

	/** Number of dim-wide sections of the fused projection: Q1, K1, K2, V and Q2. */
	private static final int SECTIONS = 5;

	/** Epsilon of the query/key normalizations. */
	private static final double EPSILON = 1e-6;

	/** The lambda of the first head. */
	private static final double LAMBDA_FIRST_HEAD = 0.6;

	/**
	 * The increase of the lambda from one head to the next: the lambdas differ between the heads, so a
	 * weighting along the wrong axis is detected.
	 */
	private static final double LAMBDA_STEP = 0.3;

	/** Valid positions of the padding mask: the last two positions are padding. */
	private static final int PADDING_VALID = 3;

	/** Agreement required between the asset, the feature method and the Java assembly. */
	private static final double ASSEMBLY_TOLERANCE = 1e-6;

	/** Agreement required between a framework computation and the double-precision host reference. */
	private static final double HOST_TOLERANCE = 1e-4;

	/** Step of the central finite differences. */
	private static final double FINITE_DIFFERENCE_STEP = 1e-2;

	/** Agreement required with the finite differences, relative to the largest gradient compared. */
	private static final double GRADIENT_TOLERANCE = 0.02;

	/** Smallest largest-magnitude a gradient may have: below it the gradient is trivially zero. */
	private static final double MINIMUM_GRADIENT = 1e-3;

	/** Differential attention without query/key normalization, every feature of a head rotated. */
	@Test(timeout = 300000)
	public void plainMatchesJavaAssemblyAndHostReference() {
		assertMatches("differential_attention", null, false, true, null);
	}

	/**
	 * A LayerNorm of every query and key row with biases, a rotation of the leading half of each head
	 * and a padding mask: the normalization of the Stable Audio diffusion transformer.
	 */
	@Test(timeout = 300000)
	public void qkLayerNormWithPaddingMaskMatchesJavaAssemblyAndHostReference() {
		assertMatches("differential_attention_qk_layernorm", NormalizationType.LAYER, true, false, PADDING_VALID);
	}

	/** An RMSNorm of every query and key row without biases. */
	@Test(timeout = 300000)
	public void qkRmsNormMatchesJavaAssemblyAndHostReference() {
		assertMatches("differential_attention_qk_rmsnorm", NormalizationType.RMS, false, true, null);
	}

	/** An RMSNorm of every query and key row with biases, and a padding mask. */
	@Test(timeout = 300000)
	public void qkRmsNormWithBiasesAndPaddingMaskMatchesJavaAssemblyAndHostReference() {
		assertMatches("differential_attention_qk_rmsnorm", NormalizationType.RMS, true, false, PADDING_VALID);
	}

	/**
	 * The lambda a model trains is not a constant but its re-parameterization,
	 * {@code exp(λq1 · λk1) − exp(λq2 · λk2) + λinit} per head ({@link #differentialLambda}); bound as that
	 * producer, the feature method still matches the Java assembly and the host reference, which computes
	 * each head's lambda from the same parameters in double precision.
	 */
	@Test(timeout = 300000)
	public void learnedLambdaMatchesJavaAssemblyAndHostReference() {
		int lambdaDim = 3;
		double lambdaInit = 0.2;
		PackedCollection[] parameters = new PackedCollection[4];
		for (int i = 0; i < parameters.length; i++) {
			parameters[i] = random(HEADS, lambdaDim);
		}
		Producer<PackedCollection> lambda = differentialLambda(parameters[0], parameters[1],
				parameters[2], parameters[3], lambdaInit);

		double[][] values = new double[parameters.length][];
		for (int i = 0; i < parameters.length; i++) {
			values[i] = parameters[i].doubleStream().toArray();
		}
		double[] hostLambda = new double[HEADS];
		for (int h = 0; h < HEADS; h++) {
			double first = 0.0;
			double second = 0.0;
			for (int d = 0; d < lambdaDim; d++) {
				first += values[0][h * lambdaDim + d] * values[1][h * lambdaDim + d];
				second += values[2][h * lambdaDim + d] * values[3][h * lambdaDim + d];
			}
			hostLambda[h] = Math.exp(first) - Math.exp(second) + lambdaInit;
		}

		Weights w = new Weights(NormalizationType.LAYER, true, false);
		PackedCollection input = new PackedCollection(shape(BATCH, SEQ_LEN, DIM)).randnFill();
		double[] feature = run(w.feature(ProjectionFactory.dense(), null, lambda), input);
		double[] assembly = run(w.javaAssembly(null, lambda), input);
		double[] host = w.hostReference(input.doubleStream().toArray(), null, hostLambda);

		assertClose("learned lambda: differentialSequenceAttention vs Java assembly", assembly, feature,
				ASSEMBLY_TOLERANCE);
		assertClose("learned lambda: differentialSequenceAttention vs host reference", host, feature,
				HOST_TOLERANCE);
	}

	/**
	 * Every forward pass attends over its own sequence: three passes through one compiled block, over two
	 * different sequences, each match the host reference for their own input, so no pass reads keys,
	 * values or a second map left by the one before.
	 */
	@Test(timeout = 300000)
	public void everyForwardPassAttendsOverItsOwnSequence() {
		Weights w = new Weights(NormalizationType.LAYER, true, false);
		PackedCollection first = new PackedCollection(shape(BATCH, SEQ_LEN, DIM)).randnFill();
		PackedCollection second = new PackedCollection(shape(BATCH, SEQ_LEN, DIM)).randnFill();
		double[] firstExpected = w.hostReference(first.doubleStream().toArray(), null, w.hostLambda());
		double[] secondExpected = w.hostReference(second.doubleStream().toArray(), null, w.hostLambda());

		try (CompiledModel compiled = compile(w.feature(ProjectionFactory.dense(), null, w.lambda()))) {
			double[] firstOutput = compiled.forward(first).doubleStream().toArray();
			double[] secondOutput = compiled.forward(second).doubleStream().toArray();
			double[] firstAgain = compiled.forward(first).doubleStream().toArray();

			assertClose("first pass", firstExpected, firstOutput, HOST_TOLERANCE);
			assertClose("second pass", secondExpected, secondOutput, HOST_TOLERANCE);
			assertClose("third pass", firstExpected, firstAgain, HOST_TOLERANCE);
			assertTrue("the two sequences must attend differently",
					maxDifference(firstOutput, secondOutput) > 1e-3);
		}
	}

	/**
	 * The two projections are the layers of the projection factory the caller passes: a low-rank adapter
	 * factory wraps exactly the fused five-section projection and the output projection, in that order.
	 * Their {@code B} matrices start at zero, so the adapted attention first matches the dense one; once
	 * the fused projection's adapter has a non-zero {@code B}, the same compiled block computes a different
	 * attention, which shows the adapter's weights are read by the graph.
	 */
	@Test(timeout = 300000)
	public void adaptedProjectionsComeFromTheProjectionFactory() {
		Weights w = new Weights(NormalizationType.LAYER, true, false);
		List<LoRALinear> adapters = new ArrayList<>();
		ProjectionFactory adapted = ProjectionFactory.lora(new AdapterConfig().rank(2).alpha(4.0)
				.targets(AdapterConfig.TargetLayer.SELF_ATTENTION_QKV,
						AdapterConfig.TargetLayer.SELF_ATTENTION_OUT), adapters);
		PackedCollection input = new PackedCollection(shape(BATCH, SEQ_LEN, DIM)).randnFill();
		double[] dense = run(w.feature(ProjectionFactory.dense(), null, w.lambda()), input);

		try (CompiledModel compiled = compile(w.feature(adapted, null, w.lambda()))) {
			assertEquals("one adapter per projection", 2, adapters.size());
			assertEquals("the fused projection is adapted first", SECTIONS * DIM,
					adapters.get(0).getOutputShape().getTotalSize() / SEQ_LEN);
			assertEquals("the output projection is adapted second", DIM,
					adapters.get(1).getOutputShape().getTotalSize() / SEQ_LEN);

			double[] untrained = compiled.forward(input).doubleStream().toArray();
			assertClose("adapters with zero B", dense, untrained, ASSEMBLY_TOLERANCE);

			adapters.get(0).getLoraB().fill(0.05);
			double[] trained = compiled.forward(input).doubleStream().toArray();
			assertTrue("a non-zero adapter must change the attention", maxDifference(untrained, trained) > 1e-4);
		}
	}

	/**
	 * The backward pass delivers to the fused projection, the output projection and the input the
	 * gradients the forward pass implies, compared section by section against central finite differences
	 * of {@code L = sum(output * G)} for a fixed output gradient {@code G}. Every one of the five
	 * sections of the fused projection reaches the output: the queries and keys of both maps, and the
	 * values both maps weight. The Java assembly the asset replaced stored the keys, the values and the
	 * second map and read them back as constants, so its backward pass delivered no gradient to the
	 * {@code K1}, {@code K2}, {@code V} and {@code Q2} sections.
	 */
	@Test(timeout = 900000)
	public void everySectionOfTheProjectionReceivesItsGradient() {
		Weights w = new Weights(NormalizationType.LAYER, true, false);
		PackedCollection padding = validPrefix(PADDING_VALID);
		TraversalPolicy sequenceShape = shape(BATCH, SEQ_LEN, DIM);
		PackedCollection input = new PackedCollection(sequenceShape).randnFill();
		PackedCollection outputGradient = new PackedCollection(sequenceShape).randnFill();

		List<PackedCollection> recorded = new ArrayList<>();
		Model model = new Model(sequenceShape, gradientRecorder(recorded));
		model.add(w.feature(ProjectionFactory.dense(), cp(padding), w.lambda()));
		try (CompiledModel compiled = model.compile(true, true)) {
			compiled.forward(input);
			double[] inputGradient = compiled.backward(outputGradient).doubleStream().toArray();
			double[] fusedGradient = gradientOfSize(recorded, SECTIONS * DIM * DIM);
			double[] outputProjectionGradient = gradientOfSize(recorded, DIM * DIM);

			Evaluable<PackedCollection> loss = multiply(cv(sequenceShape, 0), cv(sequenceShape, 1)).sum().get();
			DoubleSupplier objective = () -> loss.evaluate(compiled.forward(input), outputGradient).toDouble();

			double[] fusedNumeric = centralDifferences(w.qkv, FINITE_DIFFERENCE_STEP, objective);
			String[] sections = { "Q1", "K1", "K2", "V", "Q2" };
			int sectionSize = DIM * DIM;
			for (int s = 0; s < SECTIONS; s++) {
				assertGradient(sections[s] + " section of the fused projection",
						Arrays.copyOfRange(fusedNumeric, s * sectionSize, (s + 1) * sectionSize),
						Arrays.copyOfRange(fusedGradient, s * sectionSize, (s + 1) * sectionSize));
			}

			assertGradient("output projection",
					centralDifferences(w.out, FINITE_DIFFERENCE_STEP, objective), outputProjectionGradient);
			assertGradient("input", centralDifferences(input, FINITE_DIFFERENCE_STEP, objective), inputGradient);
		}
	}

	/** The lambda is required: differential attention without one is rejected. */
	@Test(timeout = 60000)
	public void lambdaIsRequired() {
		Weights w = new Weights(null, false, true);
		try {
			w.feature(ProjectionFactory.dense(), null, null);
			Assert.fail("differential attention without a lambda should be rejected");
		} catch (IllegalArgumentException expected) {
			log("rejected: " + expected.getMessage());
		}
	}

	/**
	 * The lambda holds one value per head: a lambda with one value more than the heads, or a single value
	 * for every head, is rejected with an {@link IllegalArgumentException} when the block is built, as the
	 * Java assembly rejected it, rather than read in memory order or broadcast.
	 */
	@Test(timeout = 60000)
	public void lambdaOfAnotherSizeIsRejected() {
		Weights w = new Weights(null, false, true);
		for (Producer<PackedCollection> lambda : List.of(zeros(shape(HEADS + 1)).add(0.5), c(0.5))) {
			try {
				w.feature(ProjectionFactory.dense(), null, lambda);
				Assert.fail("a lambda of shape " + shape(lambda) + " for " + HEADS + " heads should be rejected");
			} catch (IllegalArgumentException expected) {
				log("rejected: " + expected.getMessage());
			}
		}
	}

	/** Parallel attention supports a batch of one; a larger batch is rejected rather than misread. */
	@Test(timeout = 60000)
	public void batchesOfMoreThanOneAreRejected() {
		Weights w = new Weights(null, false, true);
		try {
			differentialSequenceAttention(2, SEQ_LEN, DIM, HEADS, w.qkv, w.out,
					null, null, null, null, w.invFreq, w.lambda());
			Assert.fail("differential attention over a batch of two should be rejected");
		} catch (UnsupportedOperationException expected) {
			log("rejected: " + expected.getMessage());
		}
	}

	/**
	 * Computes one configuration four ways and checks that the asset, the feature method and the Java
	 * assembly agree to within float rounding, and that the feature method matches the host reference.
	 *
	 * @param layer        the asset layer that covers the configuration
	 * @param qkNorm       the query/key normalization family, or {@code null} for none
	 * @param biases       whether the normalization has biases
	 * @param fullRotary   true to rotate every feature of a head, false for the leading half
	 * @param paddingValid the number of leading positions the padding mask marks valid, or {@code null}
	 *                     for no padding mask
	 */
	private void assertMatches(String layer, NormalizationType qkNorm, boolean biases, boolean fullRotary,
							   Integer paddingValid) {
		Weights w = new Weights(qkNorm, biases, fullRotary);
		PackedCollection padding = paddingValid == null ? null : validPrefix(paddingValid);
		Producer<PackedCollection> paddingMask = padding == null ? null : cp(padding);
		PackedCollection input = new PackedCollection(shape(BATCH, SEQ_LEN, DIM)).randnFill();

		double[] feature = run(w.feature(ProjectionFactory.dense(), paddingMask, w.lambda()), input);
		double[] assembly = run(w.javaAssembly(paddingMask, w.lambda()), input);
		double[] asset = run(w.asset(layer, padding), input);
		double[] host = w.hostReference(input.doubleStream().toArray(),
				padding == null ? null : padding.doubleStream().toArray(), w.hostLambda());

		assertClose(layer + ": asset vs Java assembly", assembly, asset, ASSEMBLY_TOLERANCE);
		assertClose(layer + ": differentialSequenceAttention vs Java assembly", assembly, feature,
				ASSEMBLY_TOLERANCE);
		assertClose(layer + ": differentialSequenceAttention vs host reference", host, feature, HOST_TOLERANCE);
	}

	/**
	 * Asserts that an analytic gradient matches its finite-difference estimate to within
	 * {@link #GRADIENT_TOLERANCE} of the estimate's largest magnitude, and that the gradient is not
	 * trivially zero.
	 *
	 * @param label    what the gradient is of, for the messages
	 * @param numeric  the finite-difference estimate
	 * @param analytic the gradient the backward pass delivered
	 */
	private void assertGradient(String label, double[] numeric, double[] analytic) {
		double magnitude = maxDifference(numeric, new double[numeric.length]);
		double difference = maxDifference(numeric, analytic);
		log(label + " gradient: max |numeric|=" + magnitude + " maxDifference=" + difference
				+ " max |analytic|=" + maxDifference(analytic, new double[analytic.length]));
		assertTrue(label + " gradient is trivially zero", magnitude > MINIMUM_GRADIENT);
		assertTrue(label + " gradient differs from the finite differences by " + difference,
				difference <= GRADIENT_TOLERANCE * magnitude);
	}

	/**
	 * Returns the one recorded weight gradient with the given number of values.
	 *
	 * @param recorded the gradients a backward pass recorded, one per weight
	 * @param size     the number of values of the weight
	 * @return the weight's gradient, flattened
	 */
	private static double[] gradientOfSize(List<PackedCollection> recorded, int size) {
		return recorded.stream()
				.filter(gradient -> gradient.getShape().getTotalSize() == size)
				.findFirst()
				.orElseThrow(() -> new AssertionError("No gradient recorded for a weight of " + size + " values"))
				.doubleStream().toArray();
	}

	/**
	 * A {@code [batch, seq_len]} validity mask whose first {@code valid} positions are one and whose
	 * remaining positions are zero, produced by the computation graph.
	 *
	 * @param valid the number of leading valid positions
	 * @return the mask
	 */
	private PackedCollection validPrefix(int valid) {
		PackedCollection mask = new PackedCollection(shape(BATCH, SEQ_LEN));
		lessThan(integers(0, SEQ_LEN), c((double) valid), c(1.0), c(0.0))
				.into(mask.traverseEach()).evaluate();
		return mask;
	}

	/**
	 * Compiles a block over a {@code [batch, seq_len, dim]} sequence for inference.
	 *
	 * @param block the block
	 * @return the compiled model
	 */
	private CompiledModel compile(Block block) {
		Model model = new Model(shape(BATCH, SEQ_LEN, DIM));
		model.add(block);
		return model.compile(false);
	}

	/**
	 * Compiles a block for inference and runs one forward pass.
	 *
	 * @param block the block
	 * @param input the sequence
	 * @return the output, flattened
	 */
	private double[] run(Block block, PackedCollection input) {
		try (CompiledModel compiled = compile(block)) {
			return compiled.forward(input).doubleStream().toArray();
		}
	}

	/**
	 * Asserts that two outputs agree element by element and logs their largest difference.
	 *
	 * @param label     a label for the messages
	 * @param expected  the reference output
	 * @param actual    the output under test
	 * @param tolerance the largest permitted absolute difference
	 */
	private void assertClose(String label, double[] expected, double[] actual, double tolerance) {
		assertEquals(label + " size", expected.length, actual.length);
		double difference = maxDifference(expected, actual);
		log(label + " maxDifference=" + difference);
		assertTrue(label + " differs by " + difference, difference <= tolerance);
	}

	/**
	 * The largest element-wise difference between two arrays of the same length.
	 *
	 * @param a the first array
	 * @param b the second array
	 * @return the largest absolute difference
	 */
	private static double maxDifference(double[] a, double[] b) {
		double difference = 0.0;
		for (int i = 0; i < a.length; i++) {
			difference = Math.max(difference, Math.abs(a[i] - b[i]));
		}
		return difference;
	}

	/**
	 * A small random tensor, scaled so that the projections stay near unit magnitude and neither softmax
	 * saturates.
	 *
	 * @param dims the tensor dimensions
	 * @return the tensor
	 */
	private PackedCollection random(int... dims) {
		PackedCollection value = new PackedCollection(shape(dims)).randnFill();
		return cp(value).multiply(0.25).into(value.traverseEach()).evaluate();
	}

	/**
	 * One set of random differential attention weights and the ways of computing the attention over them.
	 */
	private final class Weights {
		/** The query/key normalization family, or {@code null} for none. */
		private final NormalizationType qkNorm;

		/** Fused projection, {@code [5 * dim, dim]}: the sections Q1, K1, K2, V and Q2, in that order. */
		private final PackedCollection qkv = random(SECTIONS * DIM, DIM);

		/** Output projection, {@code [dim, dim]}. */
		private final PackedCollection out = random(DIM, DIM);

		/** Query normalization scale, shared by both query sections, or {@code null} without normalization. */
		private final PackedCollection qNormWeight;

		/** Query normalization shift, or {@code null}. */
		private final PackedCollection qNormBias;

		/** Key normalization scale, shared by both key sections, or {@code null} without normalization. */
		private final PackedCollection kNormWeight;

		/** Key normalization shift, or {@code null}. */
		private final PackedCollection kNormBias;

		/** Rotary inverse frequencies: one per rotated feature pair. */
		private final PackedCollection invFreq;

		/**
		 * Creates the weights of one configuration.
		 *
		 * @param qkNorm     the query/key normalization family, or {@code null} for none
		 * @param biases     whether the normalization has biases
		 * @param fullRotary true to rotate every feature of a head, false for the leading half
		 */
		Weights(NormalizationType qkNorm, boolean biases, boolean fullRotary) {
			this.qkNorm = qkNorm;
			boolean normalized = qkNorm != null;
			this.qNormWeight = normalized ? new PackedCollection(shape(DIM_HEAD)).randnFill() : null;
			this.qNormBias = normalized && biases ? random(DIM_HEAD) : null;
			this.kNormWeight = normalized ? new PackedCollection(shape(DIM_HEAD)).randnFill() : null;
			this.kNormBias = normalized && biases ? random(DIM_HEAD) : null;
			this.invFreq = fullRotary ? PackedCollection.of(1.0, 0.3) : PackedCollection.of(0.5);
		}

		/**
		 * The per-head lambda as the {@code [heads]} producer the attention is given, produced by the
		 * graph: {@link #LAMBDA_FIRST_HEAD} for the first head, rising by {@link #LAMBDA_STEP} per head.
		 *
		 * @return the lambda producer
		 */
		Producer<PackedCollection> lambda() {
			return integers(0, HEADS).multiply(LAMBDA_STEP).add(LAMBDA_FIRST_HEAD);
		}

		/**
		 * The lambda of each head as the host reference reads it: the values {@link #lambda()} produces
		 * on the device.
		 *
		 * @return one lambda per head
		 */
		double[] hostLambda() {
			double[] lambda = new double[HEADS];
			for (int h = 0; h < HEADS; h++) {
				lambda[h] = LAMBDA_FIRST_HEAD + LAMBDA_STEP * h;
			}
			return lambda;
		}

		/**
		 * The attention built by the feature method.
		 *
		 * @param projections the projection factory
		 * @param paddingMask the padding mask, or {@code null}
		 * @param lambda      the per-head lambda
		 * @return the block
		 */
		Block feature(ProjectionFactory projections, Producer<PackedCollection> paddingMask,
					  Producer<PackedCollection> lambda) {
			return differentialSequenceAttention(BATCH, SEQ_LEN, DIM, HEADS, qkv, out,
					qNormWeight, qNormBias, kNormWeight, kNormBias, invFreq, lambda, projections,
					qkNorm == null ? NormalizationType.LAYER : qkNorm, paddingMask);
		}

		/**
		 * A fixed copy of the Java assembly {@code differentialSequenceAttention} built before it loaded
		 * the asset, with dense projections: the fused projection is split into five per-head paths, the
		 * keys, the values and the second map are written to stores and read back as constants, and the
		 * main path forms the first map and subtracts the lambda-weighted second map. It computes the
		 * forward pass only, and is the reference every configuration's forward pass is held to.
		 *
		 * @param paddingMask the padding mask, or {@code null}
		 * @param diffLambda  the per-head lambda
		 * @return the block
		 */
		Block javaAssembly(Producer<PackedCollection> paddingMask, Producer<PackedCollection> diffLambda) {
			ProjectionFactory projectionFactory = ProjectionFactory.dense();
			TraversalPolicy inputShape = shape(BATCH, SEQ_LEN, DIM);
			TraversalPolicy headShape = shape(BATCH, HEADS, SEQ_LEN, DIM_HEAD);

			SequentialBlock attention = new SequentialBlock(inputShape);
			attention.add(projectionFactory.create(inputShape, qkv,
					AdapterConfig.TargetLayer.SELF_ATTENTION_QKV));

			attention.reshape(BATCH, SEQ_LEN, SECTIONS, DIM);
			List<Block> sections = attention.split(shape(BATCH, SEQ_LEN, 1, DIM), 0);
			SequentialBlock q1 = (SequentialBlock) sections.get(0).reshape(BATCH, SEQ_LEN, HEADS, DIM_HEAD);
			SequentialBlock k1 = (SequentialBlock) sections.get(1).reshape(BATCH, SEQ_LEN, HEADS, DIM_HEAD);
			SequentialBlock k2 = (SequentialBlock) sections.get(2).reshape(BATCH, SEQ_LEN, HEADS, DIM_HEAD);
			SequentialBlock v = (SequentialBlock) sections.get(3).reshape(BATCH, SEQ_LEN, HEADS, DIM_HEAD);
			SequentialBlock q2 = (SequentialBlock) sections.get(4).reshape(BATCH, SEQ_LEN, HEADS, DIM_HEAD);

			q1.permute(0, 2, 1, 3);
			k1.permute(0, 2, 1, 3);
			k2.permute(0, 2, 1, 3);
			v.permute(0, 2, 1, 3);
			q2.permute(0, 2, 1, 3);

			if (qNormWeight != null) {
				q1.add(norm(qkNorm, qNormWeight, qNormBias, EPSILON));
				q2.add(norm(qkNorm, qNormWeight, qNormBias, EPSILON));
				k1.add(norm(qkNorm, kNormWeight, kNormBias, EPSILON));
				k2.add(norm(qkNorm, kNormWeight, kNormBias, EPSILON));
			}

			if (paddingMask != null) {
				v.add(scale(headShape, 2, paddingMask));
			}

			q1.add(applyRotaryPositionEmbedding(headShape, invFreq));
			q2.add(applyRotaryPositionEmbedding(headShape, invFreq));
			k1.add(applyRotaryPositionEmbedding(headShape, invFreq));
			k2.add(applyRotaryPositionEmbedding(headShape, invFreq));

			PackedCollection k1Tensor = new PackedCollection(headShape);
			PackedCollection k2Tensor = new PackedCollection(headShape);
			PackedCollection vTensor = new PackedCollection(headShape);
			PackedCollection map2Tensor = new PackedCollection(headShape);

			k1.andThen(into(k1Tensor));
			k2.andThen(into(k2Tensor));
			v.andThen(into(vTensor));

			q2.add(scaledDotProductAttention(BATCH, SEQ_LEN, HEADS, DIM_HEAD, k2Tensor, vTensor));
			q2.andThen(into(map2Tensor));

			q1.add(scaledDotProductAttention(BATCH, SEQ_LEN, HEADS, DIM_HEAD, k1Tensor, vTensor));
			q1.add(layer("differentialCombine", headShape, headShape, map1 -> {
				CollectionProducer lambda = c(diffLambda).reshape(shape(HEADS))
						.repeat(1, SEQ_LEN)
						.repeat(2, DIM_HEAD)
						.repeat(0, BATCH);
				return c(map1).subtract(lambda.multiply(cp(map2Tensor)));
			}));

			q1.permute(0, 2, 1, 3)
					.reshape(BATCH, SEQ_LEN, DIM);
			q1.add(projectionFactory.create(shape(BATCH, SEQ_LEN, DIM), out,
					AdapterConfig.TargetLayer.SELF_ATTENTION_OUT));

			return attention;
		}

		/**
		 * The asset layer built directly through {@link PdslLoader}, bound as its header documents: the two
		 * projections as dense layers, all-ones masks where a mask is absent, and the per-head lambda.
		 *
		 * @param layer   the asset layer that covers the configuration
		 * @param padding the padding mask, or {@code null}
		 * @return the block
		 */
		Block asset(String layer, PackedCollection padding) {
			TraversalPolicy inputShape = shape(BATCH, SEQ_LEN, DIM);
			ProjectionFactory dense = ProjectionFactory.dense();

			Map<String, Object> args = new HashMap<>();
			args.put("batch", BATCH);
			args.put("seq_len", SEQ_LEN);
			args.put("heads", HEADS);
			args.put("head_dim", DIM_HEAD);
			args.put("qkv_projection", dense.create(inputShape, qkv, AdapterConfig.TargetLayer.SELF_ATTENTION_QKV));
			args.put("out_projection", dense.create(inputShape, out, AdapterConfig.TargetLayer.SELF_ATTENTION_OUT));
			args.put("q_norm_weight", qNormWeight);
			args.put("q_norm_bias", qNormBias);
			args.put("k_norm_weight", kNormWeight);
			args.put("k_norm_bias", kNormBias);
			args.put("inv_freq", invFreq);
			args.put("padding_mask", padding == null ? validPrefix(SEQ_LEN) : padding);
			args.put("key_mask", validPrefix(SEQ_LEN));
			args.put("lambda", lambda());

			PdslLoader loader = new PdslLoader();
			return loader.buildLayer(loader.parseResource(DIFFERENTIAL_ATTENTION_ASSET), layer, inputShape, args);
		}

		/**
		 * Computes differential attention on the host in double precision, directly from its definition:
		 * project the sequence into five sections, form the attention map of {@code Q1} over {@code K1}
		 * and of {@code Q2} over {@code K2} (both weighting the values {@code V}), subtract each head's
		 * second map scaled by that head's lambda from its first, and project.
		 *
		 * @param x       the sequence, row-major {@code [seq_len, dim]}
		 * @param padding the padding mask, or {@code null}
		 * @param lambda  the lambda of each head
		 * @return the expected output, row-major {@code [seq_len, dim]}
		 */
		double[] hostReference(double[] x, double[] padding, double[] lambda) {
			double[] fused = qkv.doubleStream().toArray();
			double[][][] projected = new double[SECTIONS][SEQ_LEN][DIM];
			for (int position = 0; position < SEQ_LEN; position++) {
				for (int row = 0; row < SECTIONS * DIM; row++) {
					double sum = 0.0;
					for (int i = 0; i < DIM; i++) {
						sum += fused[row * DIM + i] * x[position * DIM + i];
					}
					projected[row / DIM][position][row % DIM] = sum;
				}
			}

			double[][] firstMap = attentionMap(projected[0], projected[1], projected[3], padding);
			double[][] secondMap = attentionMap(projected[4], projected[2], projected[3], padding);

			double[] projection = out.doubleStream().toArray();
			double[] result = new double[SEQ_LEN * DIM];
			for (int position = 0; position < SEQ_LEN; position++) {
				for (int o = 0; o < DIM; o++) {
					double sum = 0.0;
					for (int f = 0; f < DIM; f++) {
						double difference = firstMap[position][f] - lambda[f / DIM_HEAD] * secondMap[position][f];
						sum += projection[o * DIM + f] * difference;
					}
					result[position * DIM + o] = sum;
				}
			}
			return result;
		}

		/**
		 * One attention map on the host: for every head, the query and key rows are normalized and rotated
		 * for their position, each query's softmax over the scaled scores weights the value rows, and the
		 * value row of a padded position counts as zero.
		 *
		 * @param queries the query section, {@code [seq_len][dim]}
		 * @param keys    the key section, {@code [seq_len][dim]}
		 * @param values  the value section, {@code [seq_len][dim]}
		 * @param padding the padding mask, or {@code null}
		 * @return the map, {@code [seq_len][dim]} with head {@code h} in features
		 *         {@code h * head_dim} to {@code (h + 1) * head_dim}
		 */
		private double[][] attentionMap(double[][] queries, double[][] keys, double[][] values,
										double[] padding) {
			double[][] map = new double[SEQ_LEN][DIM];
			for (int h = 0; h < HEADS; h++) {
				int from = h * DIM_HEAD;
				double[][] q = new double[SEQ_LEN][];
				double[][] k = new double[SEQ_LEN][];
				for (int position = 0; position < SEQ_LEN; position++) {
					q[position] = rotated(normalized(Arrays.copyOfRange(queries[position], from, from + DIM_HEAD),
							qNormWeight, qNormBias), position);
					k[position] = rotated(normalized(Arrays.copyOfRange(keys[position], from, from + DIM_HEAD),
							kNormWeight, kNormBias), position);
				}

				for (int i = 0; i < SEQ_LEN; i++) {
					double[] scores = new double[SEQ_LEN];
					double largest = Double.NEGATIVE_INFINITY;
					for (int j = 0; j < SEQ_LEN; j++) {
						double dot = 0.0;
						for (int d = 0; d < DIM_HEAD; d++) {
							dot += q[i][d] * k[j][d];
						}
						scores[j] = dot / Math.sqrt(DIM_HEAD);
						largest = Math.max(largest, scores[j]);
					}

					double total = 0.0;
					for (int j = 0; j < SEQ_LEN; j++) {
						scores[j] = Math.exp(scores[j] - largest);
						total += scores[j];
					}

					for (int j = 0; j < SEQ_LEN; j++) {
						double validity = padding == null ? 1.0 : padding[j];
						for (int d = 0; d < DIM_HEAD; d++) {
							map[i][from + d] += scores[j] / total * values[j][from + d] * validity;
						}
					}
				}
			}
			return map;
		}

		/**
		 * One head row normalized with this configuration's normalization family — LayerNorm subtracts the
		 * mean and divides by the standard deviation, RMSNorm divides by the root mean square, and both then
		 * scale and shift — or the row itself without normalization.
		 *
		 * @param row    the head row
		 * @param weight the scale
		 * @param bias   the shift, or {@code null}
		 * @return the normalized row
		 */
		private double[] normalized(double[] row, PackedCollection weight, PackedCollection bias) {
			if (qkNorm == null) {
				return row;
			}

			double[] scale = weight.doubleStream().toArray();
			double[] shift = bias == null ? new double[row.length] : bias.doubleStream().toArray();
			double center = qkNorm == NormalizationType.LAYER ? Arrays.stream(row).average().orElse(0.0) : 0.0;
			double spread = Arrays.stream(row).map(value -> (value - center) * (value - center)).average().orElse(0.0);

			double[] result = new double[row.length];
			for (int d = 0; d < row.length; d++) {
				result[d] = (row[d] - center) / Math.sqrt(spread + EPSILON) * scale[d] + shift[d];
			}
			return result;
		}

		/**
		 * One head row rotated for its position: feature {@code i} is paired with feature {@code i + n}
		 * ({@code n} inverse frequencies) and the pair is rotated by {@code position * invFreq[i]}; the
		 * features beyond the first {@code 2 n} are left alone.
		 *
		 * @param row      the head row
		 * @param position the row's position in the sequence
		 * @return the rotated row
		 */
		private double[] rotated(double[] row, int position) {
			double[] frequencies = invFreq.doubleStream().toArray();
			int pairs = frequencies.length;
			double[] result = row.clone();
			for (int i = 0; i < pairs; i++) {
				double cos = Math.cos(position * frequencies[i]);
				double sin = Math.sin(position * frequencies[i]);
				result[i] = row[i] * cos - row[i + pairs] * sin;
				result[i + pairs] = row[i + pairs] * cos + row[i] * sin;
			}
			return result;
		}
	}
}
