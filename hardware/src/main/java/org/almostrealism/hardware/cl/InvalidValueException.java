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

package org.almostrealism.hardware.cl;

import org.almostrealism.hardware.HardwareException;
import org.jocl.CLException;

import java.util.Optional;

public class InvalidValueException extends HardwareException {
	public InvalidValueException(CLException cause) {
		super("Invalid Value", cause);
	}

	public InvalidValueException(CLException cause, int srcIndex, int destIndex, int length) {
		super(message(srcIndex, destIndex, length), cause);
	}

	public InvalidValueException(InvalidValueException cause, int size) {
		super(cause.getMessage() + " (Destination Total Memory Length " + size + ")", cause);
	}

	protected static String message(int srcIndex, int destIndex, int length) {
		return "Source Index " + srcIndex + ", Destination Index " + destIndex + ", Length " + length;
	}

	public static Optional<HardwareException> from(CLException e, int srcIndex, int destIndex, int length) {
		if ("CL_INVALID_VALUE".equals(e.getMessage())) {
			return Optional.of(new InvalidValueException(e, srcIndex, destIndex, length));
		}

		return Optional.empty();
	}
}
