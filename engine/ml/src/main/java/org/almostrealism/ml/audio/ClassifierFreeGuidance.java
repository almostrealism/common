/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.ml.audio;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * Classifier-free guidance for a rectified-flow denoiser, with optional adaptive projected
 * guidance (APG).
 *
 * <p>Guidance combines two predictions of the same noisy latent {@code x} at noise level
 * {@code sigma}: one made under the intended conditioning and one made under a negative
 * (or empty) conditioning. For a rectified-flow model whose output {@code v} satisfies
 * {@code denoised = x - sigma * v}, the guided denoised estimate is</p>
 *
 * <pre>
 *   d_c  = x - sigma * v_c
 *   d_u  = x - sigma * v_u
 *   diff = d_c - d_u
 *   g    = apg * orthogonal(diff, d_c) + (1 - apg) * diff
 *   d_g  = d_c + (scale - 1) * g
 * </pre>
 *
 * <p>where {@code orthogonal(diff, d_c)} removes the component of {@code diff} parallel to the
 * conditional estimate, treating each batch element as one vector over channels and time. The
 * result is returned in the model's output space, {@code v_g = (x - d_g) / sigma}, which after
 * substitution is {@code v_c - (scale - 1) * g / sigma}; since {@code diff / sigma = v_u - v_c}
 * and the projection is linear, the division by {@code sigma} cancels exactly and the guided
 * output is computed without it. A scale of one leaves the conditional prediction unchanged.</p>
 *
 * <p>The whole combination is a single computation graph; nothing is evaluated on the host. The
 * projection normalises by the length of the conditional estimate, so it is undefined for an
 * all-zero conditional estimate, a case that does not arise for a noisy latent.</p>
 *
 * @see DiffusionSampler#setGuidance(ClassifierFreeGuidance, PackedCollection, PackedCollection)
 */
public class ClassifierFreeGuidance implements CodeFeatures {

	/** Guidance scale; one disables guidance. */
	private final double scale;

	/** Fraction of the guidance difference taken from its component orthogonal to the conditional estimate. */
	private final double apgScale;

	/**
	 * Creates guidance with full adaptive projection, which is the reference default.
	 *
	 * @param scale the guidance scale (one disables guidance)
	 */
	public ClassifierFreeGuidance(double scale) {
		this(scale, 1.0);
	}

	/**
	 * Creates guidance with the given blend between plain and projected guidance.
	 *
	 * @param scale    the guidance scale (one disables guidance)
	 * @param apgScale the fraction of the guidance difference taken from its component orthogonal
	 *                 to the conditional estimate; zero is plain classifier-free guidance and one
	 *                 is full adaptive projected guidance
	 */
	public ClassifierFreeGuidance(double scale, double apgScale) {
		if (apgScale < 0.0 || apgScale > 1.0) {
			throw new IllegalArgumentException("apgScale must lie in [0, 1]");
		}

		this.scale = scale;
		this.apgScale = apgScale;
	}

	/**
	 * The guidance scale.
	 *
	 * @return the scale
	 */
	public double getScale() { return scale; }

	/**
	 * The adaptive projection blend.
	 *
	 * @return the fraction of the guidance difference taken from the orthogonal component
	 */
	public double getApgScale() { return apgScale; }

	/**
	 * Whether guidance changes the prediction at all.
	 *
	 * @return true unless the scale is exactly one
	 */
	public boolean isActive() { return scale != 1.0; }

	/**
	 * Builds the guided model output from the conditional and unconditional predictions.
	 *
	 * @param x             the noisy latent the predictions were made for
	 * @param sigma         the noise level of {@code x}
	 * @param conditional   the model output under the intended conditioning
	 * @param unconditional the model output under the negative conditioning
	 * @return a producer for the guided model output, in the same space as the inputs
	 */
	public CollectionProducer guide(PackedCollection x, double sigma,
									PackedCollection conditional, PackedCollection unconditional) {
		TraversalPolicy shape = conditional.getShape();
		int batch = shape.length(0);
		int width = shape.getTotalSize() / batch;
		TraversalPolicy vectors = shape(batch, width);

		CollectionProducer difference = cp(conditional).subtract(cp(unconditional)).reshape(vectors);

		CollectionProducer guided;
		if (apgScale == 0.0) {
			guided = difference;
		} else {
			CollectionProducer denoised = cp(x).subtract(cp(conditional).multiply(sigma)).reshape(vectors);
			CollectionProducer direction = normalize(denoised);
			CollectionProducer parallel = direction.multiply(
					repeat(width, dotProduct(difference, direction)).reshape(vectors));
			CollectionProducer orthogonal = difference.subtract(parallel);
			guided = apgScale == 1.0 ? orthogonal :
					orthogonal.multiply(apgScale).add(difference.multiply(1.0 - apgScale));
		}

		return cp(conditional).add(guided.reshape(shape).multiply(scale - 1.0));
	}
}
