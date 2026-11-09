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

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

/**
 * One node of the element-wise expression a {@link ComputationTemplate} describes.
 *
 * <p>A node is structure only. The data it reads comes from the {@link WorkloadInstance} it is
 * built for, through the slot index of each {@link Input} leaf, so the same template yields
 * computations over different shapes, roots and constant values. Every node can both build the
 * computation it describes and evaluate the same expression on the host as the reference the
 * benchmark checks each output against.</p>
 */
public abstract class TemplateNode {
	/**
	 * Builds the computation this node describes over the given instance's inputs.
	 *
	 * @param features the features used to construct producers
	 * @param instance the instance whose inputs the leaves read
	 * @return the computation
	 */
	public abstract CollectionProducer build(CodeFeatures features, WorkloadInstance instance);

	/**
	 * Evaluates this node on the host, element by element, as the reference for {@link #build}.
	 *
	 * @param instance the instance whose input values the leaves read
	 * @return one value per element of the instance's shape
	 */
	public abstract double[] evaluate(WorkloadInstance instance);

	/**
	 * Returns a compact, deterministic description of this node's structure.
	 *
	 * @return the description, such as {@code mul(in0,add(in1,sin(in2)))}
	 */
	public abstract String describe();

	/**
	 * Returns the depth of the deepest leaf below this node, 0 for a leaf.
	 *
	 * @return the nesting depth
	 */
	public abstract int depth();

	/** A tensor input: a leaf that reads the tensor bound to one slot of the instance. */
	public static final class Input extends TemplateNode {
		/** The tensor slot this leaf reads. */
		private final int slot;

		/**
		 * Creates a leaf reading the given tensor slot.
		 *
		 * @param slot the slot index
		 */
		public Input(int slot) { this.slot = slot; }

		/**
		 * Returns the tensor slot this leaf reads.
		 *
		 * @return the slot index
		 */
		public int getSlot() { return slot; }

		@Override
		public CollectionProducer build(CodeFeatures features, WorkloadInstance instance) {
			return features.cp(instance.getTensor(slot));
		}

		@Override
		public double[] evaluate(WorkloadInstance instance) {
			return instance.getTensorValues(slot).clone();
		}

		@Override
		public String describe() { return "in" + slot; }

		@Override
		public int depth() { return 0; }
	}

	/** The element-wise unary operations a template may apply. */
	public enum UnaryOperator {
		/** Sine. */
		SIN("sin", Math::sin),
		/** Cosine. */
		COS("cos", Math::cos),
		/** Absolute value. */
		ABS("abs", Math::abs),
		/** Negation. */
		MINUS("neg", v -> -v),
		/** Square. */
		SQ("sq", v -> v * v);

		/** The name used in descriptions. */
		private final String label;
		/** The host implementation used for the reference. */
		private final DoubleUnaryOperator reference;

		/**
		 * Creates an operation.
		 *
		 * @param label     the name used in descriptions
		 * @param reference the host implementation used for the reference
		 */
		UnaryOperator(String label, DoubleUnaryOperator reference) {
			this.label = label;
			this.reference = reference;
		}

		/**
		 * Applies this operation to a computation.
		 *
		 * @param features the features used to construct producers
		 * @param operand  the computation to apply it to
		 * @return the resulting computation
		 */
		public CollectionProducer apply(CodeFeatures features, CollectionProducer operand) {
			switch (this) {
				case SIN: return features.sin(operand);
				case COS: return features.cos(operand);
				case ABS: return operand.abs();
				case MINUS: return operand.minus();
				default: return operand.sq();
			}
		}
	}

	/** A unary operation applied to every element of its operand. */
	public static final class Unary extends TemplateNode {
		/** The operation. */
		private final UnaryOperator operator;
		/** The operand. */
		private final TemplateNode operand;

		/**
		 * Creates a unary node.
		 *
		 * @param operator the operation
		 * @param operand  the operand
		 */
		public Unary(UnaryOperator operator, TemplateNode operand) {
			this.operator = operator;
			this.operand = operand;
		}

		@Override
		public CollectionProducer build(CodeFeatures features, WorkloadInstance instance) {
			return operator.apply(features, operand.build(features, instance));
		}

		@Override
		public double[] evaluate(WorkloadInstance instance) {
			double[] values = operand.evaluate(instance);
			for (int i = 0; i < values.length; i++) {
				values[i] = operator.reference.applyAsDouble(values[i]);
			}

			return values;
		}

		@Override
		public String describe() { return operator.label + "(" + operand.describe() + ")"; }

		@Override
		public int depth() { return operand.depth() + 1; }
	}

	/**
	 * The element-wise binary operations a template may apply. Division always divides by
	 * {@code 1 + b^2} when the divisor is a tensor, so no generated input can divide by zero;
	 * a scalar divisor is drawn from values bounded away from zero.
	 */
	public enum BinaryOperator {
		/** Addition. */
		ADD("add", Double::sum),
		/** Subtraction. */
		SUBTRACT("sub", (a, b) -> a - b),
		/** Multiplication. */
		MULTIPLY("mul", (a, b) -> a * b),
		/** Division by a scalar, or by {@code 1 + b^2} for a tensor. */
		DIVIDE("div", (a, b) -> a / b);

		/** The name used in descriptions. */
		private final String label;
		/** The host implementation used for the reference, applied after the divisor transform. */
		private final DoubleBinaryOperator reference;

		/**
		 * Creates an operation.
		 *
		 * @param label     the name used in descriptions
		 * @param reference the host implementation used for the reference
		 */
		BinaryOperator(String label, DoubleBinaryOperator reference) {
			this.label = label;
			this.reference = reference;
		}

		/**
		 * Returns the name used in descriptions.
		 *
		 * @return the label
		 */
		public String getLabel() { return label; }

		/**
		 * Applies this operation with a computed right operand.
		 *
		 * @param left  the left operand
		 * @param right the right operand
		 * @return the resulting computation
		 */
		public CollectionProducer apply(CollectionProducer left, CollectionProducer right) {
			switch (this) {
				case ADD: return left.add(right);
				case SUBTRACT: return left.subtract(right);
				case MULTIPLY: return left.multiply(right);
				default: return left.divide(right);
			}
		}

		/**
		 * Applies this operation with a constant right operand, which is embedded in the
		 * computation rather than read from memory.
		 *
		 * @param left  the left operand
		 * @param right the constant
		 * @return the resulting computation
		 */
		public CollectionProducer apply(CollectionProducer left, double right) {
			switch (this) {
				case ADD: return left.add(right);
				case SUBTRACT: return left.subtract(right);
				case MULTIPLY: return left.multiply(right);
				default: return left.divide(right);
			}
		}

		/**
		 * Applies the host reference of this operation.
		 *
		 * @param left  the left value
		 * @param right the right value, after any divisor transform
		 * @return the result
		 */
		public double reference(double left, double right) {
			return reference.applyAsDouble(left, right);
		}
	}

	/** A binary operation whose right operand is another tensor expression. */
	public static final class Binary extends TemplateNode {
		/** The operation. */
		private final BinaryOperator operator;
		/** The left operand. */
		private final TemplateNode left;
		/** The right operand. */
		private final TemplateNode right;

		/**
		 * Creates a binary node over two tensor expressions.
		 *
		 * @param operator the operation
		 * @param left     the left operand
		 * @param right    the right operand
		 */
		public Binary(BinaryOperator operator, TemplateNode left, TemplateNode right) {
			this.operator = operator;
			this.left = left;
			this.right = right;
		}

		@Override
		public CollectionProducer build(CodeFeatures features, WorkloadInstance instance) {
			CollectionProducer r = right.build(features, instance);
			if (operator == BinaryOperator.DIVIDE) r = r.sq().add(1.0);
			return operator.apply(left.build(features, instance), r);
		}

		@Override
		public double[] evaluate(WorkloadInstance instance) {
			double[] a = left.evaluate(instance);
			double[] b = right.evaluate(instance);
			for (int i = 0; i < a.length; i++) {
				double divisor = operator == BinaryOperator.DIVIDE ? 1.0 + b[i] * b[i] : b[i];
				a[i] = operator.reference(a[i], divisor);
			}

			return a;
		}

		@Override
		public String describe() {
			return operator.label + "(" + left.describe() + "," + right.describe() + ")";
		}

		@Override
		public int depth() { return Math.max(left.depth(), right.depth()) + 1; }
	}

	/**
	 * A binary operation whose right operand is a scalar: either a constant embedded in the
	 * computation, or a one-element input read from memory. Which of the two it is belongs to the
	 * template; the value belongs to the instance.
	 */
	public static final class Scalar extends TemplateNode {
		/** The operation. */
		private final BinaryOperator operator;
		/** The tensor operand. */
		private final TemplateNode left;
		/** The scalar slot supplying the right operand. */
		private final int slot;
		/** Whether the scalar is embedded as a constant rather than read from an input. */
		private final boolean literal;

		/**
		 * Creates a binary node with a scalar right operand.
		 *
		 * @param operator the operation
		 * @param left     the tensor operand
		 * @param slot     the scalar slot supplying the right operand
		 * @param literal  whether the scalar is embedded as a constant
		 */
		public Scalar(BinaryOperator operator, TemplateNode left, int slot, boolean literal) {
			this.operator = operator;
			this.left = left;
			this.slot = slot;
			this.literal = literal;
		}

		@Override
		public CollectionProducer build(CodeFeatures features, WorkloadInstance instance) {
			CollectionProducer l = left.build(features, instance);
			if (literal) return operator.apply(l, instance.getScalarValue(slot));
			return operator.apply(l, features.cp(instance.getScalar(slot)));
		}

		@Override
		public double[] evaluate(WorkloadInstance instance) {
			double[] a = left.evaluate(instance);
			double b = instance.getScalarValue(slot);
			for (int i = 0; i < a.length; i++) {
				a[i] = operator.reference(a[i], b);
			}

			return a;
		}

		@Override
		public String describe() {
			return operator.label + "(" + left.describe() + "," + (literal ? "k" : "s") + slot + ")";
		}

		@Override
		public int depth() { return left.depth() + 1; }
	}

	/** The sum of several tensor inputs as one n-ary addition, rather than a chain of binary ones. */
	public static final class Sum extends TemplateNode {
		/** The inputs summed. */
		private final List<Input> operands;

		/**
		 * Creates an n-ary sum.
		 *
		 * @param operands the inputs summed, at least two
		 */
		public Sum(List<Input> operands) { this.operands = List.copyOf(operands); }

		@Override
		public CollectionProducer build(CodeFeatures features, WorkloadInstance instance) {
			List<Producer<?>> producers = new ArrayList<>();
			for (Input operand : operands) producers.add(operand.build(features, instance));
			return features.add(producers);
		}

		@Override
		public double[] evaluate(WorkloadInstance instance) {
			double[] total = operands.get(0).evaluate(instance);
			for (Input operand : operands.subList(1, operands.size())) {
				double[] values = operand.evaluate(instance);
				for (int i = 0; i < total.length; i++) total[i] += values[i];
			}

			return total;
		}

		@Override
		public String describe() {
			StringBuilder b = new StringBuilder("sum[");
			for (Input operand : operands) b.append(b.length() > 4 ? "," : "").append(operand.describe());
			return b.append(']').toString();
		}

		@Override
		public int depth() { return 1; }
	}
}
