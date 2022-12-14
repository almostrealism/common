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

public interface KernelSupport {
	default boolean isKernelEnabled() { return true; }

	default String getKernelIndex(String variableName, int kernelIndex) {
		return kernelIndex < 0 ? "" :
				"get_global_id(" + kernelIndex + ") * " + getValueSizeName(variableName) + " + ";
	}

	static String getValueSizeName(String variableName) {
		return variableName + "Size";
	}
}
