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

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.code.DataContext;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.jocl.CLException;

/**
 * Processes {@link CLException} instances into typed {@link HardwareException} subclasses.
 *
 * <p>Converts OpenCL error codes (CL_INVALID_CONTEXT, CL_INVALID_VALUE) into specific
 * exception types with enhanced error messages and debugging context.</p>
 *
 * <h2>Exception Mapping</h2>
 *
 * <pre>{@code
 * CL_INVALID_CONTEXT -> InvalidContextException or MismatchedContextException
 * CL_INVALID_VALUE   -> InvalidValueException (with index/length details)
 * Other errors       -> HardwareException (with source code if available)
 * }</pre>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * try {
 *     CL.clEnqueueWriteBuffer(...);
 * } catch (CLException e) {
 *     throw CLExceptionProcessor.process(e, provider, srcIdx, destIdx, length);
 * }
 * }</pre>
 *
 * @see InvalidContextException
 * @see InvalidValueException
 * @see MismatchedContextException
 */
public class CLExceptionProcessor {
	public static HardwareException process(CLException e, CLComputeContext ctx, String msg, String src) {
		HardwareException ex;

		if ("CL_INVALID_CONTEXT".equals(e.getMessage())) {
			ex = new InvalidContextException(ctx.toString(), e);
			if (msg != null) ex = new HardwareException(msg, ex);
		} else if ("CL_INVALID_VALUE".equals(e.getMessage())) {
			ex = new InvalidValueException(e);
			if (msg != null) ex = new HardwareException(msg, ex);
		} else {
			ex = new HardwareException(msg, e);
		}

		ex.setProgram(src);
		return ex;
	}

	public static HardwareException process(CLException e, CLMemoryProvider provider, int srcIndex, int destIndex, int length) {
		if ("CL_INVALID_CONTEXT".equals(e.getMessage())) {
			DataContext ctx = Hardware.getLocalHardware().getDataContext(ComputeRequirement.CL);

			if (provider.getContext() == ctx) {
				return new InvalidContextException(provider.getContext().toString(), e);
			} else if (ctx instanceof CLDataContext) {
				return new MismatchedContextException(provider.getContext(), (CLDataContext) ctx, e);
			} else {
				return new MismatchedContextException(provider.getContext(), null, e);
			}
		} else if ("CL_INVALID_VALUE".equals(e.getMessage())) {
			return new InvalidValueException(e, srcIndex, destIndex, length);
		} else {
			return new HardwareException(e, (long) length * provider.getNumberSize());
		}
	}
}
