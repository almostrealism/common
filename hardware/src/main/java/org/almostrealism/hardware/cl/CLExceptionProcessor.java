/*
 * Copyright 2021 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware.cl;

import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.jocl.CLException;

public class CLExceptionProcessor {
	public static HardwareException process(CLException e, CLMemoryProvider provider, int srcIndex, int destIndex, int length) {
		if ("CL_INVALID_CONTEXT".equals(e.getMessage())) {
			if (provider.getContext() == Hardware.getLocalHardware().getDataContext()) {
				return new InvalidContextException(provider.getContext().toString(), e);
			} else {
				return new MismatchedContextException(provider.getContext(), Hardware.getLocalHardware().getDataContext(), e);
			}
		} else if ("CL_INVALID_VALUE".equals(e.getMessage())) {
			return new InvalidValueException(e, srcIndex, destIndex, length);
		} else {
			return new HardwareException(e, (long) length * provider.getNumberSize());
		}
	}
}
