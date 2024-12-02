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

package io.almostrealism.kernel;

import io.almostrealism.code.OperationInfo;

import java.util.OptionalLong;

public class DefaultKernelStructureContext implements KernelStructureContext {
	private OptionalLong count;

	public DefaultKernelStructureContext() {
		this.count = OptionalLong.empty();
	}

	public DefaultKernelStructureContext(long count) {
		this.count = OptionalLong.of(count);
	}

	@Override
	public OptionalLong getKernelMaximum() {
		return count;
	}

	@Override
	public KernelSeriesProvider getSeriesProvider() {
		if (count.isPresent()) {
			return KernelSeriesMatcher.defaultProvider(
					OperationInfo.metadataForValue(this),
					Math.toIntExact(count.getAsLong()));
		} else {
			return KernelSeriesMatcher.defaultProvider(
					OperationInfo.metadataForValue(this));
		}
	}

	@Override
	public KernelTraversalProvider getTraversalProvider() {
		return null;
	}
}
