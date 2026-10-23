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

package org.almostrealism.studio.ml;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * A {@link Factor} that scales audio feature data by decision weights derived from
 * a conditioning value vector. Used in attention-conditioned audio generation to
 * modulate feature contributions based on an external control signal.
 */
public class ComposableAudioFeatures implements Factor<PackedCollection>, Destroyable, CodeFeatures {
	/** The audio feature data to be scaled, shaped {@code (T, B)}. */
	private Producer<PackedCollection> features;

	/** The weight tensor shaped {@code (T, B, L)} used to compute scaling factors. */
	private Producer<PackedCollection> weights;

	/** Scalar multiplier applied to the resultant; {@code 1.0} leaves it unscaled. */
	private double contribution;

	/**
	 * Create {@link ComposableAudioFeatures} with the provided features and weights.
	 * <p>
	 * For time (T), bin (B), and decision vector length (L). The weights are either a
	 * {@code (T, B, L)} tensor holding a decision vector per feature cell, or a single
	 * length-{@code L} vector that is shared by every cell (see {@link #setWeights(Producer)}).
	 * </p>
	 * <p>
	 * This object takes ownership of both producers: {@link #destroy()} destroys them,
	 * and destroying a computation also destroys its inputs.
	 * </p>
	 *
	 * @param features  (T, B)
	 * @param weights  (T, B, L), or (L)
	 */
	public ComposableAudioFeatures(Producer<PackedCollection> features,
								   Producer<PackedCollection> weights) {
		this.features = features;
		this.weights = broadcastWeights(weights);
		this.contribution = 1.0;
	}

	/**
	 * Replaces the weights used to compute the scaling factors. The weights are either a
	 * {@code (T, B, L)} tensor, used as is, or a decision vector of any other shape, which
	 * is flattened to length {@code L} and broadcast across every {@code (T, B)} feature
	 * cell. The previous weights are owned by this object and are destroyed, together with
	 * any producer they were computed from; ownership of the new weights passes to this
	 * object. The blend contribution is unaffected.
	 *
	 * @param weights  the new weights, {@code (T, B, L)} or {@code (L)}
	 */
	public void setWeights(Producer<PackedCollection> weights) {
		if (weights != this.weights) {
			Destroyable.destroy(this.weights);
		}

		this.weights = broadcastWeights(weights);
	}

	/**
	 * Broadcasts the given weights across the {@code (T, B)} cells of the feature data.
	 * A producer with one more dimension than the features is already a per-cell
	 * {@code (T, B, L)} tensor and is returned unchanged; any other producer is a single
	 * decision vector, which is flattened and repeated for every cell.
	 * <p>
	 * The decision vector is repeated once per feature axis, outer axis
	 * ({@code length(0)}) first and inner axis ({@code length(1)}) second, so the
	 * resulting weight tensor's leading axes are {@code (length(0), length(1))} in feature
	 * order. This matches the feature shape that {@link #getResultant(Producer)} multiplies
	 * the per-cell scale against; repeating in the opposite order would transpose those
	 * axes, which is invisible only while the vector is broadcast uniformly to every cell.
	 * </p>
	 *
	 * @param weights  the weights, {@code (T, B, L)} or a decision vector
	 * @return  the per-cell weight tensor, {@code (T, B, L)}
	 */
	protected Producer<PackedCollection> broadcastWeights(Producer<PackedCollection> weights) {
		TraversalPolicy featureShape = getFeatureShape();
		TraversalPolicy weightShape = shape(weights);
		if (weightShape.getDimensions() == featureShape.getDimensions() + 1) {
			return weights;
		}

		CollectionProducer vector = c(weights);
		if (weightShape.getDimensions() != 1)
			vector = vector.reshape(shape(weightShape.getTotalSize()));
		// Repeat the outer feature axis first, then the inner, so the leading weight axes
		// match the feature shape rather than its transpose
		return vector.repeat(featureShape.length(0)).repeat(featureShape.length(1));
	}

	/** Returns the scalar multiplier applied to the resultant. */
	public double getContribution() { return contribution; }

	/**
	 * Sets the scalar multiplier applied to the resultant, so that a sum of several
	 * resultants weights this one by {@code contribution} independently of the value
	 * vector. The default of {@code 1.0} leaves the resultant unscaled.
	 *
	 * @param contribution  the multiplier for the resultant
	 */
	public void setContribution(double contribution) {
		this.contribution = contribution;
	}

	/**
	 * Returns the traversal policy (shape) of the feature data.
	 *
	 * @return the feature shape
	 */
	public TraversalPolicy getFeatureShape() { return shape(features); }

	/**
	 * Compute the dot product of each weight vector with the provided value vector
	 * and use the resulting values, clamped to be non-negative and multiplied by the
	 * {@link #getContribution() contribution}, to scale the feature data.
	 *
	 * <p>
	 * The value vector is tiled explicitly to the {@code (T, B, L)} shape of the weights
	 * before the element-wise product, so that every weight vector is paired with the
	 * whole value vector. Relying on implicit broadcasting of a length-{@code L} vector
	 * against the weight tensor pairs it with a leading axis instead.
	 * </p>
	 *
	 * @param value  the vector of length {@code L} used to determine the scaling factors
	 * @return  scaled feature data
	 */
	@Override
	public Producer<PackedCollection> getResultant(Producer<PackedCollection> value) {
		TraversalPolicy weightShape = shape(weights);
		int length = weightShape.length(2);
		CollectionProducer tiled = c(value).reshape(shape(length))
				.repeat(weightShape.getTotalSize() / length)
				.reshape(weightShape);

		CollectionProducer scale = c(weights).multiply(tiled).traverse(2).sum();
		scale = scale.reshape(scale.getShape().trim());
		CollectionProducer result = c(features).multiply(max(scale, c(0)));
		return contribution == 1.0 ? result : result.multiply(c(contribution));
	}

	@Override
	public void destroy() {
		Destroyable.destroy(features);
		Destroyable.destroy(weights);
		features = null;
		weights = null;
	}
}
