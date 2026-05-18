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

/**
 * Exception thrown when OpenCL operations receive invalid parameter values.
 *
 * <p>Indicates CL_INVALID_VALUE error from OpenCL runtime, typically caused by
 * out-of-bounds memory access, invalid buffer sizes, or incorrect offsets.</p>
 *
 * <h2>Example Error Message</h2>
 *
 * <pre>{@code
 * InvalidValueException: Source Index 0, Destination Index 1024, Length 2048
 * (Destination Total Memory Length 1024)
 * }</pre>
 *
 * @see CLExceptionProcessor
 * @see HardwareException
 */
public class InvalidValueException extends HardwareException {
	/**
	 * Creates an invalid value exception with a generic message.
	 *
	 * @param cause The underlying {@link CLException}
	 */
	public InvalidValueException(CLException cause) {
		super("Invalid Value", cause);
	}

	/**
	 * Creates an invalid value exception with index and length information.
	 *
	 * @param cause     The underlying {@link CLException}
	 * @param srcIndex  Source buffer index that caused the error
	 * @param destIndex Destination buffer index that caused the error
	 * @param length    Length of the failed transfer
	 */
	public InvalidValueException(CLException cause, int srcIndex, int destIndex, int length) {
		super(message(srcIndex, destIndex, length), cause);
	}

	/**
	 * Creates an invalid value exception wrapping an existing one with additional destination size info.
	 *
	 * @param cause Existing invalid value exception
	 * @param size  Total destination memory length at the time of the error
	 */
	public InvalidValueException(InvalidValueException cause, int size) {
		super(cause.getMessage() + " (Destination Total Memory Length " + size + ")", cause);
	}

	/**
	 * Formats an error message describing the source index, destination index, and length of a failed operation.
	 *
	 * @param srcIndex  Source buffer index
	 * @param destIndex Destination buffer index
	 * @param length    Length of the operation
	 * @return Formatted error message
	 */
	protected static String message(int srcIndex, int destIndex, int length) {
		return "Source Index " + srcIndex + ", Destination Index " + destIndex + ", Length " + length;
	}

	/**
	 * Creates an invalid value exception from a CL exception if it matches {@code CL_INVALID_VALUE}.
	 *
	 * @param e         CL exception to check
	 * @param srcIndex  Source buffer index
	 * @param destIndex Destination buffer index
	 * @param length    Length of the operation
	 * @return An {@link Optional} containing a new exception if the error code matches, or empty
	 */
	public static Optional<HardwareException> from(CLException e, int srcIndex, int destIndex, int length) {
		if ("CL_INVALID_VALUE".equals(e.getMessage())) {
			return Optional.of(new InvalidValueException(e, srcIndex, destIndex, length));
		}

		return Optional.empty();
	}
}
