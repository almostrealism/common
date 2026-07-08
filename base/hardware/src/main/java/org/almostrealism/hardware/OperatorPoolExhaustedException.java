/*
 * Copyright 2026 Michael Murray
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

/**
 * Thrown when the runtime native-lib template class pool is exhausted and
 * no further operators can be obtained for an accelerated operation.
 *
 * <p>Raised when reserving a native operator template fails because the pool of
 * pre-compiled {@code GeneratedOperation} classes has been exhausted (every slot
 * has already been reserved). Exhaustion is a terminal condition for the current
 * rendering pass — further invocations along the same path will also fail. Callers
 * that encounter this exception should abort rather than retry.</p>
 *
 * <p>This is thrown specifically for pool exhaustion; other failures encountered
 * while obtaining an operator (for example a code-generation error) surface as an
 * ordinary {@link HardwareException} carrying the underlying cause, so they are not
 * misreported as pool exhaustion.</p>
 *
 * @see HardwareException
 */
public class OperatorPoolExhaustedException extends HardwareException {

	/**
	 * Creates an exception wrapping the cause of pool exhaustion.
	 *
	 * @param cause the exception thrown when the operator pool was exhausted
	 */
	public OperatorPoolExhaustedException(Exception cause) {
		super("Could not obtain operator", cause);
	}
}
