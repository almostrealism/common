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

public class DimensionAwareKernel<T extends MemoryData> implements Producer<T>, DimensionAware {
	private Evaluable<T> k;

	public DimensionAwareKernel(Evaluable<T> k) {
		if (k instanceof DimensionAware == false) {
			throw new IllegalArgumentException(k == null ? null : k.getClass() +
												" is not DimensionAware");
		}

		this.k = k;
	}

	@Override
	public Evaluable<T> get() {
		return k;
	}

	@Override
	public void setDimensions(int width, int height, int ssw, int ssh) {
		((DimensionAware) k).setDimensions(width, height, ssw, ssh);
	}
}
