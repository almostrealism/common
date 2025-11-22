/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.geometry;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.hardware.MemoryData;

/**
 * A wrapper class that adds {@link DimensionAware} functionality to an existing kernel.
 * This class wraps an {@link Evaluable} and delegates dimension settings to it,
 * ensuring that the wrapped kernel receives rendering dimension information.
 *
 * <p>This is useful when you need to pass dimension information through the
 * producer chain to kernels that need to know the image size for their computations.</p>
 *
 * @param <T> the type of data produced by the kernel
 * @author Michael Murray
 * @see DimensionAware
 */
public class DimensionAwareKernel<T extends MemoryData> implements Producer<T>, DimensionAware {
	private Evaluable<T> k;

	/**
	 * Constructs a new DimensionAwareKernel wrapping the specified evaluable.
	 *
	 * @param k the evaluable to wrap, must implement {@link DimensionAware}
	 * @throws IllegalArgumentException if the provided evaluable does not implement DimensionAware
	 */
	public DimensionAwareKernel(Evaluable<T> k) {
		if (k instanceof DimensionAware == false) {
			throw new IllegalArgumentException(k == null ? null : k.getClass() +
												" is not DimensionAware");
		}

		this.k = k;
	}

	/**
	 * Returns the wrapped evaluable.
	 *
	 * @return the underlying evaluable kernel
	 */
	@Override
	public Evaluable<T> get() {
		return k;
	}

	/**
	 * {@inheritDoc}
	 * <p>Delegates the dimension settings to the wrapped kernel.</p>
	 */
	@Override
	public void setDimensions(int width, int height, int ssw, int ssh) {
		((DimensionAware) k).setDimensions(width, height, ssw, ssh);
	}
}
