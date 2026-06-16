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

package org.almostrealism.ml.audio;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * A learned embedding of a single continuous scalar, reusable wherever a model conditions on a
 * continuous control (duration in seconds, tempo, loudness, classifier-free-guidance scale, etc.).
 *
 * <h2>Computation</h2>
 * <p>For an input scalar {@code value} the embedding is</p>
 * <pre>
 * normalized = (clamp(value, minVal, maxVal) - minVal) / (maxVal - minVal)   // host-side, in [0, 1]
 * embedding  = projection( fourierFeatures(normalized) )                     // a producer graph
 * </pre>
 * <p>The Fourier (positional) features of the normalized scalar are produced by the shared
 * {@link DiffusionTransformerFeatures#fourierFeatures(int, int, io.almostrealism.relation.Producer,
 * PackedCollection)} builder, so the projection step is the only conditioner-specific math. The
 * normalization is the only host-side arithmetic and operates purely on the scalar method argument at
 * the pipeline boundary; all tensor computation is expressed as a {@link CollectionProducer} graph
 * that the framework compiles to native code.</p>
 *
 * <h2>Dual routing</h2>
 * <p>The single embedding is intended to feed two consumers: a cross-attention conditioning token and
 * a global-conditioning vector. {@link #embed(double)} produces the {@code [1, outDim]} embedding;
 * {@link #globalCond(double)} returns it in the global-conditioning vector shape {@code [1, outDim]}
 * and {@link #crossAttentionToken(double)} returns the same embedding shaped as a length-1
 * cross-attention sequence {@code [1, 1, outDim]}. Both forms derive from the same Fourier-plus-
 * projection computation, so a caller routes one learned embedding to both slots.</p>
 *
 * <h2>Relationship to the Stable Audio 3 duration conditioner</h2>
 * <p>Stable Audio 3 conditions on {@code seconds_total} ({@code min_val 0}, {@code max_val 384},
 * expo Fourier features) via a learned number embedder whose output is routed to both the cross-
 * attention input and the global (adaLN) conditioning. This class is the general framework primitive
 * behind that behaviour; the SA3 range is supplied through the constructor and nothing else is baked
 * in. The Stable Audio 3 number embedder applies a single learned linear projection after its
 * positional embedding, which is the projection form used here. (The Phase 1 plan sketch listed two
 * projection matrices modeling on the diffusion timestep MLP; a single learned projection is used
 * instead, matching both the task specification and the reference model. A model needing a deeper
 * projection head can compose the {@link #embed(double)} producer further.)</p>
 *
 * @author  Michael Murray
 * @see DiffusionTransformerFeatures
 * @see AudioAttentionConditioner
 */
public class NumberConditioner implements DiffusionTransformerFeatures {

	/** Single-scalar batch dimension; one embedding is produced per call. */
	private static final int BATCH = 1;

	/** Lower bound of the input range; values are clamped to {@code [minVal, maxVal]} before embedding. */
	private final double minVal;

	/** Upper bound of the input range; values are clamped to {@code [minVal, maxVal]} before embedding. */
	private final double maxVal;

	/** Output embedding dimensionality. */
	private final int outDim;

	/** Number of Fourier feature components, derived from {@link #fourierWeights}. */
	private final int fourierDim;

	/** Fourier projection weights of shape {@code [fourierDim/2, 1]}. */
	private final PackedCollection fourierWeights;

	/** Projection weight of shape {@code [outDim, fourierDim]} mapping the Fourier features to the embedding. */
	private final PackedCollection projWeight;

	/** Optional projection bias of shape {@code [outDim]}, or {@code null} for no bias. */
	private final PackedCollection projBias;

	/**
	 * Creates a number conditioner with no projection bias.
	 *
	 * @param minVal         lower bound of the input range
	 * @param maxVal         upper bound of the input range; must be greater than {@code minVal}
	 * @param outDim         output embedding dimensionality; must equal {@code projWeight} rows
	 * @param fourierWeights Fourier projection weights of shape {@code [fourierDim/2, 1]}
	 * @param projWeight     projection weight of shape {@code [outDim, fourierDim]}
	 */
	public NumberConditioner(double minVal, double maxVal, int outDim,
							 PackedCollection fourierWeights, PackedCollection projWeight) {
		this(minVal, maxVal, outDim, fourierWeights, projWeight, null);
	}

	/**
	 * Creates a number conditioner.
	 *
	 * @param minVal         lower bound of the input range
	 * @param maxVal         upper bound of the input range; must be greater than {@code minVal}
	 * @param outDim         output embedding dimensionality; must equal {@code projWeight} rows
	 * @param fourierWeights Fourier projection weights of shape {@code [fourierDim/2, 1]}
	 * @param projWeight     projection weight of shape {@code [outDim, fourierDim]}
	 * @param projBias       optional projection bias of shape {@code [outDim]}, or {@code null}
	 */
	public NumberConditioner(double minVal, double maxVal, int outDim,
							 PackedCollection fourierWeights, PackedCollection projWeight,
							 PackedCollection projBias) {
		if (maxVal <= minVal) {
			throw new IllegalArgumentException("NumberConditioner maxVal must be greater than minVal");
		}

		if (outDim <= 0) {
			throw new IllegalArgumentException("NumberConditioner outDim must be positive");
		}

		if (fourierWeights == null || fourierWeights.getShape().getDimensions() != 2
				|| fourierWeights.getShape().length(1) != 1) {
			throw new IllegalArgumentException("NumberConditioner fourierWeights must have shape [fourierDim/2, 1]");
		}

		int fourier = 2 * fourierWeights.getShape().length(0);

		if (projWeight == null || projWeight.getShape().getDimensions() != 2) {
			throw new IllegalArgumentException("NumberConditioner projWeight must be a matrix");
		}

		if (projWeight.getShape().length(0) != outDim || projWeight.getShape().length(1) != fourier) {
			throw new IllegalArgumentException("NumberConditioner projWeight must have shape [outDim, fourierDim]");
		}

		if (projBias != null && projBias.getShape().getTotalSize() != outDim) {
			throw new IllegalArgumentException("NumberConditioner projBias must have outDim values");
		}

		this.minVal = minVal;
		this.maxVal = maxVal;
		this.outDim = outDim;
		this.fourierDim = fourier;
		this.fourierWeights = fourierWeights;
		this.projWeight = projWeight;
		this.projBias = projBias == null ? null : projBias.flatten();
	}

	/**
	 * Normalizes the input scalar onto {@code [0, 1]} relative to the configured range. The value is
	 * first clamped to {@code [minVal, maxVal]}. This is the only host-side arithmetic and operates on
	 * the scalar method argument at the pipeline boundary.
	 *
	 * @param value the raw input scalar
	 * @return the normalized scalar in {@code [0, 1]}
	 */
	private double normalize(double value) {
		double clamped = Math.max(minVal, Math.min(maxVal, value));
		return (clamped - minVal) / (maxVal - minVal);
	}

	/**
	 * Produces the learned embedding of the given scalar as a {@code [1, outDim]} producer.
	 * <p>
	 * The scalar is normalized onto {@code [0, 1]}, expanded into Fourier features via the shared
	 * {@link DiffusionTransformerFeatures#fourierFeatures(int, int, io.almostrealism.relation.Producer,
	 * PackedCollection)} builder, and projected to the embedding dimension. The result is a producer
	 * (not an evaluated collection), so it composes directly into a larger computation graph.
	 * </p>
	 *
	 * @param value the raw input scalar; clamped to {@code [minVal, maxVal]}
	 * @return a producer of the {@code [1, outDim]} embedding
	 */
	public CollectionProducer embed(double value) {
		CollectionProducer input = c(shape(BATCH, 1), normalize(value));
		CollectionProducer fourier = fourierFeatures(BATCH, fourierDim, input, fourierWeights);

		CollectionProducer embedding = matmul(fourier, cp(projWeight).transpose(1));

		if (projBias != null) {
			embedding = embedding.add(cp(projBias).reshape(shape(BATCH, outDim)));
		}

		return embedding;
	}

	/**
	 * Returns the embedding of the given scalar as a global-conditioning vector of shape
	 * {@code [1, outDim]}. This is the same computation as {@link #embed(double)}; the method names the
	 * global-conditioning consumption form for clarity at call sites that also use
	 * {@link #crossAttentionToken(double)}.
	 *
	 * @param value the raw input scalar; clamped to {@code [minVal, maxVal]}
	 * @return a producer of the {@code [1, outDim]} global-conditioning vector
	 */
	public CollectionProducer globalCond(double value) {
		return embed(value);
	}

	/**
	 * Returns the embedding of the given scalar shaped as a length-1 cross-attention sequence of shape
	 * {@code [1, 1, outDim]} — a single conditioning token. The values are identical to
	 * {@link #embed(double)} and {@link #globalCond(double)}; only the shape differs, so one learned
	 * embedding is routed to both the cross-attention and global-conditioning consumers.
	 *
	 * @param value the raw input scalar; clamped to {@code [minVal, maxVal]}
	 * @return a producer of the {@code [1, 1, outDim]} cross-attention token
	 */
	public CollectionProducer crossAttentionToken(double value) {
		TraversalPolicy tokenShape = shape(BATCH, 1, outDim);
		return embed(value).reshape(tokenShape);
	}

	/**
	 * Returns the output embedding dimensionality.
	 *
	 * @return the embedding dimension ({@code outDim})
	 */
	public int getOutputDim() {
		return outDim;
	}

	/**
	 * Returns the lower bound of the input range.
	 *
	 * @return {@code minVal}
	 */
	public double getMinVal() {
		return minVal;
	}

	/**
	 * Returns the upper bound of the input range.
	 *
	 * @return {@code maxVal}
	 */
	public double getMaxVal() {
		return maxVal;
	}
}
