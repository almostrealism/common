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

import java.util.OptionalLong;

public class NoOpKernelStructureContext implements KernelStructureContext {
	private OptionalLong kernelMaximum;

	public NoOpKernelStructureContext() { this.kernelMaximum = OptionalLong.empty(); }

	public NoOpKernelStructureContext(long kernelMaximum) { this.kernelMaximum = OptionalLong.of(kernelMaximum); }

	@Override
	public OptionalLong getKernelMaximum() { return kernelMaximum; }

	@Override
	public KernelSeriesProvider getSeriesProvider() { return null; }

	@Override
	public KernelTraversalProvider getTraversalProvider() { return null; }

	@Override
	public NoOpKernelStructureContext asNoOp() { return this; }
}
