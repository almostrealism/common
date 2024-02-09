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

import java.util.OptionalInt;

public class NoOpKernelStructureContext implements KernelStructureContext {
	private OptionalInt kernelMaximum;

	public NoOpKernelStructureContext() { this.kernelMaximum = OptionalInt.empty(); }

	public NoOpKernelStructureContext(int kernelMaximum) { this.kernelMaximum = OptionalInt.of(kernelMaximum); }

	@Override
	public OptionalInt getKernelMaximum() { return kernelMaximum; }

	@Override
	public KernelSeriesProvider getSeriesProvider() { return null; }

	@Override
	public KernelTraversalProvider getTraversalProvider() { return null; }

	@Override
	public NoOpKernelStructureContext asNoOp() { return this; }
}
