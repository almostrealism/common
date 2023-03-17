/*
 * Copyright 2021 Michael Murray
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

import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.NameProvider;
import io.almostrealism.scope.Variable;

public interface ComputerFeatures extends HardwareFeatures, NameProvider {
	boolean enableKernel = Hardware.getLocalHardware().isKernelSupported();

	default boolean isContextKernelEnabled() {
		return Hardware.getLocalHardware().getComputeContext().isKernelSupported();
	}

//	@Override
//	default Variable getOutputVariable() { return getArgument(0); }

	@Override
	default String getVariableValueName(Variable v, String pos, boolean assignment, int kernelIndex) {
		return getValueName(v, pos, assignment, enableKernel ? kernelIndex : -1);
	}

	default String getValueName(Variable v, String pos, boolean assignment, int kernelIndex) {
		String name;

		if (v instanceof ArrayVariable) {
			if (isContextKernelEnabled() && v.getProducer() instanceof KernelSupport
					&& ((KernelSupport) v.getProducer()).isKernelEnabled()) {
				String kernelOffset = ((KernelSupport) v.getProducer()).getKernelIndex(v.getName(), kernelIndex);

				if (pos.equals("0") || pos.equals("(0)")) {
					name = v.getName() + "[" + kernelOffset + v.getName() + "Offset]";
				} else {
					name = v.getName() + "[" + kernelOffset + v.getName() + "Offset + (int) (" + pos + ")]";
				}
			} else {
				if (pos.equals("0")) {
					name = v.getName() + "[" + v.getName() + "Offset]";
				} else {
					name = v.getName() + "[" + v.getName() + "Offset + (int) (" + pos + ")]";
				}
			}
		} else {
			name = v.getName() + "[(int) (" + pos + ")]";
		}

		if (isCastEnabled() && !assignment) {
			return "(float)" + name;
		} else {
			return name;
		}
	}

	@Override
	default String getVariableDimName(ArrayVariable v, int dim) {
		return KernelSupport.getValueDimName(v.getName(), dim);
	}

	@Override
	default String getVariableSizeName(ArrayVariable v) {
		return KernelSupport.getValueSizeName(v.getName());
	}
}
