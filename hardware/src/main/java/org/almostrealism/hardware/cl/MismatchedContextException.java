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

import org.almostrealism.hardware.HardwareException;
import org.jocl.CLException;

/**
 * Exception thrown when attempting to use OpenCL resources across incompatible contexts.
 *
 * <p>Indicates that an operation tried to use a {@link CLDataContext} that doesn't match
 * the currently active context, or that no OpenCL context is available.</p>
 *
 * <h2>Example Error Messages</h2>
 *
 * <pre>{@code
 * // Different contexts:
 * "Attempting to use CLDataContext@GPU when CLDataContext@CPU is in effect"
 *
 * // No context available:
 * "Attempting to use CLDataContext@GPU when no context supporting OpenCL is in effect"
 * }</pre>
 *
 * @see CLExceptionProcessor
 * @see HardwareException
 */
public class MismatchedContextException extends HardwareException {
	public MismatchedContextException(CLDataContext targetContext, CLDataContext actualContext, CLException cause) {
		super(text(targetContext, actualContext), cause);
	}

	protected static String text(CLDataContext targetContext, CLDataContext actualContext) {
		if (actualContext == null) {
			return "Attempting to use " + name(targetContext) + " when no context supporting OpenCL is in effect";
		} else {
			return "Attempting to use " + name(targetContext) + " when " + name(actualContext) + " is in effect";
		}
	}

	public static String name(CLDataContext ctx) {
		String name = String.valueOf(ctx.toString());
		if (name.contains(".")) {
			name = name.substring(name.lastIndexOf(".") + 1);
		}

		return name;
	}
}
