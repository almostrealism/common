/*
 * Copyright 2020 Michael Murray
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

import io.almostrealism.code.Argument;
import io.almostrealism.code.Variable;
import org.almostrealism.relation.NameProvider;

public interface ComputerFeatures extends HardwareFeatures, NameProvider {
	boolean enableKernel = true;

	default Variable getOutputVariable() { return getArgument(0); }

	@Override
	default String getVariableValueName(Variable v, int pos, boolean assignment, int kernelIndex) {
		return getValueName(v, pos, assignment, enableKernel ? kernelIndex : -1);
	}

	default String getValueName(Variable v, int pos, boolean assignment, int kernelIndex) {
		String name;

		if (v instanceof Argument) {
			if (enableKernel) {
				String kernelOffset = kernelIndex < 0 ? "" :
						("get_global_id(" + kernelIndex + ") * " + v.getName() + "Size + ");

				if (pos == 0) {
					name = v.getName() + "[" + kernelOffset + v.getName() + "Offset]";
				} else {
					name = v.getName() + "[" + kernelOffset + v.getName() + "Offset + " + pos + "]";
				}
			} else {
				if (pos == 0) {
					name = v.getName() + "[" + v.getName() + "Offset]";
				} else {
					name = v.getName() + "[" + v.getName() + "Offset + " + pos + "]";
				}
			}
		} else {
			name = v.getName() + "[" + pos + "]";
		}

		if (isCastEnabled() && !assignment) {
			return "(float)" + name;
		} else {
			return name;
		}
	}
}