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

package org.almostrealism.hardware.benchmark;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.benchmark.ComputationTemplate.Reduction;
import org.almostrealism.hardware.benchmark.TemplateNode.Binary;
import org.almostrealism.hardware.benchmark.TemplateNode.BinaryOperator;
import org.almostrealism.hardware.benchmark.TemplateNode.Input;
import org.almostrealism.hardware.benchmark.TemplateNode.Scalar;
import org.almostrealism.hardware.benchmark.TemplateNode.Unary;
import org.almostrealism.hardware.benchmark.TemplateNode.UnaryOperator;
import org.almostrealism.hardware.benchmark.WorkloadInstance.Aliasing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Generates, from a seed, the populations the instruction-cache benchmark runs.
 *
 * <p>The main population ({@link #generated(int)}) draws every instance from three finite pools:
 * a pool of {@link ComputationTemplate}s generated from the seed, a pool of shapes, and a pool of
 * constant values. Each draw is Zipf-distributed over a pool (the {@code k}-th entry is drawn with
 * probability proportional to {@code 1 / k^s}), so a few templates and shapes recur often and the
 * rest occasionally, the way a real program reuses a handful of operations heavily. The pool sizes
 * and skews are the tuning: a pool of one entry, or a large skew, makes every instance repeat an
 * earlier one and the cache hit rate approaches 100%; a large pool with no skew makes almost every
 * instance new and the rate approaches 0%. The defaults sit between, and the report states the
 * {@link BenchmarkScenario#structuralRepeatRate() structural repeat rate} they produced alongside
 * the measured hit rate.</p>
 *
 * <p>The targeted scenarios are fixed lists over hand-written templates, each isolating one
 * property of the cache: reuse across shapes, inputs sharing a root, roots on either side of the
 * aggregation size limit, and kernels with argument counts around the Metal limit.</p>
 *
 * <p>Every random choice is drawn from {@link Random} streams seeded from the generator seed, and
 * the input values of each instance from a seed of its own, so the same seed always yields the
 * same populations.</p>
 */
public class WorkloadGenerator {
	/** The number of arguments a Metal kernel can take (see {@code MetalOperator}). */
	public static final int METAL_ARGUMENT_LIMIT = 29;

	/** The default number of templates in the main population's pool. */
	public static final int DEFAULT_TEMPLATES = 24;
	/** The default Zipf exponent of template draws. */
	public static final double DEFAULT_TEMPLATE_SKEW = 1.1;
	/** The default Zipf exponent of shape draws. */
	public static final double DEFAULT_SHAPE_SKEW = 1.0;
	/** The default Zipf exponent of constant draws. */
	public static final double DEFAULT_CONSTANT_SKEW = 1.2;
	/** The default probability that an instance aliases its first two inputs. */
	public static final double DEFAULT_ALIAS_PROBABILITY = 0.15;
	/** The default deepest nesting of a generated template. */
	public static final int DEFAULT_MAX_DEPTH = 4;
	/** The default most distinct tensor inputs of a generated template. */
	public static final int DEFAULT_MAX_INPUTS = 4;
	/** The default probability that a generated operand is a scalar. */
	public static final double DEFAULT_SCALAR_PROBABILITY = 0.3;
	/** The default probability that a generated scalar is an embedded constant. */
	public static final double DEFAULT_LITERAL_PROBABILITY = 0.5;

	/** The rank-1 shape pool, before it is shuffled by the seed. */
	private static final int[] VECTOR_LENGTHS = { 16, 64, 100, 256, 1000, 1024, 1025, 2048, 4096 };
	/** The rank-2 shape pool, as rows and columns, before it is shuffled by the seed. */
	private static final int[][] MATRIX_SHAPES = { { 4, 16 }, { 8, 8 }, { 16, 64 }, { 32, 32 }, { 10, 100 } };
	/** The pool of values an embedded constant is drawn from, none of them zero. */
	private static final double[] CONSTANTS = { 2.0, 0.5, 3.0, -1.5, 4.0, 0.25, -2.0, 1.25 };

	/** The seed every choice derives from. */
	private final long seed;
	/** The number of templates in the main population's template pool. */
	private final int templatePoolSize;
	/** The Zipf exponent of template draws. */
	private final double templateSkew;
	/** The Zipf exponent of shape draws. */
	private final double shapeSkew;
	/** The Zipf exponent of constant draws. */
	private final double constantSkew;
	/** The probability that an instance places its first two inputs on one root. */
	private final double aliasProbability;
	/** The deepest nesting a generated template may have. */
	private final int maxDepth;
	/** The most distinct tensor inputs a generated template may read. */
	private final int maxInputs;
	/** The probability that a generated operand is a scalar rather than a tensor expression. */
	private final double scalarProbability;
	/** The probability that a generated scalar is an embedded constant rather than an input. */
	private final double literalProbability;
	/** The size limit at or below which a root is folded into the aggregate argument. */
	private final int aggregateLimit;

	/** The template pool of the main population, generated from the seed. */
	private final List<ComputationTemplate> templates;
	/** The rank-1 shape pool, in the seed's draw order. */
	private final List<TraversalPolicy> vectorShapes;
	/** The rank-2 shape pool, in the seed's draw order. */
	private final List<TraversalPolicy> matrixShapes;

	/**
	 * Creates a generator with the given parameters.
	 *
	 * @param seed               the seed every choice derives from
	 * @param templatePoolSize   the number of templates in the main population's pool
	 * @param templateSkew       the Zipf exponent of template draws
	 * @param shapeSkew          the Zipf exponent of shape draws
	 * @param constantSkew       the Zipf exponent of constant draws
	 * @param aliasProbability   the probability that an instance aliases its first two inputs
	 * @param maxDepth           the deepest nesting a generated template may have, at least 2
	 * @param maxInputs          the most distinct tensor inputs a generated template may read
	 * @param scalarProbability  the probability that a generated operand is a scalar
	 * @param literalProbability the probability that a generated scalar is an embedded constant
	 * @param aggregateLimit     the size limit at or below which a root is folded
	 */
	public WorkloadGenerator(long seed, int templatePoolSize, double templateSkew, double shapeSkew,
							 double constantSkew, double aliasProbability, int maxDepth, int maxInputs,
							 double scalarProbability, double literalProbability, int aggregateLimit) {
		this.seed = seed;
		this.templatePoolSize = templatePoolSize;
		this.templateSkew = templateSkew;
		this.shapeSkew = shapeSkew;
		this.constantSkew = constantSkew;
		this.aliasProbability = aliasProbability;
		this.maxDepth = Math.max(2, maxDepth);
		this.maxInputs = Math.max(2, maxInputs);
		this.scalarProbability = scalarProbability;
		this.literalProbability = literalProbability;
		this.aggregateLimit = aggregateLimit;

		Random shapes = new Random(seed ^ 0x5DEECE66DL);
		this.vectorShapes = new ArrayList<>();
		for (int length : VECTOR_LENGTHS) vectorShapes.add(new TraversalPolicy(length));
		Collections.shuffle(vectorShapes, shapes);
		this.matrixShapes = new ArrayList<>();
		for (int[] dims : MATRIX_SHAPES) matrixShapes.add(new TraversalPolicy(dims[0], dims[1]));
		Collections.shuffle(matrixShapes, shapes);

		Random structure = new Random(seed);
		this.templates = new ArrayList<>();
		for (int i = 0; i < templatePoolSize; i++) {
			templates.add(new TemplateBuilder(structure).template("t" + i));
		}
	}

	/**
	 * Creates a generator with every distribution parameter at its default.
	 *
	 * @param seed           the seed every choice derives from
	 * @param aggregateLimit the size limit at or below which a root is folded
	 */
	public WorkloadGenerator(long seed, int aggregateLimit) {
		this(seed, DEFAULT_TEMPLATES, DEFAULT_TEMPLATE_SKEW, DEFAULT_SHAPE_SKEW, DEFAULT_CONSTANT_SKEW,
				DEFAULT_ALIAS_PROBABILITY, DEFAULT_MAX_DEPTH, DEFAULT_MAX_INPUTS, DEFAULT_SCALAR_PROBABILITY,
				DEFAULT_LITERAL_PROBABILITY, aggregateLimit);
	}

	/**
	 * Returns the template pool of the main population.
	 *
	 * @return the templates, in pool order
	 */
	public List<ComputationTemplate> getTemplates() { return Collections.unmodifiableList(templates); }

	/**
	 * Returns every scenario the benchmark runs, the main population first.
	 *
	 * @param population the number of instances in the main population
	 * @return the scenarios
	 */
	public List<BenchmarkScenario> scenarios(int population) {
		return List.of(generated(population), sizeGeneric(), aliasing(), aggregateThreshold(), argumentLimit());
	}

	/**
	 * Returns the main population: instances drawn from the template, shape and constant pools.
	 *
	 * @param population the number of instances
	 * @return the scenario
	 */
	public BenchmarkScenario generated(int population) {
		Random draws = new Random(seed * 31 + 7);
		ZipfDistribution templateDistribution = new ZipfDistribution(templates.size(), templateSkew);
		ZipfDistribution constantDistribution = new ZipfDistribution(CONSTANTS.length, constantSkew);
		List<WorkloadInstance> instances = new ArrayList<>();

		for (int i = 0; i < population; i++) {
			ComputationTemplate template = templates.get(templateDistribution.sample(draws));
			List<TraversalPolicy> pool = template.getRank() == 2 ? matrixShapes : vectorShapes;
			TraversalPolicy shape = pool.get(new ZipfDistribution(pool.size(), shapeSkew).sample(draws));

			Aliasing aliasing = template.getTensorSlots() >= 2 && draws.nextDouble() < aliasProbability
					? Aliasing.SHARED_ROOT : Aliasing.DISTINCT;
			instances.add(new WorkloadInstance(template, shape, aliasing,
					scalars(draws, template, constantDistribution), dataSeed(1, i)));
		}

		return new BenchmarkScenario("generated",
				"instances drawn with Zipf-skewed reuse from pools of generated templates, shapes and constants",
				instances);
	}

	/**
	 * Returns the size-generic scenario: the four most frequently drawn templates of the pool, each
	 * run over every shape of its rank in ascending size order and then again in descending order,
	 * with the constants held fixed so that only the shape varies. A kernel that serves every size
	 * compiles once per template; one that is specialized to its size compiles once per shape on the
	 * first pass and hits on the second.
	 *
	 * @return the scenario
	 */
	public BenchmarkScenario sizeGeneric() {
		List<WorkloadInstance> instances = new ArrayList<>();
		int index = 0;

		for (ComputationTemplate template : templates.subList(0, Math.min(4, templates.size()))) {
			List<TraversalPolicy> shapes = new ArrayList<>(template.getRank() == 2 ? matrixShapes : vectorShapes);
			shapes.sort((a, b) -> Integer.compare(a.getTotalSize(), b.getTotalSize()));
			List<TraversalPolicy> descending = new ArrayList<>(shapes);
			Collections.reverse(descending);
			shapes.addAll(descending);

			double[] fixed = template.defaultScalarValues();

			for (TraversalPolicy shape : shapes) {
				instances.add(new WorkloadInstance(template, shape, Aliasing.DISTINCT, fixed, dataSeed(2, index++)));
			}
		}

		return new BenchmarkScenario("size-generic",
				"the most frequent templates over every shape, ascending then descending", instances);
	}

	/**
	 * Returns the aliasing scenario: three templates whose first two inputs are, in turn, separate
	 * roots, disjoint views of one root, overlapping views of one root and one view bound twice, at
	 * a shape small enough to be folded and one too large.
	 *
	 * @return the scenario
	 */
	public BenchmarkScenario aliasing() {
		List<WorkloadInstance> instances = new ArrayList<>();
		Aliasing[] order = { Aliasing.DISTINCT, Aliasing.SHARED_ROOT, Aliasing.DISTINCT,
				Aliasing.OVERLAP, Aliasing.SAME_VIEW, Aliasing.SHARED_ROOT, Aliasing.SAME_VIEW };
		int index = 0;

		for (ComputationTemplate template : List.of(ComputationTemplate.pairSum(), ComputationTemplate.canonical(), ComputationTemplate.productPlusInput())) {
			for (int length : new int[] { 64, 2048 }) {
				for (Aliasing aliasing : order) {
					instances.add(new WorkloadInstance(template, new TraversalPolicy(length), aliasing,
							template.defaultScalarValues(), dataSeed(3, index++)));
				}
			}
		}

		return new BenchmarkScenario("aliasing",
				"two inputs on separate roots, on one root, overlapping, and bound to the same view", instances);
	}

	/**
	 * Returns the aggregation-threshold scenario. In its first part every input is a 64-element view
	 * whose root is 64 elements, exactly at the aggregation limit or one past it, in every
	 * combination over three inputs, so the shape and the signature stay the same while the inputs
	 * that are folded change. In its second part the shape itself steps across the limit with roots
	 * as long as the shape.
	 *
	 * @return the scenario
	 */
	public BenchmarkScenario aggregateThreshold() {
		List<WorkloadInstance> instances = new ArrayList<>();
		int[] rootChoices = { 64, aggregateLimit, aggregateLimit + 1 };
		int index = 0;

		for (ComputationTemplate template : List.of(ComputationTemplate.canonical(), ComputationTemplate.productPlusInput())) {
			for (int combination = 0; combination < 27; combination++) {
				int[] roots = { rootChoices[combination % 3], rootChoices[(combination / 3) % 3],
						rootChoices[combination / 9] };
				instances.add(new WorkloadInstance(template, new TraversalPolicy(64), Aliasing.DISTINCT,
						roots, template.defaultScalarValues(), dataSeed(4, index++)));
			}

			for (int length = aggregateLimit - 2; length <= aggregateLimit + 2; length++) {
				instances.add(new WorkloadInstance(template, new TraversalPolicy(length), Aliasing.DISTINCT,
						template.defaultScalarValues(), dataSeed(4, index++)));
			}
		}

		return new BenchmarkScenario("aggregate-threshold",
				"roots at, below and above the aggregation limit under one shape, and shapes crossing it",
				instances);
	}

	/**
	 * Returns the argument-limit scenario: {@code n} distinct inputs combined in one computation
	 * for counts around {@link #METAL_ARGUMENT_LIMIT}, as a chain of binary additions, as one n-ary
	 * addition, and as a chain alternating multiplication and addition, with inputs too large to
	 * fold (each one a kernel argument) and small enough to fold (all of them one aggregate
	 * argument). Which of these reaches a kernel with {@code n} arguments is decided by
	 * {@link io.almostrealism.compute.Process#optimize()}, which may split the computation into
	 * several kernels. Each computation is run twice, so the second can reuse the first.
	 *
	 * @return the scenario
	 */
	public BenchmarkScenario argumentLimit() {
		List<WorkloadInstance> instances = new ArrayList<>();
		int index = 0;

		for (int inputs : new int[] { 8, 16, 24, 26, 27, 28, 29, 30, 32, 40 }) {
			for (ComputationTemplate template : List.of(ComputationTemplate.chainSum(inputs),
					ComputationTemplate.flatSum(inputs), ComputationTemplate.alternatingChain(inputs))) {
				for (int length : new int[] { 2 * aggregateLimit, 16 }) {
					for (int repeat = 0; repeat < 2; repeat++) {
						instances.add(new WorkloadInstance(template, new TraversalPolicy(length), Aliasing.DISTINCT,
								new double[0], dataSeed(5, index++)));
					}
				}
			}
		}

		return new BenchmarkScenario("argument-limit",
				"n distinct inputs in one computation around the Metal argument limit, unfolded and folded", instances);
	}

	/**
	 * Draws the scalar values of an instance: embedded constants from the constant pool with
	 * Zipf-skewed reuse, scalar inputs uniformly from {@code [0.5, 2)}, which only change data.
	 */
	private double[] scalars(Random draws, ComputationTemplate template, ZipfDistribution constants) {
		double[] values = new double[template.getScalarSlots()];
		for (int slot = 0; slot < values.length; slot++) {
			values[slot] = template.isLiteral(slot)
					? CONSTANTS[constants.sample(draws)]
					: 0.5 + 1.5 * draws.nextDouble();
		}

		return values;
	}

	/** Returns the seed of the input values of one instance of one scenario. */
	private long dataSeed(int scenario, int index) {
		return seed * 1_000_003L + scenario * 100_000L + index;
	}

	/**
	 * Returns a description of the generator parameters, for the report.
	 *
	 * @return the parameters as name and value pairs, in a fixed order
	 */
	public List<String[]> describeParameters() {
		return List.of(
				new String[] { "seed", Long.toString(seed) },
				new String[] { "templatePoolSize", Integer.toString(templatePoolSize) },
				new String[] { "templateSkew", Double.toString(templateSkew) },
				new String[] { "shapeSkew", Double.toString(shapeSkew) },
				new String[] { "constantSkew", Double.toString(constantSkew) },
				new String[] { "aliasProbability", Double.toString(aliasProbability) },
				new String[] { "maxDepth", Integer.toString(maxDepth) },
				new String[] { "maxInputs", Integer.toString(maxInputs) },
				new String[] { "scalarProbability", Double.toString(scalarProbability) },
				new String[] { "literalProbability", Double.toString(literalProbability) },
				new String[] { "aggregateLimit", Integer.toString(aggregateLimit) });
	}

	/** Builds one random template, tracking the slots it allocates. */
	private final class TemplateBuilder {
		/** The stream the template's choices are drawn from. */
		private final Random random;
		/** The number of tensor slots allocated so far. */
		private int tensorSlots;
		/** Whether each scalar slot allocated so far is an embedded constant. */
		private final List<Boolean> literals = new ArrayList<>();

		/**
		 * Creates a builder drawing from the given stream.
		 *
		 * @param random the stream
		 */
		TemplateBuilder(Random random) { this.random = random; }

		/**
		 * Generates a template: a rank, a binary expression of random depth, a reduction and an
		 * optional scale.
		 *
		 * @param id the identity of the template within the pool
		 * @return the template
		 */
		ComputationTemplate template(String id) {
			int rank = random.nextDouble() < 0.3 ? 2 : 1;
			int depth = 2 + random.nextInt(maxDepth - 1);
			TemplateNode body = new Binary(binary(), node(depth - 1), node(random.nextInt(depth)));

			double r = random.nextDouble();
			Reduction reduction = rank == 2
					? (r < 0.3 ? Reduction.NONE : r < 0.65 ? Reduction.SUM : Reduction.ROW_SUM)
					: (r < 0.4 ? Reduction.NONE : Reduction.SUM);
			int scale = reduction != Reduction.NONE && random.nextDouble() < 0.6 ? scalarSlot() : -1;

			boolean[] literalSlots = new boolean[literals.size()];
			for (int i = 0; i < literalSlots.length; i++) literalSlots[i] = literals.get(i);
			return new ComputationTemplate(id, body, reduction, scale, rank, tensorSlots, literalSlots);
		}

		/** Generates an expression whose deepest leaf is {@code depth} levels down. */
		private TemplateNode node(int depth) {
			if (depth == 0) {
				boolean fresh = tensorSlots == 0 || (tensorSlots < maxInputs && random.nextDouble() < 0.75);
				return new Input(fresh ? tensorSlots++ : random.nextInt(tensorSlots));
			}

			double r = random.nextDouble();
			if (r < 0.25) {
				return new Unary(UnaryOperator.values()[random.nextInt(UnaryOperator.values().length)],
						node(depth - 1));
			} else if (r < 0.25 + 0.75 * scalarProbability) {
				int slot = scalarSlot();
				return new Scalar(binary(), node(depth - 1), slot, literals.get(slot));
			} else {
				return new Binary(binary(), node(depth - 1), node(random.nextInt(depth)));
			}
		}

		/** Allocates a scalar slot, an embedded constant with the configured probability. */
		private int scalarSlot() {
			literals.add(random.nextDouble() < literalProbability);
			return literals.size() - 1;
		}

		/** Draws a binary operation. */
		private BinaryOperator binary() {
			return BinaryOperator.values()[random.nextInt(BinaryOperator.values().length)];
		}
	}
}
