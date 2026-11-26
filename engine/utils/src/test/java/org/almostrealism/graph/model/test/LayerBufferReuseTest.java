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

package org.almostrealism.graph.model.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.DefaultCellularLayer;
import org.almostrealism.layers.LayerOutputPool;
import org.almostrealism.layers.MonitorReceptor;
import org.almostrealism.model.Block;
import org.almostrealism.model.BranchBlock;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * Tests inference layer buffer reuse ({@link LayerOutputPool}): a model compiled without
 * backpropagation shares memory between layers whose outputs are never live at the same time.
 *
 * <p>Each equivalence test builds the same graph twice from the same weights, compiles one copy
 * with reuse off and the other with reuse on (and the verification of the assignment on), runs
 * two passes with different inputs through each, and requires bitwise-identical outputs, as well
 * as identical values in any caller-owned tap. It also requires that reuse actually shared memory,
 * so that a graph on which the analysis silently fell back would fail.</p>
 */
public class LayerBufferReuseTest extends TestSuiteBase {
	/** The length of the small outputs, which are eligible for argument aggregation. */
	private static final int SIZE = 32;

	/** The length of the large outputs, which are passed to kernels as direct arguments. */
	private static final int LARGE = 1536;

	/** A chain of dense layers with small outputs shares memory and computes the same values. */
	@Test(timeout = 5 * 60000)
	public void denseChain() {
		Assert.assertTrue("outputs of " + SIZE + " must be aggregation-eligible",
				MemoryDataArgumentMap.maxAggregateLength >= SIZE);

		List<PackedCollection> w = weights(new Random(1), 4, SIZE, SIZE);
		assertEquivalent(taps -> {
			Model model = new Model(shape(SIZE));
			for (PackedCollection weight : w) {
				model.add(dense(weight));
				model.add(silu());
			}
			return model;
		}, 0, inputs(new Random(2), shape(SIZE)));
	}

	/** A chain of dense layers with large outputs shares memory and computes the same values. */
	@Test(timeout = 10 * 60000)
	public void largeDenseChain() {
		List<PackedCollection> w = weights(new Random(3), 3, LARGE, LARGE);
		assertEquivalent(taps -> {
			Model model = new Model(shape(LARGE));
			for (PackedCollection weight : w) {
				model.add(dense(weight));
				model.add(silu());
			}
			return model;
		}, 0, inputs(new Random(4), shape(LARGE)));
	}

	/** Residual connections keep the skipped output alive across the branch they skip. */
	@Test(timeout = 5 * 60000)
	public void residual() {
		List<PackedCollection> w = weights(new Random(5), 4, SIZE, SIZE);
		assertEquivalent(taps -> {
			Model model = new Model(shape(SIZE));
			SequentialBlock s = model.sequential();
			s.add(dense(w.get(0)));
			s.accum(dense(shape(SIZE), w.get(1), false, false));
			s.add(silu());
			s.accum(residualBranch(w.get(2)));
			s.add(dense(w.get(3)));
			return model;
		}, 0, inputs(new Random(6), shape(SIZE)));
	}

	/**
	 * A branch with three children, each tapped into a caller-owned destination, while the main
	 * chain continues after it: the source of the branch is read by every child and by the next
	 * block, and the taps must see the same values as without reuse.
	 */
	@Test(timeout = 5 * 60000)
	public void branchWithTaps() {
		List<PackedCollection> w = weights(new Random(7), 6, SIZE, SIZE);
		assertEquivalent(taps -> {
			Model model = new Model(shape(SIZE));
			SequentialBlock s = model.sequential();
			s.add(dense(w.get(0)));

			BranchBlock branch = new BranchBlock(shape(SIZE));
			for (int k = 0; k < 3; k++) {
				SequentialBlock child = branch.append(new SequentialBlock(shape(SIZE)));
				child.add(dense(w.get(1 + k)));
				child.add(silu());
				PackedCollection tap = new PackedCollection(shape(SIZE));
				taps.add(tap);
				child.andThen(tap);
			}

			s.add(branch);
			s.add(dense(w.get(4)));
			s.add(silu());
			s.add(dense(w.get(5)));
			return model;
		}, 3, inputs(new Random(8), shape(SIZE)));
	}

	/** {@code accum(List)} and {@code concat(List)} keep each child live until it is combined. */
	@Test(timeout = 5 * 60000)
	public void accumAndConcatOfLists() {
		List<PackedCollection> w = weights(new Random(9), 6, SIZE, SIZE);
		assertEquivalent(taps -> {
			Model model = new Model(shape(SIZE));
			SequentialBlock s = model.sequential();
			s.add(dense(w.get(0)));
			s.accum(List.of(dense(shape(SIZE), w.get(1), false, false),
					dense(shape(SIZE), w.get(2), false, false)));
			s.add(silu());
			s.add(dense(w.get(3)));
			s.concat(List.of(dense(shape(SIZE), w.get(4), false, false),
					dense(shape(SIZE), w.get(5), false, false)));
			return model;
		}, 0, inputs(new Random(10), shape(SIZE)));
	}

	/** {@code product} of two blocks of the same input. */
	@Test(timeout = 5 * 60000)
	public void product() {
		List<PackedCollection> w = weights(new Random(11), 4, SIZE, SIZE);
		assertEquivalent(taps -> {
			Model model = new Model(shape(SIZE));
			SequentialBlock s = model.sequential();
			s.add(dense(w.get(0)));
			s.product(dense(shape(SIZE), w.get(1), false, false),
					dense(shape(SIZE), w.get(2), false, false));
			s.add(dense(w.get(3)));
			return model;
		}, 0, inputs(new Random(12), shape(SIZE)));
	}

	/**
	 * Compose layers whose auxiliary branches hang off an auxiliary input of the model, and are
	 * reached by a {@code split} of a projection, as cross-attention's keys and values are.
	 */
	@Test(timeout = 5 * 60000)
	public void composeFromAuxiliaryInputWithSplit() {
		List<PackedCollection> w = weights(new Random(13), 3, SIZE, SIZE);
		PackedCollection projection = randn(shape(2 * SIZE, SIZE), 0.0, 1.0, new Random(14)).evaluate();
		assertEquivalent(taps -> {
			Model model = new Model(shape(SIZE));
			SequentialBlock context = new SequentialBlock(shape(SIZE));
			model.addInput(context);

			SequentialBlock branch = context.branch();
			branch.add(dense(projection));
			branch.reshape(2, SIZE);
			List<Block> halves = branch.split(shape(1, SIZE), 0);
			Block a = halves.get(0).reshape(SIZE);
			Block b = halves.get(1).reshape(SIZE);

			SequentialBlock main = model.sequential();
			main.add(dense(w.get(0)));
			main.add(silu());
			main.add(compose("a", a, (in, other) -> multiply(in, other)));
			main.add(dense(w.get(1)));
			main.add(silu());
			main.add(compose("b", b, (in, other) -> add(in, other)));
			main.add(dense(w.get(2)));
			main.add(silu());
			return model;
		}, 0, inputs(new Random(15), shape(SIZE), shape(SIZE)));
	}

	/**
	 * A compose layer whose branch belongs to another model, compiled after the compose layer's
	 * own model, still reads the record the branch writes when its model runs: the record must
	 * not be bypassed or released by the inference compile of either model. The output is
	 * compared with the product computed on the host, with reuse off and on.
	 */
	@Test(timeout = 5 * 60000)
	public void composeWithBranchInLaterModel() {
		List<PackedCollection> w = weights(new Random(27), 2, SIZE, SIZE);
		PackedCollection xa = randn(shape(SIZE), 0.0, 1.0, new Random(28)).evaluate();
		PackedCollection xb = randn(shape(SIZE), 0.0, 1.0, new Random(29)).evaluate();

		double[] a = multiply(w.get(0), xa);
		double[] b = multiply(w.get(1), xb);
		double[] expected = new double[SIZE];
		for (int i = 0; i < SIZE; i++) expected[i] = a[i] * b[i];

		Assert.assertArrayEquals(expected, composeAcrossModels(w, false, xa, xb), 1e-5);
		Assert.assertArrayEquals(expected, composeAcrossModels(w, true, xa, xb), 1e-5);
	}

	/**
	 * Returns the product of a {@code (SIZE, SIZE)} matrix and a vector, computed on the host as
	 * the reference for a test.
	 *
	 * @param m the matrix
	 * @param x the vector
	 * @return the product
	 */
	private double[] multiply(PackedCollection m, PackedCollection x) {
		double[] mv = m.toArray();
		double[] xv = x.toArray();
		double[] result = new double[SIZE];
		for (int i = 0; i < SIZE; i++) {
			for (int j = 0; j < SIZE; j++) {
				result[i] += mv[i * SIZE + j] * xv[j];
			}
		}
		return result;
	}

	/**
	 * Builds a model whose last layer is the branch of a compose layer in a second model,
	 * compiles the second model and then the first, runs the first and then the second, and
	 * returns the second model's output.
	 *
	 * @param w     the weights of the branch layer and of the second model's dense layer
	 * @param reuse whether layer buffer reuse is on
	 * @param xa    the input of the first model
	 * @param xb    the input of the second model
	 * @return the output of the second model
	 */
	private double[] composeAcrossModels(List<PackedCollection> w, boolean reuse,
										 PackedCollection xa, PackedCollection xb) {
		Model source = new Model(shape(SIZE));
		CellularLayer branch = dense(shape(SIZE), w.get(0), false, false);
		source.add(branch);

		Model reader = new Model(shape(SIZE));
		reader.add(dense(w.get(1)));
		reader.add(compose("c", branch, (in, other) -> multiply(in, other)));

		CompiledModel compiledReader = compile(reader, reuse);
		CompiledModel compiledSource = compile(source, reuse);
		try {
			compiledSource.forward(xa);
			return compiledReader.forward(xb).toArray();
		} finally {
			compiledSource.destroy();
			compiledReader.destroy();
		}
	}

	/**
	 * With a monitor on each layer, as {@link org.almostrealism.hardware.HardwareFeatures#outputMonitoring}
	 * installs one, the monitor reads the layer's output in the same operation list that writes it;
	 * the analysis sees the read and the outputs are still shared.
	 */
	@Test(timeout = 5 * 60000)
	public void outputMonitoring() {
		List<PackedCollection> w = weights(new Random(16), 3, SIZE, SIZE);
		assertEquivalent(taps -> {
			List<DefaultCellularLayer> layers = new ArrayList<>();
			Model model = chain(w, layers);
			for (DefaultCellularLayer layer : layers) {
				layer.setMonitor(new MonitorReceptor(layer.getName(),
						layer.getInputShape(), layer.getOutputShape()));
			}
			return model;
		}, 0, inputs(new Random(17), shape(SIZE)));
	}

	/**
	 * A layer's output that shares memory cannot be read after a pass; a retained layer keeps
	 * an output of its own, which holds the same values as the layer's output without reuse.
	 */
	@Test(timeout = 5 * 60000)
	public void getOutputOfSharedLayerThrowsAndRetainedOutputIsKept() {
		List<PackedCollection> w = weights(new Random(18), 4, SIZE, SIZE);
		PackedCollection x = randn(shape(SIZE), 0.0, 1.0, new Random(19)).evaluate();

		List<DefaultCellularLayer> off = new ArrayList<>();
		double[] expected;
		CompiledModel offCompiled = compile(chain(w, off), false);
		try {
			offCompiled.forward(x);
			expected = off.get(1).getOutput().toArray();
		} finally {
			offCompiled.destroy();
		}

		List<DefaultCellularLayer> on = new ArrayList<>();
		Model model = chain(w, on);
		on.get(1).retainOutput();
		CompiledModel onCompiled = compile(model, true);
		try {
			onCompiled.forward(x);
			Assert.assertFalse(on.get(1).isOutputShared());
			Assert.assertArrayEquals(expected, on.get(1).getOutput().toArray(), 0.0);

			DefaultCellularLayer shared = on.stream().filter(DefaultCellularLayer::isOutputShared)
					.findFirst().orElseThrow(() -> new AssertionError("no layer output is shared"));
			try {
				shared.getOutput();
				Assert.fail("getOutput() of a shared layer output must throw");
			} catch (IllegalStateException e) {
				Assert.assertTrue(e.getMessage(), e.getMessage().contains("retainOutput"));
			}
		} finally {
			onCompiled.destroy();
		}
	}

	/** Retaining a model's layer outputs compiles it with one buffer per layer. */
	@Test(timeout = 5 * 60000)
	public void retainedModelIsNotPooled() {
		List<DefaultCellularLayer> layers = new ArrayList<>();
		Model model = chain(weights(new Random(20), 3, SIZE, SIZE), layers);
		model.setLayerOutputsRetained(true);

		CompiledModel compiled = compile(model, true);
		try {
			Assert.assertNull(compiled.getLayerOutputPool());
			compiled.forward(randn(shape(SIZE), 0.0, 1.0, new Random(21)).evaluate());
			for (DefaultCellularLayer layer : layers) {
				Assert.assertFalse(layer.isOutputShared());
				Assert.assertNotNull(layer.getOutput());
			}
		} finally {
			compiled.destroy();
		}
	}

	/**
	 * A model compiled with backpropagation has no pool, and every layer keeps a distinct
	 * output buffer of its own.
	 */
	@Test(timeout = 5 * 60000)
	public void backpropCompileHasNoPooledLayers() {
		List<DefaultCellularLayer> layers = new ArrayList<>();
		Model model = chain(weights(new Random(22), 3, SIZE, SIZE), layers);

		CompiledModel compiled = model.compile(true);
		try {
			Assert.assertNull(compiled.getLayerOutputPool());
			compiled.forward(randn(shape(SIZE), 0.0, 1.0, new Random(23)).evaluate());

			List<Object> memories = new ArrayList<>();
			for (DefaultCellularLayer layer : layers) {
				Assert.assertFalse(layer.isOutputShared());
				Assert.assertNull(layer.getOutputPool());
				PackedCollection output = layer.getOutput();
				Assert.assertNotNull(output);
				for (Object other : memories) {
					Assert.assertNotSame(other, output.getMem());
				}
				memories.add(output.getMem());
			}
		} finally {
			compiled.destroy();
		}
	}

	/**
	 * A model whose layer outputs were shared by an inference compile cannot be compiled again,
	 * since a later compile would read and write memory another compiled model owns.
	 */
	@Test(timeout = 5 * 60000)
	public void recompilingAPooledModelThrows() {
		Model model = chain(weights(new Random(24), 3, SIZE, SIZE), new ArrayList<>());
		CompiledModel compiled = compile(model, true);
		try {
			Assert.assertTrue(compiled.getLayerOutputPool().getSharedLayerCount() > 0);
			try {
				model.compile(true);
				Assert.fail("compiling a model whose layer outputs are shared must throw");
			} catch (IllegalStateException e) {
				Assert.assertTrue(e.getMessage(), e.getMessage().contains("cannot be compiled again"));
			}
		} finally {
			compiled.destroy();
		}
	}

	/**
	 * An operation that does not expose the memory it touches makes the compile keep one buffer
	 * per layer, rather than guess that it touches nothing; the results are unchanged.
	 */
	@Test(timeout = 5 * 60000)
	public void opaqueOperationFallsBack() {
		List<PackedCollection> w = weights(new Random(25), 3, SIZE, SIZE);
		Function<List<PackedCollection>, Model> build = taps -> {
			List<DefaultCellularLayer> layers = new ArrayList<>();
			Model model = chain(w, layers);
			Receptor<PackedCollection> opaque = in -> () -> () -> { };
			layers.get(1).andThen(opaque);
			return model;
		};

		PackedCollection x = randn(shape(SIZE), 0.0, 1.0, new Random(26)).evaluate();
		double[] expected = forward(build.apply(new ArrayList<>()), false, x);

		CompiledModel compiled = compile(build.apply(new ArrayList<>()), true);
		try {
			LayerOutputPool pool = compiled.getLayerOutputPool();
			Assert.assertNotNull(pool.getFallbackReason());
			Assert.assertEquals(0, pool.getSharedLayerCount());
			Assert.assertArrayEquals(expected, compiled.forward(x).toArray(), 0.0);
		} finally {
			compiled.destroy();
		}
	}

	/**
	 * A monitor with a consumer may keep or touch any memory, so it is opaque and the compile
	 * keeps one buffer per layer; the consumer sees the same values as without reuse.
	 */
	@Test(timeout = 5 * 60000)
	public void monitorWithConsumerFallsBack() {
		List<PackedCollection> w = weights(new Random(36), 3, SIZE, SIZE);
		PackedCollection x = randn(shape(SIZE), 0.0, 1.0, new Random(37)).evaluate();

		List<double[]> expected = new ArrayList<>();
		List<DefaultCellularLayer> off = new ArrayList<>();
		Model offModel = chain(w, off);
		off.get(1).setMonitor(new MonitorReceptor(out -> expected.add(out.toArray())));
		CompiledModel offCompiled = compile(offModel, false);
		try {
			offCompiled.forward(x);
		} finally {
			offCompiled.destroy();
		}

		List<double[]> actual = new ArrayList<>();
		List<DefaultCellularLayer> on = new ArrayList<>();
		Model onModel = chain(w, on);
		on.get(1).setMonitor(new MonitorReceptor(out -> actual.add(out.toArray())));
		CompiledModel onCompiled = compile(onModel, true);
		try {
			LayerOutputPool pool = onCompiled.getLayerOutputPool();
			Assert.assertNotNull(pool.getFallbackReason());
			Assert.assertEquals(0, pool.getSharedLayerCount());
			onCompiled.forward(x);
		} finally {
			onCompiled.destroy();
		}

		Assert.assertEquals(1, expected.size());
		Assert.assertEquals(1, actual.size());
		Assert.assertArrayEquals(expected.get(0), actual.get(0), 0.0);
	}

	/**
	 * A model whose compile fell back, so that no output is shared, can be compiled again; the
	 * outputs the first compile's pool created are replaced rather than reused, so destroying the
	 * first compiled model, which releases its pool, leaves the second one computing correct values.
	 */
	@Test(timeout = 5 * 60000)
	public void recompilingAFallbackModelReplacesPooledOutputs() {
		List<PackedCollection> w = weights(new Random(30), 3, SIZE, SIZE);
		PackedCollection x = randn(shape(SIZE), 0.0, 1.0, new Random(31)).evaluate();

		List<DefaultCellularLayer> off = new ArrayList<>();
		double[] expected;
		double[] expectedLayer;
		CompiledModel offCompiled = compile(opaqueChain(w, off), false);
		try {
			expected = offCompiled.forward(x).toArray();
			expectedLayer = off.get(1).getOutput().toArray();
		} finally {
			offCompiled.destroy();
		}

		List<DefaultCellularLayer> layers = new ArrayList<>();
		Model model = opaqueChain(w, layers);
		CompiledModel first = compile(model, true);
		LayerOutputPool firstPool = first.getLayerOutputPool();
		List<PackedCollection> firstOutputs = new ArrayList<>();
		try {
			Assert.assertNotNull(firstPool.getFallbackReason());
			Assert.assertEquals(0, firstPool.getSharedLayerCount());
			for (DefaultCellularLayer layer : layers) {
				Assert.assertSame(firstPool, layer.getOutputPool());
				firstOutputs.add(layer.getOutput());
			}
		} finally {
			first.destroy();
		}

		CompiledModel second = compile(model, true);
		try {
			LayerOutputPool secondPool = second.getLayerOutputPool();
			Assert.assertNotSame(firstPool, secondPool);
			for (int i = 0; i < layers.size(); i++) {
				Assert.assertSame(secondPool, layers.get(i).getOutputPool());
				Assert.assertNotSame(firstOutputs.get(i), layers.get(i).getOutput());
			}

			Assert.assertArrayEquals(expected, second.forward(x).toArray(), 0.0);
			Assert.assertArrayEquals(expectedLayer, layers.get(1).getOutput().toArray(), 0.0);
		} finally {
			second.destroy();
		}
	}

	/**
	 * An inference compile with reuse skips the copy of a compose layer's auxiliary branch into
	 * the record the backward pass reads, but keeps the record: a later compile of the same model
	 * with reuse off copies into it again, and computes the same output as a model only ever
	 * compiled with reuse off. The compile with reuse falls back, so that no output is shared and
	 * the model may be compiled again.
	 */
	@Test(timeout = 5 * 60000)
	public void composeRecordSurvivesInferenceCompile() {
		List<PackedCollection> w = weights(new Random(32), 2, SIZE, SIZE);
		PackedCollection projection = randn(shape(SIZE, SIZE), 0.0, 1.0, new Random(33)).evaluate();
		PackedCollection x = randn(shape(SIZE), 0.0, 1.0, new Random(34)).evaluate();
		PackedCollection context = randn(shape(SIZE), 0.0, 1.0, new Random(35)).evaluate();

		CompiledModel reference = compile(composeModel(w, projection), false);
		double[] expected;
		try {
			expected = reference.forward(x, context).toArray();
		} finally {
			reference.destroy();
		}

		Model model = composeModel(w, projection);
		CompiledModel pooled = compile(model, true);
		try {
			Assert.assertNotNull(pooled.getLayerOutputPool().getFallbackReason());
			Assert.assertArrayEquals(expected, pooled.forward(x, context).toArray(), 0.0);
		} finally {
			pooled.destroy();
		}

		CompiledModel unpooled = compile(model, false);
		try {
			Assert.assertNull(unpooled.getLayerOutputPool());
			Assert.assertArrayEquals(expected, unpooled.forward(x, context).toArray(), 0.0);
		} finally {
			unpooled.destroy();
		}
	}

	/**
	 * Builds a model with an auxiliary input whose projection is the branch of a compose layer,
	 * and an opaque receptor after the first dense layer so that an inference compile falls back.
	 *
	 * @param w          the weights of the two dense layers of the main chain
	 * @param projection the weight of the auxiliary input's projection
	 * @return the model
	 */
	private Model composeModel(List<PackedCollection> w, PackedCollection projection) {
		Model model = new Model(shape(SIZE));
		SequentialBlock context = new SequentialBlock(shape(SIZE));
		model.addInput(context);
		SequentialBlock branch = context.branch();
		branch.add(dense(projection));

		DefaultCellularLayer first = (DefaultCellularLayer) dense(shape(SIZE), w.get(0), false, false);
		Receptor<PackedCollection> opaque = in -> () -> () -> { };
		first.andThen(opaque);

		SequentialBlock main = model.sequential();
		main.add(first);
		main.add(silu());
		main.add(compose("c", branch, (in, other) -> multiply(in, other)));
		main.add(dense(w.get(1)));
		return model;
	}

	/**
	 * Builds the chain of {@link #chain(List, List)} with an opaque receptor after its second
	 * dense layer, so that an inference compile with reuse falls back to one buffer per layer.
	 *
	 * @param w      the weights of the dense layers
	 * @param layers receives the dense layers
	 * @return the model
	 */
	private Model opaqueChain(List<PackedCollection> w, List<DefaultCellularLayer> layers) {
		Model model = chain(w, layers);
		Receptor<PackedCollection> opaque = in -> () -> () -> { };
		layers.get(1).andThen(opaque);
		return model;
	}

	/**
	 * Builds a chain of dense layers and activations, recording each dense layer.
	 *
	 * @param w      the weights of the dense layers
	 * @param layers receives the dense layers
	 * @return the model
	 */
	private Model chain(List<PackedCollection> w, List<DefaultCellularLayer> layers) {
		Model model = new Model(shape(SIZE));
		for (PackedCollection weight : w) {
			CellularLayer layer = dense(shape(SIZE), weight, false, false);
			layers.add((DefaultCellularLayer) layer);
			model.add(layer);
			model.add(silu());
		}
		return model;
	}

	/**
	 * Builds a branch block for a residual connection: a dense layer and an activation.
	 *
	 * @param w the weight of the dense layer
	 * @return the block
	 */
	private Block residualBranch(PackedCollection w) {
		SequentialBlock block = new SequentialBlock(shape(SIZE));
		block.add(dense(w));
		block.add(silu());
		return block;
	}

	/**
	 * Builds the graph twice, compiles it for inference with reuse off and on, runs two passes
	 * with different inputs through each, and requires identical outputs and taps, and that reuse
	 * shared memory and verified the assignment.
	 *
	 * @param build    builds the model, adding any tap destinations to the given list
	 * @param tapCount the number of taps the builder adds
	 * @param passes   the inputs of each pass: the primary input followed by any auxiliary inputs
	 */
	private void assertEquivalent(Function<List<PackedCollection>, Model> build, int tapCount,
								  List<PackedCollection[]> passes) {
		List<double[]> expected = run(build, false, tapCount, passes);
		List<double[]> actual = run(build, true, tapCount, passes);

		Assert.assertEquals(expected.size(), actual.size());
		for (int i = 0; i < expected.size(); i++) {
			Assert.assertArrayEquals("result " + i, expected.get(i), actual.get(i), 0.0);
		}
	}

	/**
	 * Compiles the model built by {@code build} for inference and runs the given passes,
	 * returning the output and taps after each.
	 *
	 * @param build    builds the model
	 * @param reuse    whether layer buffer reuse is on
	 * @param tapCount the number of taps the builder adds
	 * @param passes   the inputs of each pass
	 * @return the output and the taps of each pass, in order
	 */
	private List<double[]> run(Function<List<PackedCollection>, Model> build, boolean reuse,
							   int tapCount, List<PackedCollection[]> passes) {
		List<PackedCollection> taps = new ArrayList<>();
		CompiledModel compiled = compile(build.apply(taps), reuse);
		Assert.assertEquals(tapCount, taps.size());

		try {
			LayerOutputPool pool = compiled.getLayerOutputPool();
			if (reuse) {
				Assert.assertNotNull(pool);
				Assert.assertNull(pool.getFallbackReason(), pool.getFallbackReason());
				Assert.assertTrue("no layer output is shared", pool.getSharedLayerCount() > 0);
				Assert.assertTrue("pooledBytes=" + pool.getPooledBytes() +
								" layerOutputBytes=" + pool.getLayerOutputBytes(),
						pool.getPooledBytes() < pool.getLayerOutputBytes());
			} else {
				Assert.assertNull(pool);
			}

			List<double[]> results = new ArrayList<>();
			for (PackedCollection[] in : passes) {
				PackedCollection[] aux = new PackedCollection[in.length - 1];
				for (int i = 0; i < aux.length; i++) aux[i] = in[i + 1];
				results.add(compiled.forward(in[0], aux).toArray());
				for (PackedCollection tap : taps) {
					results.add(tap.toArray());
				}
			}

			return results;
		} finally {
			compiled.destroy();
		}
	}

	/**
	 * Compiles a model for inference and returns the output of one pass.
	 *
	 * @param model the model
	 * @param reuse whether layer buffer reuse is on
	 * @param x     the input
	 * @return the output values
	 */
	private double[] forward(Model model, boolean reuse, PackedCollection x) {
		CompiledModel compiled = compile(model, reuse);
		try {
			return compiled.forward(x).toArray();
		} finally {
			compiled.destroy();
		}
	}

	/**
	 * Compiles a model for inference with layer buffer reuse on or off, with the verification
	 * of the assignment on.
	 *
	 * @param model the model
	 * @param reuse whether layer buffer reuse is on
	 * @return the compiled model
	 */
	private CompiledModel compile(Model model, boolean reuse) {
		boolean enabled = CompiledModel.enableLayerBufferReuse;
		boolean verify = CompiledModel.enableLayerBufferReuseVerification;

		try {
			CompiledModel.enableLayerBufferReuse = reuse;
			CompiledModel.enableLayerBufferReuseVerification = true;
			return model.compile(false);
		} finally {
			CompiledModel.enableLayerBufferReuse = enabled;
			CompiledModel.enableLayerBufferReuseVerification = verify;
		}
	}

	/**
	 * Creates random weight matrices.
	 *
	 * @param random the source of the values
	 * @param count  the number of matrices
	 * @param nodes  the output size of each
	 * @param size   the input size of each
	 * @return the matrices
	 */
	private List<PackedCollection> weights(Random random, int count, int nodes, int size) {
		List<PackedCollection> result = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			result.add(randn(shape(nodes, size), 0.0, 1.0 / Math.sqrt(size), random).evaluate());
		}
		return result;
	}

	/**
	 * Creates the inputs of two passes, each with one input of every given shape.
	 *
	 * @param random the source of the values
	 * @param shapes the shape of each input of a pass
	 * @return the inputs of the two passes
	 */
	private List<PackedCollection[]> inputs(Random random, TraversalPolicy... shapes) {
		List<PackedCollection[]> passes = new ArrayList<>();
		for (int p = 0; p < 2; p++) {
			PackedCollection[] in = new PackedCollection[shapes.length];
			for (int i = 0; i < shapes.length; i++) {
				in[i] = randn(shapes[i], 0.0, 1.0, random).evaluate();
			}
			passes.add(in);
		}
		return passes;
	}
}
