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
import io.almostrealism.uml.Nameable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Low-Rank Adaptation (LoRA) layer for parameter-efficient fine-tuning.
 *
 * <p>LoRA adds trainable low-rank matrices A and B to a frozen base layer,
 * allowing efficient fine-tuning without modifying the original weights.
 * The output is computed as:</p>
 *
 * <pre>
 * output = base(x) + (alpha / rank) * x @ A @ B
 * </pre>
 *
 * <p>Where:</p>
 * <ul>
 *   <li>base(x) is the output from the frozen base layer</li>
 *   <li>A has shape [inputSize, rank] - initialized from Gaussian</li>
 *   <li>B has shape [rank, outputSize] - initialized to zeros</li>
 *   <li>alpha is a scaling factor (typically 2 * rank)</li>
 *   <li>rank is the low-rank dimension (e.g., 4, 8, 16)</li>
 * </ul>
 *
 * <p>The key benefits of LoRA:</p>
 * <ul>
 *   <li>Only ~0.1-1% of base parameters need to be trained</li>
 *   <li>Base model weights remain frozen, preserving original capabilities</li>
 *   <li>Multiple LoRA adaptations can be stored and swapped efficiently</li>
 *   <li>LoRA weights can be merged into base for deployment (no runtime overhead)</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Load pre-trained dense layer
 * PackedCollection baseWeights = stateDict.get("layer.weight");
 * PackedCollection baseBias = stateDict.get("layer.bias");
 *
 * // Wrap with LoRA (rank 8, alpha 16)
 * LoRALinear loraLayer = new LoRALinear(
 *     inputShape, baseWeights, baseBias,
 *     8, 16.0
 * );
 *
 * // Only LoRA weights are trainable
 * List<PackedCollection> trainable = loraLayer.getWeights();  // [loraA, loraB]
 * }</pre>
 *
 * @see LayerFeatures#dense(PackedCollection, PackedCollection, ComputeRequirement...)
 * @author Michael Murray
 */
public class LoRALinear implements CellularLayer, Learning, Nameable, LayerFeatures {

	private static final Random random = new Random(42);

	private final String name;
	private final TraversalPolicy inputShape;
	private final TraversalPolicy outputShape;
	private final int rank;
	private final double alpha;
	private final double scale;

	private final PackedCollection baseWeights;
	private final PackedCollection baseBias;
	private final PackedCollection loraA;
	private final PackedCollection loraB;

	// Delegate to a properly constructed CellularLayer
	private final CellularLayer delegate;

	private ParameterUpdate<PackedCollection> parameterUpdate;
	private List<ComputeRequirement> requirements;

	/**
	 * Creates a LoRA-wrapped linear layer.
	 *
	 * @param inputShape Shape of input data (last dimension is input size)
	 * @param baseWeights Pre-trained weight matrix [outputSize, inputSize]
	 * @param baseBias Pre-trained bias vector [outputSize], may be null
	 * @param rank Low-rank dimension for LoRA matrices (e.g., 4, 8, 16)
	 * @param alpha Scaling factor for LoRA output (typically 2 * rank)
	 */
	public LoRALinear(TraversalPolicy inputShape,
					  PackedCollection baseWeights,
					  PackedCollection baseBias,
					  int rank, double alpha) {
		this(inputShape, baseWeights, baseBias, rank, alpha, null);
	}

	/**
	 * Creates a LoRA-wrapped linear layer with optional pre-initialized LoRA weights.
	 *
	 * @param inputShape Shape of input data (last dimension is input size)
	 * @param baseWeights Pre-trained weight matrix [outputSize, inputSize]
	 * @param baseBias Pre-trained bias vector [outputSize], may be null
	 * @param rank Low-rank dimension for LoRA matrices
	 * @param alpha Scaling factor for LoRA output
	 * @param existingLoraWeights Array of [loraA, loraB] if loading from checkpoint, null to initialize fresh
	 */
	public LoRALinear(TraversalPolicy inputShape,
					  PackedCollection baseWeights,
					  PackedCollection baseBias,
					  int rank, double alpha,
					  PackedCollection[] existingLoraWeights) {
		if (baseWeights.getShape().getDimensions() != 2) {
			throw new IllegalArgumentException("Base weights must be 2D [outputSize, inputSize]");
		}

		int outputSize = baseWeights.getShape().length(0);
		int inputSize = baseWeights.getShape().length(1);

		this.name = "LoRALinear[" + inputSize + "->" + outputSize + ", r=" + rank + "]";
		this.inputShape = inputShape;
		this.outputShape = inputShape.replaceDimension(inputShape.getDimensions() - 1, outputSize);
		this.rank = rank;
		this.alpha = alpha;
		this.scale = alpha / rank;

		this.baseWeights = baseWeights;
		this.baseBias = baseBias;

		if (existingLoraWeights != null) {
			this.loraA = existingLoraWeights[0];
			this.loraB = existingLoraWeights[1];
		} else {
			this.loraA = initializeLoraA(inputSize, rank);
			this.loraB = initializeLoraB(rank, outputSize);
		}

		// Create forward operator using same pattern as dense layer
		TraversalPolicy flat = padDimensions(inputShape, 2)
				.flatten(true, inputSize)
				.traverse(1);

		Factor<PackedCollection> forwardOp = in -> {
			// Reshape input to flat shape (same pattern as dense layer)
			in = reshape(flat, in);

			// Base dense computation: y = Wx + b
			CollectionProducer baseResult = baseBias != null
					? matmul(p(baseWeights), in).add(traverse(1, p(baseBias)))
					: matmul(p(baseWeights), in);

			// LoRA computation: (alpha/rank) * x @ A @ B
			// x is [batch, inputSize], A is [inputSize, rank], B is [rank, outputSize]
			// x @ A = matmul(A^T, x^T)^T = [batch, rank]
			// (x @ A) @ B = matmul(B^T, (x @ A)^T)^T = [batch, outputSize]
			CollectionProducer xA = matmul(cp(loraA).transpose(), in);  // [rank, batch]
			CollectionProducer loraResult = matmul(cp(loraB).transpose(), xA);  // [outputSize, batch]

			// Combine base + scaled LoRA
			CollectionProducer result = baseResult.add(loraResult.multiply(c(scale)));

			return result.reshape(outputShape);
		};

		// Create the delegate using the layer() helper for proper initialization
		this.delegate = layer(name, inputShape.traverseEach(), outputShape.traverseEach(),
				forwardOp, Arrays.asList(loraA, loraB));
	}

	/**
	 * Initialize LoRA A matrix with small Gaussian values.
	 * Following the original LoRA paper, A is initialized from N(0, sigma).
	 */
	private PackedCollection initializeLoraA(int inputSize, int rank) {
		PackedCollection a = new PackedCollection(shape(inputSize, rank));
		double std = 1.0 / Math.sqrt(inputSize);
		for (int i = 0; i < inputSize * rank; i++) {
			a.setMem(i, random.nextGaussian() * std);
		}
		return a;
	}

	/**
	 * Initialize LoRA B matrix to zeros.
	 * This ensures LoRA contribution is zero at the start of training,
	 * preserving the original model behavior.
	 */
	private PackedCollection initializeLoraB(int rank, int outputSize) {
		PackedCollection b = new PackedCollection(shape(rank, outputSize));
		b.fill(pos -> 0.0);
		return b;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void setName(String name) {
		// Name is computed from dimensions, not settable
	}

	@Override
	public TraversalPolicy getInputShape() {
		return inputShape.traverseEach();
	}

	@Override
	public TraversalPolicy getOutputShape() {
		return outputShape.traverseEach();
	}

	/**
	 * Returns only the LoRA weights, not the base weights.
	 * This ensures only LoRA parameters are updated during training.
	 *
	 * @return List containing [loraA, loraB]
	 */
	@Override
	public List<PackedCollection> getWeights() {
		return Arrays.asList(loraA, loraB);
	}

	/**
	 * Returns the base (frozen) weights for inspection or merging.
	 *
	 * @return The frozen base weight matrix
	 */
	public PackedCollection getBaseWeights() {
		return baseWeights;
	}

	/**
	 * Returns the base (frozen) bias for inspection or merging.
	 *
	 * @return The frozen base bias, or null if no bias
	 */
	public PackedCollection getBaseBias() {
		return baseBias;
	}

	/**
	 * Returns the LoRA A matrix.
	 *
	 * @return LoRA A matrix [inputSize, rank]
	 */
	public PackedCollection getLoraA() {
		return loraA;
	}

	/**
	 * Returns the LoRA B matrix.
	 *
	 * @return LoRA B matrix [rank, outputSize]
	 */
	public PackedCollection getLoraB() {
		return loraB;
	}

	/**
	 * Returns the rank of this LoRA layer.
	 *
	 * @return The low-rank dimension
	 */
	public int getRank() {
		return rank;
	}

	/**
	 * Returns the alpha scaling factor.
	 *
	 * @return The alpha value
	 */
	public double getAlpha() {
		return alpha;
	}

	@Override
	public Supplier<Runnable> setup() {
		return delegate.setup();
	}

	@Override
	public Cell<PackedCollection> getForward() {
		return delegate.getForward();
	}

	@Override
	public Cell<PackedCollection> getBackward() {
		return delegate.getBackward();
	}

	@Override
	public void setParameterUpdate(ParameterUpdate<PackedCollection> update) {
		this.parameterUpdate = update;
		if (delegate instanceof Learning) {
			((Learning) delegate).setParameterUpdate(update);
		}
	}

	public void setComputeRequirements(List<ComputeRequirement> requirements) {
		this.requirements = requirements;
		if (delegate instanceof DefaultCellularLayer) {
			((DefaultCellularLayer) delegate).setComputeRequirements(requirements);
		}
	}

	/**
	 * Merges LoRA weights into the base weights, producing a single weight matrix
	 * that can be used without LoRA runtime overhead.
	 *
	 * <p>The merged weight is: W_merged = W_base + (alpha/rank) * A @ B</p>
	 *
	 * @return A new PackedCollection containing the merged weights [outputSize, inputSize]
	 */
	public PackedCollection mergeWeights() {
		int outputSize = baseWeights.getShape().length(0);
		int inputSize = baseWeights.getShape().length(1);

		PackedCollection merged = new PackedCollection(shape(outputSize, inputSize));

		for (int i = 0; i < outputSize; i++) {
			for (int j = 0; j < inputSize; j++) {
				double baseVal = baseWeights.toDouble(i * inputSize + j);

				double loraVal = 0.0;
				for (int r = 0; r < rank; r++) {
					double aVal = loraA.toDouble(j * rank + r);
					double bVal = loraB.toDouble(r * outputSize + i);
					loraVal += aVal * bVal;
				}

				merged.setMem(i * inputSize + j, baseVal + scale * loraVal);
			}
		}

		return merged;
	}

	/**
	 * Creates a standard dense layer with the merged weights.
	 * This can be used for efficient deployment after fine-tuning.
	 *
	 * @return A CellularLayer with merged weights
	 */
	public CellularLayer toMergedLayer() {
		PackedCollection mergedWeights = mergeWeights();
		return dense(inputShape, mergedWeights, baseBias, false);
	}

	@Override
	public String describe() {
		return name + " " + inputShape + "->" + outputShape;
	}
}
