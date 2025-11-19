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

import org.jocl.CLException;

/**
 * Runtime exception thrown when hardware acceleration operations fail.
 *
 * <p>{@link HardwareException} wraps errors from hardware backends (OpenCL, Metal, JNI) and
 * compilation failures, providing context like the program source code that failed to compile.</p>
 *
 * <h2>Common Causes</h2>
 *
 * <ul>
 *   <li><b>Compilation Errors:</b> Kernel/shader source code contains syntax or semantic errors</li>
 *   <li><b>Memory Errors:</b> Invalid buffer sizes, out-of-memory conditions</li>
 *   <li><b>Execution Errors:</b> Runtime failures during kernel execution</li>
 *   <li><b>Backend Errors:</b> OpenCL/Metal/JNI-specific errors (e.g., CL_INVALID_BUFFER_SIZE)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * try {
 *     operation.compile();
 *     operation.evaluate(args);
 * } catch (HardwareException e) {
 *     System.err.println("Hardware operation failed: " + e.getMessage());
 *
 *     // Check if program source is available
 *     if (e.getProgram() != null) {
 *         System.err.println("Failed program:");
 *         System.err.println(e.getProgram());
 *     }
 *
 *     // Check underlying cause
 *     if (e.getCause() instanceof CLException) {
 *         CLException cl = (CLException) e.getCause();
 *         System.err.println("OpenCL error: " + cl.getMessage());
 *     }
 * }
 * }</pre>
 *
 * <h2>Program Source Preservation</h2>
 *
 * <p>When compilation fails, the source code can be attached via {@link #setProgram} or constructor:</p>
 * <pre>{@code
 * try {
 *     compileKernel(sourceCode);
 * } catch (CLException e) {
 *     throw new HardwareException("Kernel compilation failed", e, sourceCode);
 * }
 * }</pre>
 *
 * @see AcceleratedOperation
 * @see HardwareOperator
 */
public class HardwareException extends RuntimeException {
	private String program;

	public HardwareException(String message) {
		super(message);
	}

	public HardwareException(String message, HardwareException cause) {
		super(message, cause);
	}

	public HardwareException(String message, Exception cause) {
		super(message, cause);
	}

	public HardwareException(String message, CLException cause) {
		super(message, cause);
	}

	public HardwareException(String message, CLException cause, String program) {
		super(message, cause);
		this.program = program;
	}

	public HardwareException(CLException e, long bufferSize) {
		this(messageForBuffer(e, bufferSize), e);
	}

	public String getProgram() { return program; }
	public void setProgram(String program) { this.program = program; }

	public static String messageForBuffer(CLException e, long bufferSize) {
		if ("CL_INVALID_BUFFER_SIZE".equals(e.getMessage())) {
			return "Buffer size of " + bufferSize + " bytes is invalid";
		} else {
			return e.getMessage();
		}
	}
}
