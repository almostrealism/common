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

import io.almostrealism.sequence.KernelSeriesMatcher;
import io.almostrealism.profile.OperationInfo;

import java.util.OptionalLong;

/**
 * A {@link KernelStructureContext} that provides a default {@link KernelSeriesProvider}
 * based on the kernel element count and has no traversal provider.
 *
 * <p>The series provider is obtained from {@link KernelSeriesMatcher#defaultProvider} and
 * takes the optional kernel maximum into account when constructing its matching strategy.</p>
 */
public class DefaultKernelStructureContext implements KernelStructureContext {
	/** The number of kernel elements, or empty if the kernel size is not yet known. */
	private OptionalLong count;

	/**
	 * Creates a {@link DefaultKernelStructureContext} with no fixed kernel maximum.
	 */
	public DefaultKernelStructureContext() {
		this.count = OptionalLong.empty();
	}

	/**
	 * Creates a {@link DefaultKernelStructureContext} with the given kernel
	 * iteration count from {@link #getKernelMaximum()}.
	 *
	 * @param count the kernel iteration count; must be {@code > 0}.
	 *              See {@link KernelStructureContext#getKernelMaximum()} for
	 *              why {@code 0} is forbidden. If the bound is genuinely
	 *              unknown, use the no-arg constructor so
	 *              {@code getKernelMaximum()} returns
	 *              {@link OptionalLong#empty()}.
	 * @throws IllegalArgumentException if {@code count <= 0}
	 */
	public DefaultKernelStructureContext(long count) {
		if (count <= 0) {
			throw new IllegalArgumentException(
					"DefaultKernelStructureContext requires count > 0 (got "
					+ count + "). A zero-iteration kernel does not exist — "
					+ "use the no-arg constructor for an unbounded context. "
					+ "See KernelStructureContext#getKernelMaximum().");
		}
		this.count = OptionalLong.of(count);
	}

	/** {@inheritDoc} */
	@Override
	public OptionalLong getKernelMaximum() {
		return count;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns a default {@link KernelSeriesProvider} from {@link KernelSeriesMatcher},
	 * seeded with the kernel element count when present.</p>
	 */
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

	/**
	 * {@inheritDoc}
	 *
	 * @return {@code null} always — this context has no traversal provider
	 */
	@Override
	public KernelTraversalProvider getTraversalProvider() {
		return null;
	}
}
