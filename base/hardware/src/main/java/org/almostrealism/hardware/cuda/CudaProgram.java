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

package org.almostrealism.hardware.cuda;

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.uml.Signature;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.TimingMetric;

/**
 * One generated CUDA program: its source, compiled with NVRTC for the device and loaded
 * as a {@link CUModule}, and the kernel it defines.
 *
 * <p>The instruction-set monitoring flags of {@link HardwareOperator} are honored, writing
 * the source to {@code cuda_instruction_set_N.cu} in the instruction-set output directory.</p>
 */
public class CudaProgram implements OperationInfo, Signature, Destroyable, ConsoleFeatures {
	/** Time spent compiling and loading CUDA programs. */
	public static TimingMetric compileTime = Hardware.console.timing("cudaCompile");

	/** The context the program is loaded into. */
	private final CudaComputeContext context;

	/** Metadata describing the operation this program implements. */
	private final OperationMetadata metadata;

	/** The kernel name. */
	private final String func;

	/** The CUDA C++ source. */
	private final String src;

	/** The loaded module, once compiled. */
	private CUModule module;

	/** The kernel, once compiled. */
	private CUFunction function;

	/**
	 * Creates a program. It is not compiled until {@link #compile()} is called.
	 *
	 * @param context  the context to load the program into
	 * @param metadata metadata describing the operation, or null
	 * @param func     the kernel name
	 * @param src      the CUDA C++ source
	 */
	public CudaProgram(CudaComputeContext context, OperationMetadata metadata, String func, String src) {
		this.context = context;
		this.metadata = (metadata == null ?
				new OperationMetadata((String) null, null) : metadata)
				.withContextName(context.getDataContext().getName());
		this.func = func;
		this.src = src;
	}

	/** Returns the kernel name. */
	public String getName() { return func; }

	@Override
	public OperationMetadata getMetadata() { return metadata; }

	/** Returns the kernel, once compiled. */
	public CUFunction getFunction() {
		if (function == null) {
			throw new HardwareException("CudaProgram " + func + " is unavailable");
		}

		return function;
	}

	/**
	 * Compiles the source with NVRTC and loads the result.
	 *
	 * @throws HardwareException if compilation or loading fails
	 */
	public void compile() {
		if (HardwareOperator.enableInstructionSetMonitoring ||
				(HardwareOperator.enableLargeInstructionSetMonitoring && src.length() > 10000)) {
			recordInstructionSet();
		}

		long start = System.nanoTime();

		try {
			CudaDataContext dc = context.getDataContext();
			byte[] image = dc.getDevice().compile(src, func + ".cu");
			CUModule loaded = dc.getCudaContext().loadModule(image);

			try {
				function = loaded.getFunction(func);
			} catch (RuntimeException e) {
				loaded.release();
				throw e;
			}

			module = loaded;
		} catch (HardwareException e) {
			if (HardwareOperator.enableFailedInstructionSetMonitoring) recordInstructionSet();
			throw e;
		} finally {
			compileTime.addEntry(System.nanoTime() - start);
		}
	}

	/** Writes the source to the instruction-set output directory. */
	protected void recordInstructionSet() {
		log("Wrote " + HardwareOperator.recordInstructionSet("cuda", "cu", src));
	}

	@Override
	public String signature() { return getMetadata().getSignature(); }

	/** Returns true if the program is not compiled or has been destroyed. */
	public boolean isDestroyed() {
		return function == null;
	}

	@Override
	public void destroy() {
		function = null;

		if (module != null) {
			module.release();
			module = null;
		}
	}

	@Override
	public String describe() {
		return getMetadata().getDisplayName();
	}

	@Override
	public Console console() { return Hardware.console; }
}
