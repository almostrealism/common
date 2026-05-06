/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.layers;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.relation.Factor;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Factory interface for normalization layers.
 *
 * <p>This collaborator interface groups the methods that build normalization
 * kernels — group normalization (with all its overloads) and RMS normalization
 * (with all its overloads). They are lifted out of {@link LayerFeatures} so that
 * the umbrella interface stays focused on fundamental layer-construction
 * primitives.</p>
 *
 * <p>{@link LayerFeatures} extends this interface, so any class that implements
 * {@code LayerFeatures} continues to see every normalization method without
 * modification. Default methods here call {@link #layer(String, TraversalPolicy,
 * TraversalPolicy, Factor, List, Supplier, ComputeRequirement...) layer(...)} and
 * {@link #layer(String, TraversalPolicy, TraversalPolicy, Factor, List,
 * ComputeRequirement...) layer(...)} (the abstract declarations below) — which
 * {@link LayerFeatures} satisfies via its existing default implementations.</p>
 *
 * @see LayerFeatures
 * @see ConvolutionLayerFeatures
 * @author Michael Murray
 */
public interface NormalizationLayerFeatures extends MatrixFeatures, ActivationFeatures, ConsoleFeatures {

	/**
	 * Creates a cellular layer with learnable weights and a no-op setup operation.
	 *
	 * <p>Declared abstractly so that the normalization default methods below can wire
	 * scale and shift parameters into the layer. The default implementation lives on
	 * {@link LayerFeatures}.</p>
	 *
	 * @param name         a human-readable label for the layer
	 * @param inputShape   the expected input shape
	 * @param outputShape  the shape produced by the operator
	 * @param operator     the differentiable forward operator
	 * @param weights      the learnable parameter collections
	 * @param requirements optional compute requirements
	 * @return the constructed {@link CellularLayer}
	 */
	CellularLayer layer(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
						Factor<PackedCollection> operator,
						List<PackedCollection> weights,
						ComputeRequirement... requirements);

	/**
	 * Creates a cellular layer with learnable weights and an explicit setup operation.
	 *
	 * <p>Declared abstractly so that group normalization can wire scale-initialisation
	 * operations into the layer. The default implementation lives on
	 * {@link LayerFeatures}.</p>
	 *
	 * @param name         a human-readable label for the layer
	 * @param inputShape   the expected input shape
	 * @param outputShape  the shape produced by the operator
	 * @param operator     the differentiable forward operator
	 * @param weights      the learnable parameter collections
	 * @param setup        the setup operation to run before the first forward pass
	 * @param requirements optional compute requirements
	 * @return the constructed {@link CellularLayer}
	 */
	CellularLayer layer(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
						Factor<PackedCollection> operator,
						List<PackedCollection> weights,
						Supplier<Runnable> setup,
						ComputeRequirement... requirements);

	/**
	 * Calculate the effective width for a normalization layer. This will always be
	 * the total size of weights or biases (if they are present), otherwise it will
	 * simply be the total size of the {@link TraversalPolicy} divided by the desired
	 * number of groups. This size is critical to the distinction between so called
	 * "batch" normalization versus "layer" normalization.
	 *
	 * @param shape
	 * @param groups
	 * @param weights
	 * @param biases
	 * @return
	 */
	default int normSize(TraversalPolicy shape, int groups, PackedCollection weights, PackedCollection biases) {
		if (weights != null) {
			return weights.getShape().getTotalSize();
		} else if (biases != null) {
			return biases.getShape().getTotalSize();
		} else {
			return shape.getTotalSize();
		}
	}

	/**
	 * Creates a group-normalization layer factory with a single group and trainable parameters.
	 *
	 * @param requirements optional compute requirements
	 * @return a function that creates a norm {@link CellularLayer} for any input shape
	 */
	default Function<TraversalPolicy, CellularLayer> norm(ComputeRequirement... requirements) {
		return norm(1, requirements);
	}

	/**
	 * Creates a group-normalization layer factory with the given number of groups and trainable parameters.
	 *
	 * @param groups       the number of normalization groups
	 * @param requirements optional compute requirements
	 * @return a function that creates a norm {@link CellularLayer} for any input shape
	 */
	default Function<TraversalPolicy, CellularLayer> norm(int groups, ComputeRequirement... requirements) {
		return shape -> norm(shape, groups, requirements);
	}

	/**
	 * Creates a group-normalization layer factory with pre-allocated weights and biases
	 * and a single group, using the hardware default epsilon.
	 *
	 * @param weights      the normalization scale parameters
	 * @param biases       the normalization shift parameters
	 * @param requirements optional compute requirements
	 * @return a function that creates a norm {@link CellularLayer} for any input shape
	 */
	default Function<TraversalPolicy, CellularLayer> norm(PackedCollection weights,
														  PackedCollection biases,
														  ComputeRequirement... requirements) {
		return shape -> norm(shape, 1, weights, biases, false, requirements);
	}

	/**
	 * Creates a group-normalization layer factory with pre-allocated weights, biases, and
	 * an explicit epsilon, using a single group.
	 *
	 * @param weights      the normalization scale parameters
	 * @param biases       the normalization shift parameters
	 * @param eps          small constant for numerical stability
	 * @param requirements optional compute requirements
	 * @return a function that creates a norm {@link CellularLayer} for any input shape
	 */
	default Function<TraversalPolicy, CellularLayer> norm(PackedCollection weights,
														  PackedCollection biases,
														  double eps,
														  ComputeRequirement... requirements) {
		return shape -> norm(shape, 1, weights, biases, eps, false, requirements);
	}


	/**
	 * Creates a trainable group-normalization layer for the given shape.
	 *
	 * @param shape        the input and output shape
	 * @param groups       the number of normalization groups
	 * @param requirements optional compute requirements
	 * @return the constructed norm {@link CellularLayer}
	 */
	default CellularLayer norm(TraversalPolicy shape, int groups, ComputeRequirement... requirements) {
		return norm(shape, groups, true, requirements);
	}

	/**
	 * Creates a group-normalization layer with optional parameter training for the given shape.
	 *
	 * @param shape        the input and output shape
	 * @param groups       the number of normalization groups
	 * @param trainable    when {@code true}, scale and shift parameters are learnable
	 * @param requirements optional compute requirements
	 * @return the constructed norm {@link CellularLayer}
	 */
	default CellularLayer norm(TraversalPolicy shape, int groups,
							   boolean trainable, ComputeRequirement... requirements) {
		return norm(shape, normSize(shape, groups, null, null), groups, trainable, requirements);
	}

	/**
	 * Creates a group-normalization layer with an explicit parameter size and optional training.
	 *
	 * @param shape        the input and output shape
	 * @param size         the total number of normalization parameters
	 * @param groups       the number of normalization groups
	 * @param trainable    when {@code true}, scale and shift parameters are learnable
	 * @param requirements optional compute requirements
	 * @return the constructed norm {@link CellularLayer}
	 */
	default CellularLayer norm(TraversalPolicy shape, int size, int groups,
							   boolean trainable, ComputeRequirement... requirements) {
		return norm(shape, size, groups,
				trainable ? new PackedCollection(size) : null,
				trainable ? new PackedCollection(size) : null,
				true, requirements);
	}

	/**
	 * Creates a group-normalization layer deriving the shape from the given weights or biases,
	 * without initializing the parameters.
	 *
	 * @param groups       the number of normalization groups
	 * @param weights      the normalization scale parameters (used to infer shape when non-null)
	 * @param biases       the normalization shift parameters (used to infer shape when weights is null)
	 * @param requirements optional compute requirements
	 * @return the constructed norm {@link CellularLayer}
	 */
	default CellularLayer norm(int groups,
							   PackedCollection weights,
							   PackedCollection biases,
							   ComputeRequirement... requirements) {
		return norm(groups, weights, biases, false, requirements);
	}

	/**
	 * Creates a group-normalization layer deriving the shape from the given weights or biases,
	 * with optional parameter initialization.
	 *
	 * @param groups       the number of normalization groups
	 * @param weights      the normalization scale parameters (used to infer shape when non-null)
	 * @param biases       the normalization shift parameters (used to infer shape when weights is null)
	 * @param init         when {@code true}, scale parameters are initialized to 1 and bias to 0
	 * @param requirements optional compute requirements
	 * @return the constructed norm {@link CellularLayer}
	 */
	default CellularLayer norm(int groups, PackedCollection weights, PackedCollection biases,
							   boolean init, ComputeRequirement... requirements) {
		TraversalPolicy shape;

		if (weights != null) {
			shape = shape(weights);
		} else if (biases != null) {
			shape = shape(biases);
		} else {
			throw new IllegalArgumentException();
		}

		return norm(shape, groups, weights, biases, init, requirements);
	}

	/**
	 * Creates a group-normalization layer for the given shape with pre-allocated weights and biases,
	 * using a single group and initializing the parameters.
	 *
	 * @param shape        the input and output shape
	 * @param weights      the normalization scale parameters
	 * @param biases       the normalization shift parameters
	 * @param requirements optional compute requirements
	 * @return the constructed norm {@link CellularLayer}
	 */
	default CellularLayer norm(TraversalPolicy shape,
							   PackedCollection weights,
							   PackedCollection biases,
							   ComputeRequirement... requirements) {
		return norm(shape, 1, weights, biases, requirements);
	}

	/**
	 * Creates a group-normalization layer for the given shape with pre-allocated weights and biases,
	 * initializing the parameters.
	 *
	 * @param shape        the input and output shape
	 * @param groups       the number of normalization groups
	 * @param weights      the normalization scale parameters
	 * @param biases       the normalization shift parameters
	 * @param requirements optional compute requirements
	 * @return the constructed norm {@link CellularLayer}
	 */
	default CellularLayer norm(TraversalPolicy shape, int groups,
							   PackedCollection weights,
							   PackedCollection biases,
							   ComputeRequirement... requirements) {
		return norm(shape, groups, weights, biases, true, requirements);
	}

	/**
	 * Creates a group-normalization layer with optional parameter initialization,
	 * deriving the normalization size from the shape and weights.
	 *
	 * @param shape        the input and output shape
	 * @param groups       the number of normalization groups
	 * @param weights      the normalization scale parameters
	 * @param biases       the normalization shift parameters
	 * @param init         when {@code true}, scale parameters are initialized to 1 and bias to 0
	 * @param requirements optional compute requirements
	 * @return the constructed norm {@link CellularLayer}
	 */
	default CellularLayer norm(TraversalPolicy shape, int groups,
							   PackedCollection weights,
							   PackedCollection biases,
							   boolean init,
							   ComputeRequirement... requirements) {
		return norm(shape, normSize(shape, groups, weights, biases),
				groups, weights, biases, init, requirements);
	}

	/**
	 * Creates a group-normalization layer with an explicit normalization size, using
	 * the hardware default epsilon.
	 *
	 * @param shape        the input and output shape
	 * @param size         the total number of normalization parameters
	 * @param groups       the number of normalization groups
	 * @param weights      the normalization scale parameters
	 * @param biases       the normalization shift parameters
	 * @param init         when {@code true}, scale parameters are initialized to 1 and bias to 0
	 * @param requirements optional compute requirements
	 * @return the constructed norm {@link CellularLayer}
	 */
	default CellularLayer norm(TraversalPolicy shape, int size, int groups,
							   PackedCollection weights,
							   PackedCollection biases,
							   boolean init,
							   ComputeRequirement... requirements) {
		return norm(shape, size, groups, weights, biases,
				Hardware.getLocalHardware().epsilon(), init, requirements);
	}

	/**
	 * Creates a group-normalization layer with an explicit epsilon value, deriving the
	 * normalization size from the shape and weights.
	 *
	 * @param shape        the input and output shape
	 * @param groups       the number of normalization groups
	 * @param weights      the normalization scale parameters
	 * @param biases       the normalization shift parameters
	 * @param eps          small constant added to variance for numerical stability
	 * @param init         when {@code true}, scale parameters are initialized to 1 and bias to 0
	 * @param requirements optional compute requirements
	 * @return the constructed norm {@link CellularLayer}
	 */
	default CellularLayer norm(TraversalPolicy shape, int groups,
							   PackedCollection weights,
							   PackedCollection biases,
							   double eps, boolean init,
							   ComputeRequirement... requirements) {
		return norm(shape, normSize(shape, groups, weights, biases),
				groups, weights, biases, eps, init, requirements);
	}

	/**
	 * Creates a fully specified group-normalization layer with explicit size and epsilon.
	 *
	 * <p>Normalizes the input over groups, applies learnable scale ({@code weights}) and
	 * shift ({@code biases}) parameters, and outputs the same shape as the input.</p>
	 *
	 * @param shape        the input and output shape
	 * @param size         the total number of normalization parameters
	 * @param groups       the number of normalization groups
	 * @param weights      the normalization scale parameters (may be null for no scaling)
	 * @param biases       the normalization shift parameters (may be null for no bias)
	 * @param eps          small constant added to variance for numerical stability
	 * @param init         when {@code true}, scale parameters are initialized to 1 and bias to 0
	 * @param requirements optional compute requirements
	 * @return the constructed norm {@link CellularLayer}
	 */
	default CellularLayer norm(TraversalPolicy shape,
							   int size, int groups,
							   PackedCollection weights,
							   PackedCollection biases,
							   double eps, boolean init,
							   ComputeRequirement... requirements) {
		if ((weights != null && shape(weights).getTotalSize() != size) ||
				(biases != null && shape(biases).getTotalSize() != size)) {
			throw new IllegalArgumentException();
		}

		if (size % groups != 0) {
			if (shape.getTotalSizeLong() % groups == 0) {
				warn("Group normalization may span across batches");
			} else {
				throw new IllegalArgumentException();
			}
		}

		List<PackedCollection> prop = new ArrayList<>();
		if (weights != null) prop.add(weights);
		if (biases != null) prop.add(biases);

		PackedCollection w = weights == null ? null : weights.flatten();
		PackedCollection b = biases == null ? null : biases.flatten();

		OperationList setup = new OperationList();
		if (init) {
			if (w != null) setup.add(a(p(w.each()), c(1)));
			if (b != null) setup.add(a(p(b.each()), c(0.0)));
		}

		TraversalPolicy outputShape = shape.traverse(1);
		return layer("norm", outputShape, outputShape, input -> {
			CollectionProducer in = c(input).reshape(-1, groups, Math.toIntExact(size / groups));
			CollectionProducer out = in.subtractMean(2).divide(in.variance(2).add(c(eps)).sqrt());
			out = out.reshape(-1, Math.toIntExact(size)).traverse(1);

			if (w != null) out = out.multiply(cp(w));
			if (b != null) out = out.add(cp(b));
			return out.reshape(outputShape.traverseEach());
		}, prop, setup, requirements);
	}

	/**
	 * Creates an RMS-normalization layer factory with trainable scale parameters and bias
	 * for the given feature size.
	 *
	 * @param size         the number of features to normalize
	 * @param requirements optional compute requirements
	 * @return a function that creates the RMSNorm {@link CellularLayer} for any input shape
	 */
	default Function<TraversalPolicy, CellularLayer> rmsnorm(int size, ComputeRequirement... requirements) {
		return rmsnorm(size, true, requirements);
	}

	/**
	 * Creates an RMS-normalization layer factory with trainable scale parameters and optional bias
	 * for the given feature size.
	 *
	 * @param size         the number of features to normalize
	 * @param bias         when {@code true}, a learnable bias is included
	 * @param requirements optional compute requirements
	 * @return a function that creates the RMSNorm {@link CellularLayer} for any input shape
	 */
	default Function<TraversalPolicy, CellularLayer> rmsnorm(int size, boolean bias, ComputeRequirement... requirements) {
		return shape -> rmsnorm(shape,
				new PackedCollection(shape(size)).fill(1.0),
				bias ? new PackedCollection(shape(size)) : null,
				requirements);
	}


	/**
	 * Creates an RMS-normalization layer using the shape of the provided weights and no bias.
	 *
	 * @param weights      the normalization scale parameters; their shape is used as the layer shape
	 * @param requirements optional compute requirements
	 * @return the constructed RMSNorm {@link CellularLayer}
	 */
	default CellularLayer rmsnorm(PackedCollection weights,
								  ComputeRequirement... requirements) {
		return rmsnorm(weights.getShape(), weights, null, requirements);
	}

	/**
	 * Creates an RMS-normalization layer using the shape of the provided weights, no bias, and an
	 * explicit epsilon.
	 *
	 * @param weights      the normalization scale parameters; their shape is used as the layer shape
	 * @param epsilon      small constant for numerical stability
	 * @param requirements optional compute requirements
	 * @return the constructed RMSNorm {@link CellularLayer}
	 */
	default CellularLayer rmsnorm(PackedCollection weights,
								  double epsilon,
								  ComputeRequirement... requirements) {
		return rmsnorm(weights.getShape(), weights, null, epsilon, requirements);
	}

	/**
	 * Creates an RMS-normalization layer using the shape of the provided weights, with bias.
	 *
	 * @param weights      the normalization scale parameters; their shape is used as the layer shape
	 * @param biases       the normalization shift parameters
	 * @param requirements optional compute requirements
	 * @return the constructed RMSNorm {@link CellularLayer}
	 */
	default CellularLayer rmsnorm(PackedCollection weights,
								  PackedCollection biases,
								  ComputeRequirement... requirements) {
		return rmsnorm(weights.getShape(), weights, biases, requirements);
	}

	/**
	 * Creates an RMS-normalization layer using the shape of the provided weights, with bias
	 * and an explicit epsilon.
	 *
	 * @param weights      the normalization scale parameters; their shape is used as the layer shape
	 * @param biases       the normalization shift parameters
	 * @param epsilon      small constant for numerical stability
	 * @param requirements optional compute requirements
	 * @return the constructed RMSNorm {@link CellularLayer}
	 */
	default CellularLayer rmsnorm(PackedCollection weights,
								  PackedCollection biases,
								  double epsilon,
								  ComputeRequirement... requirements) {
		return rmsnorm(weights.getShape(), weights, biases, epsilon, requirements);
	}

	/**
	 * Creates an RMS-normalization layer for the given shape with weights and no bias,
	 * using the default epsilon of {@code 1e-5}.
	 *
	 * @param shape        the input and output shape
	 * @param weights      the normalization scale parameters
	 * @param requirements optional compute requirements
	 * @return the constructed RMSNorm {@link CellularLayer}
	 */
	default CellularLayer rmsnorm(TraversalPolicy shape,
								  PackedCollection weights,
								  ComputeRequirement... requirements) {
		return rmsnorm(shape, weights, null, requirements);
	}

	/**
	 * Creates an RMS-normalization layer for the given shape with weights, no bias, and
	 * an explicit epsilon.
	 *
	 * @param shape        the input and output shape
	 * @param weights      the normalization scale parameters
	 * @param epsilon      small constant for numerical stability
	 * @param requirements optional compute requirements
	 * @return the constructed RMSNorm {@link CellularLayer}
	 */
	default CellularLayer rmsnorm(TraversalPolicy shape,
								  PackedCollection weights,
								  double epsilon,
								  ComputeRequirement... requirements) {
		return rmsnorm(shape, weights, null, epsilon, requirements);
	}

	/**
	 * Creates an RMS-normalization layer for the given shape with weights and bias,
	 * using the default epsilon of {@code 1e-5}.
	 *
	 * @param shape        the input and output shape
	 * @param weights      the normalization scale parameters
	 * @param biases       the normalization shift parameters (may be null)
	 * @param requirements optional compute requirements
	 * @return the constructed RMSNorm {@link CellularLayer}
	 */
	default CellularLayer rmsnorm(TraversalPolicy shape,
								  PackedCollection weights,
								  PackedCollection biases,
								  ComputeRequirement... requirements) {
		return rmsnorm(shape, weights, biases, 1e-5, requirements);
	}

	/**
	 * RMS (Root Mean Square) normalization layer with configurable epsilon.
	 *
	 * @param shape Input/output shape
	 * @param weights Normalization weights
	 * @param biases Optional biases (can be null)
	 * @param epsilon Small constant for numerical stability (e.g., 1e-5 or 1e-6)
	 * @param requirements Compute requirements
	 * @return RMSNorm layer
	 */
	default CellularLayer rmsnorm(TraversalPolicy shape,
								  PackedCollection weights,
								  PackedCollection biases,
								  double epsilon,
								  ComputeRequirement... requirements) {
		if (weights.getShape().getDimensions() != 1 ||
				(biases != null && biases.getShape().getDimensions() != 1)) {
			throw new IllegalArgumentException();
		}

		int size = weights.getShape().getTotalSize();
		int axis = shape.getDimensions() - 1;

		return layer("rmsnorm", shape, shape, input -> {
			CollectionProducer ss = pow(traverseEach(input), c(2.0)).traverse(axis).sum();
			ss = ss.divide(c(size)).add(c(epsilon));
			ss = c(1.0).divide(ss.pow(c(0.5)));

			if (weights == null) {
				ss = ss.multiply(traverseEach(input));
			} else {
				ss = multiply(traverseEach(cp(weights)), traverseEach(input)).multiply(ss);
			}

			if (biases != null) {
				ss = ss.add(traverseEach(cp(biases)));
			}

			return ss.reshape(shape);
		}, biases != null ? List.of(weights, biases) : List.of(weights), requirements);
	}
}
