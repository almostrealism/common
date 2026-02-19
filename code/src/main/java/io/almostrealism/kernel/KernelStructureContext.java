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

import io.almostrealism.expression.Expression;

import java.util.OptionalLong;

/** The KernelStructureContext interface. */
public interface KernelStructureContext {
	/** Performs the getKernelMaximum operation. */
	OptionalLong getKernelMaximum();

	/** Performs the getSeriesProvider operation. */
	KernelSeriesProvider getSeriesProvider();
	/** Performs the getTraversalProvider operation. */
	KernelTraversalProvider getTraversalProvider();

	/** Performs the isValidKernelSize operation. */
	default boolean isValidKernelSize(long size) {
		if ((getKernelMaximum().isPresent() && size !=
				getKernelMaximum().getAsLong()) ||
				(getSeriesProvider() != null && getSeriesProvider().getMaximumLength().isPresent() &&
						size != getSeriesProvider().getMaximumLength().getAsInt())) {
			return false;
		}

		return true;
	}

	/** Performs the simplify operation. */
	default Expression<?> simplify(Expression<?> expression) {
		Expression<?> e = expression.simplify(this);
		if (getSeriesProvider() != null) {
			e = getSeriesProvider().getSeries(e);
		}
		return e;
	}

	/** Performs the asNoOp operation. */
	default NoOpKernelStructureContext asNoOp() {
		return getKernelMaximum().stream()
				.mapToObj(NoOpKernelStructureContext::new)
				.findFirst().orElse(new NoOpKernelStructureContext());
	}
}
