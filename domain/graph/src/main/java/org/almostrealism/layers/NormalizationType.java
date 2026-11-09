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

package org.almostrealism.layers;

import org.almostrealism.hardware.Hardware;

/**
 * The family of feature normalization applied by a layer that is parameterized by a scale
 * (and optionally a shift) vector.
 *
 * <p>Both families divide by a statistic of the features; they differ in whether the mean is
 * removed first. A model's checkpoint fixes the family, so builders that assemble blocks from
 * checkpoint weights take the type explicitly rather than inferring it from which parameters
 * happen to be present.</p>
 *
 * @see NormalizationLayerFeatures#norm(NormalizationType, org.almostrealism.collect.PackedCollection,
 *      org.almostrealism.collect.PackedCollection, double, io.almostrealism.compute.ComputeRequirement...)
 */
public enum NormalizationType {
	/** Layer normalization: the features are centered on their mean and divided by their standard deviation. */
	LAYER {
		/**
		 * {@inheritDoc}
		 *
		 * <p>For layer normalization this is the epsilon of the local hardware's precision,
		 * {@link Hardware#epsilon()}: {@code 1e-5} at single precision.</p>
		 */
		@Override
		public double defaultEpsilon() {
			return Hardware.getLocalHardware().epsilon();
		}
	},

	/** Root-mean-square normalization: the features are divided by their root mean square without centering. */
	RMS {
		/**
		 * {@inheritDoc}
		 *
		 * <p>For root-mean-square normalization this is {@code 1e-5}.</p>
		 */
		@Override
		public double defaultEpsilon() {
			return 1e-5;
		}
	};

	/**
	 * Returns the epsilon a normalization of this family adds to its statistic when the builder is
	 * given none, as {@link NormalizationLayerFeatures#norm(NormalizationType,
	 * org.almostrealism.collect.PackedCollection, org.almostrealism.collect.PackedCollection,
	 * io.almostrealism.compute.ComputeRequirement...)} is: the one definition of that default, so that a
	 * builder which must state its epsilon explicitly (a PDSL asset, whose normalization stages take
	 * one) builds the same normalization as the default.
	 *
	 * @return the default epsilon of this family
	 */
	public abstract double defaultEpsilon();
}
