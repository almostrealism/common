/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.hardware;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.StaticReference;

import java.util.stream.IntStream;

public interface KernelSupport {
	boolean enableKernelDivisabilityFallback = true;

	default boolean isKernelEnabled() { return true; }

	static Expression index() {
		return kernelIndex(0);
	}

	static Expression<?> kernelIndex(int kernelIndex) {
		return new StaticReference<>(Integer.class, getKernelIndex(kernelIndex));
	}

	static String getKernelIndex(int kernelIndex) {
		return "get_global_id(" + kernelIndex + ")";
	}

	default String getKernelIndex(String variableName, int kernelIndex) {
		if (kernelIndex > 0) {
			throw new UnsupportedOperationException("Only one kernel dimension is currently supported");
		}

		return kernelIndex < 0 ? "" :
				getKernelIndex(kernelIndex) + " * " + getValueDimName(variableName, kernelIndex) + " + ";
	}

	static String getValueDimName(String variableName, int dim) {
		return variableName + "Dim" + dim;
	}

	static String getValueSizeName(String variableName) {
		return variableName + "Size";
	}

	/**
	 * There should exist some value for kernelSize = m / n, such that every
	 * argument is either of size m or size n. (More distinct multiples of
	 * n could be permitted with kernel operations that are multi-dimensional,
	 * but currently this is disallowed).
	 */
	static int inferKernelSize(int sizes[]) {
		int smallest = IntStream.of(sizes).min().orElse(1);
		int largest = IntStream.of(sizes).max().orElse(-1);
		int kernelSize = largest; // largest / smallest;
		if (kernelSize <= 0) return kernelSize;

		try {
			validateKernelSize(kernelSize, smallest, sizes);
		} catch (IllegalArgumentException e) {
			if (enableKernelDivisabilityFallback) {
				return largest;
			} else {
				throw e;
			}
		}

		return kernelSize;
	}

	static int validateKernelSize(int kernelSize, int smallest, int sizes[]) {
		int n = 1;

		for (int i = 0; i < sizes.length; i++) {
			if (sizes[i] % smallest != 0) {
				throw new IllegalArgumentException("Argument with count " + sizes[i] +
						" is not divisible by " + smallest + " (kernel size " + kernelSize + ")");
			}

			if (sizes[i] / smallest != n) {
				if (n == 1) {
					n = sizes[i] / smallest;
				} else if (sizes[i] / smallest != 1) {
					throw new IllegalArgumentException("Argument with count " + sizes[i] +
							" is not compatible with argument with count " + (n * smallest) +
							" when using kernel size " + kernelSize);
				}
			}
		}

		return kernelSize;
	}
}
