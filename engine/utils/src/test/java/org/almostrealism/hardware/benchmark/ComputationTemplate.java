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

import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.hardware.benchmark.TemplateNode.Binary;
import org.almostrealism.hardware.benchmark.TemplateNode.BinaryOperator;
import org.almostrealism.hardware.benchmark.TemplateNode.Input;
import org.almostrealism.hardware.benchmark.TemplateNode.Unary;
import org.almostrealism.hardware.benchmark.TemplateNode.UnaryOperator;

import java.util.ArrayList;
import java.util.List;

/**
 * The structure of one family of generated computations: an element-wise expression over
 * tensor inputs and scalars, an optional reduction, and an optional division of the result by a
 * scalar. {@code sum(x * (y + sin(z))) / k} is one template; every {@link WorkloadInstance} of it
 * binds its own shape, inputs and constant values.
 *
 * <p>A template knows how many tensor slots and scalar slots it reads, so an instance can bind
 * them, and which tensor slots are distinct inputs, so an instance can place two of them on one
 * root to probe aliasing.</p>
 */
public class ComputationTemplate {
	/** How the element-wise result is reduced. */
	public enum Reduction {
		/** The element-wise result is the output. */
		NONE,
		/** All elements are summed to one value. */
		SUM,
		/** Each row of a rank-2 shape is summed, giving one value per row. */
		ROW_SUM
	}

	/** The values {@link #defaultScalarValues()} cycles through, none of them zero. */
	private static final double[] DEFAULT_SCALARS = { 2.0, 0.5, 3.0 };

	/** The identity of this template within the pool it was generated in. */
	private final String id;
	/** The element-wise expression. */
	private final TemplateNode body;
	/** The reduction applied to the expression. */
	private final Reduction reduction;
	/** The scalar slot the reduced result is divided by, or -1 for none. */
	private final int scaleSlot;
	/** Whether the scale is embedded as a constant rather than read from an input. */
	private final boolean scaleLiteral;
	/** The rank of the shapes the template is instantiated over. */
	private final int rank;
	/** The number of tensor slots the expression reads. */
	private final int tensorSlots;
	/** The number of scalar slots the expression and scale read. */
	private final int scalarSlots;
	/** Which scalar slots are embedded constants, by slot index. */
	private final boolean[] literalSlots;

	/**
	 * Creates a template.
	 *
	 * @param id           the identity of the template within its pool
	 * @param body         the element-wise expression
	 * @param reduction    the reduction applied to the expression
	 * @param scaleSlot    the scalar slot the reduced result is divided by, or -1 for none
	 * @param rank         the rank of the shapes the template is instantiated over (1 or 2)
	 * @param tensorSlots  the number of tensor slots the expression reads
	 * @param literalSlots which scalar slots are embedded constants, one entry per scalar slot
	 */
	public ComputationTemplate(String id, TemplateNode body, Reduction reduction, int scaleSlot,
							   int rank, int tensorSlots, boolean[] literalSlots) {
		this.id = id;
		this.body = body;
		this.reduction = reduction;
		this.scaleSlot = scaleSlot;
		this.scaleLiteral = scaleSlot >= 0 && literalSlots[scaleSlot];
		this.rank = rank;
		this.tensorSlots = tensorSlots;
		this.scalarSlots = literalSlots.length;
		this.literalSlots = literalSlots.clone();
	}

	/**
	 * Returns the identity of this template within the pool it was generated in.
	 *
	 * @return the template id
	 */
	public String getId() { return id; }

	/**
	 * Returns the rank of the shapes the template is instantiated over.
	 *
	 * @return 1 or 2
	 */
	public int getRank() { return rank; }

	/**
	 * Returns the number of tensor slots the expression reads.
	 *
	 * @return the tensor slot count
	 */
	public int getTensorSlots() { return tensorSlots; }

	/**
	 * Returns the number of scalar slots the expression and the scale read.
	 *
	 * @return the scalar slot count
	 */
	public int getScalarSlots() { return scalarSlots; }

	/**
	 * Returns whether a scalar slot is embedded in the computation as a constant.
	 *
	 * @param slot the scalar slot index
	 * @return true for a constant, false for a one-element input
	 */
	public boolean isLiteral(int slot) { return literalSlots[slot]; }

	/**
	 * Returns the reduction applied to the expression.
	 *
	 * @return the reduction
	 */
	public Reduction getReduction() { return reduction; }

	/**
	 * Builds the computation for an instance of this template.
	 *
	 * @param features the features used to construct producers
	 * @param instance the instance whose inputs the computation reads
	 * @return the computation
	 */
	public CollectionProducer build(CodeFeatures features, WorkloadInstance instance) {
		CollectionProducer result = body.build(features, instance);

		if (reduction == Reduction.SUM) {
			result = result.sum();
		} else if (reduction == Reduction.ROW_SUM) {
			result = result.sum(1);
		}

		if (scaleSlot < 0) return result;
		if (scaleLiteral) return result.divide(instance.getScalarValue(scaleSlot));
		return result.divide(features.cp(instance.getScalar(scaleSlot)));
	}

	/**
	 * Evaluates the computation for an instance on the host, as the reference its output is
	 * checked against.
	 *
	 * @param instance the instance whose input values the computation reads
	 * @return the expected output values, in output order
	 */
	public double[] reference(WorkloadInstance instance) {
		return reference(instance, false);
	}

	/**
	 * Returns, for each output value, the magnitude of the terms it is computed from: the
	 * reference with the element-wise result and the scale replaced by their absolute values. A
	 * reduction whose terms cancel has a small result but accumulates rounding error in proportion
	 * to this magnitude, so it is what the tolerance of an output comparison is scaled by.
	 *
	 * @param instance the instance whose input values the computation reads
	 * @return the term magnitude of each output value, in output order
	 */
	public double[] referenceMagnitude(WorkloadInstance instance) {
		return reference(instance, true);
	}

	/** Evaluates the reference, or its term magnitude when {@code magnitude} is set. */
	private double[] reference(WorkloadInstance instance, boolean magnitude) {
		double[] values = body.evaluate(instance);
		if (magnitude) {
			for (int i = 0; i < values.length; i++) values[i] = Math.abs(values[i]);
		}

		if (reduction == Reduction.SUM) {
			double total = 0.0;
			for (double v : values) total += v;
			values = new double[] { total };
		} else if (reduction == Reduction.ROW_SUM) {
			int rows = instance.getShape().length(0);
			int columns = values.length / rows;
			double[] sums = new double[rows];
			for (int i = 0; i < values.length; i++) {
				sums[i / columns] += values[i];
			}

			values = sums;
		}

		if (scaleSlot >= 0) {
			double scale = magnitude ? Math.abs(instance.getScalarValue(scaleSlot)) : instance.getScalarValue(scaleSlot);
			for (int i = 0; i < values.length; i++) values[i] /= scale;
		}

		return values;
	}

	/**
	 * Returns the nesting depth of the element-wise expression.
	 *
	 * @return the depth of the deepest leaf
	 */
	public int depth() { return body.depth(); }

	/**
	 * Returns a compact, deterministic description of this template's structure.
	 *
	 * @return the description, such as {@code div(sum(mul(in0,add(in1,sin(in2)))),k0)}
	 */
	public String describe() {
		String expression = body.describe();
		if (reduction == Reduction.SUM) expression = "sum(" + expression + ")";
		if (reduction == Reduction.ROW_SUM) expression = "rowsum(" + expression + ")";
		if (scaleSlot >= 0) expression = "div(" + expression + "," + (scaleLiteral ? "k" : "s") + scaleSlot + ")";
		return expression;
	}

	/**
	 * Returns scalar values for an instance of this template that holds its constants fixed, so
	 * that two instances differ only in shape or memory layout.
	 *
	 * @return one value per scalar slot
	 */
	public double[] defaultScalarValues() {
		double[] values = new double[scalarSlots];
		for (int slot = 0; slot < values.length; slot++) values[slot] = DEFAULT_SCALARS[slot % DEFAULT_SCALARS.length];
		return values;
	}

	/**
	 * Returns the template {@code in0 + in1}.
	 *
	 * @return the template
	 */
	public static ComputationTemplate pairSum() {
		return new ComputationTemplate("pair-sum", new Binary(BinaryOperator.ADD, new Input(0), new Input(1)),
				Reduction.NONE, -1, 1, 2, new boolean[0]);
	}

	/**
	 * Returns the template {@code sum(x * (y + sin(z))) / k}, with {@code k} an embedded constant.
	 *
	 * @return the template
	 */
	public static ComputationTemplate canonical() {
		TemplateNode body = new Binary(BinaryOperator.MULTIPLY, new Input(0),
				new Binary(BinaryOperator.ADD, new Input(1), new Unary(UnaryOperator.SIN, new Input(2))));
		return new ComputationTemplate("canonical", body, Reduction.SUM, 0, 1, 3, new boolean[] { true });
	}

	/**
	 * Returns the template {@code x * y + z}.
	 *
	 * @return the template
	 */
	public static ComputationTemplate productPlusInput() {
		TemplateNode body = new Binary(BinaryOperator.ADD,
				new Binary(BinaryOperator.MULTIPLY, new Input(0), new Input(1)), new Input(2));
		return new ComputationTemplate("product-plus-input", body, Reduction.NONE, -1, 1, 3, new boolean[0]);
	}

	/**
	 * Returns the template summing the given number of distinct inputs.
	 *
	 * @param inputs the number of inputs, at least 1
	 * @return the template
	 */
	public static ComputationTemplate chainSum(int inputs) {
		TemplateNode body = new Input(0);
		for (int slot = 1; slot < inputs; slot++) {
			body = new Binary(BinaryOperator.ADD, body, new Input(slot));
		}

		return new ComputationTemplate("sum-of-" + inputs, body, Reduction.NONE, -1, 1, inputs, new boolean[0]);
	}

	/**
	 * Returns the template summing the given number of distinct inputs in one n-ary addition.
	 *
	 * @param inputs the number of inputs, at least 2
	 * @return the template
	 */
	public static ComputationTemplate flatSum(int inputs) {
		List<Input> operands = new ArrayList<>();
		for (int slot = 0; slot < inputs; slot++) operands.add(new Input(slot));
		return new ComputationTemplate("flat-sum-of-" + inputs, new TemplateNode.Sum(operands),
				Reduction.NONE, -1, 1, inputs, new boolean[0]);
	}

	/**
	 * Returns the template combining the given number of distinct inputs by alternately
	 * multiplying and adding them, {@code ((in0 * in1) + in2) * in3 ...}, a chain no two adjacent
	 * links of which are the same operation.
	 *
	 * @param inputs the number of inputs, at least 1
	 * @return the template
	 */
	public static ComputationTemplate alternatingChain(int inputs) {
		TemplateNode body = new Input(0);
		for (int slot = 1; slot < inputs; slot++) {
			BinaryOperator operator = slot % 2 == 1 ? BinaryOperator.MULTIPLY : BinaryOperator.ADD;
			body = new Binary(operator, body, new Input(slot));
		}

		return new ComputationTemplate("alternating-chain-of-" + inputs, body, Reduction.NONE, -1, 1, inputs,
				new boolean[0]);
	}
}
