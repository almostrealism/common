/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.hardware.metal;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.code.LanguageOperations;
import io.almostrealism.scope.Scope;
import io.almostrealism.code.ScopeEncoder;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.almostrealism.hardware.Hardware;

import java.util.ArrayList;
import java.util.List;

public class MetalComputeContext extends AbstractComputeContext {
	public static boolean enableFastQueue = false;

	private static String includes = "#include <metal_stdlib>\n" +
									"using metal::min;\n" +
									"using metal::max;\n" +
									"using metal::fmod;\n" +
									"using metal::floor;\n" +
									"using metal::ceil;\n" +
									"using metal::pow;\n" +
									"using metal::exp;\n" +
									"using metal::sin;\n";

	private MTLDevice mainDevice, kernelDevice;
	private MTLCommandQueue queue;
	private MTLCommandQueue fastQueue;
	private MTLCommandQueue kernelQueue;

	private MetalCommandRunner runner;

	private List<MetalOperatorMap> instructionSets;

	public MetalComputeContext(Hardware hardware) {
		super(hardware, true, false);
		this.instructionSets = new ArrayList<>();
	}

	protected void init(MTLDevice mainDevice, MTLDevice kernelDevice) {
		if (queue != null) return;

		this.mainDevice = mainDevice;
		this.kernelDevice = kernelDevice;

		if (Hardware.enableVerbose) {
			System.out.println("Hardware[" + getName() + "]: Max Threadgroup Size (" +
					mainDevice.maxThreadgroupWidth() + ", " +
					mainDevice.maxThreadgroupHeight() + ", " +
					mainDevice.maxThreadgroupDepth() + ")");
		}

		queue = mainDevice.newCommandQueue();
		if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: Metal command queue initialized");

		if (enableFastQueue) {
			fastQueue = mainDevice.newCommandQueue();
			if (Hardware.enableVerbose)
				System.out.println("Hardware[" + getName() + "]: Metal fast command queue initialized");
		}

		if (kernelDevice != null) {
			kernelQueue = kernelDevice.newCommandQueue();
			if (Hardware.enableVerbose)
				System.out.println("Hardware[" + getName() + "]: Metal kernel command queue initialized");
		}

		this.runner = new MetalCommandRunner(queue);
	}

	@Override
	public LanguageOperations getLanguage() {
		return new MetalLanguageOperations();
	}

	@Override
	public InstructionSet deliver(Scope scope) {
		StringBuilder buf = new StringBuilder();
		buf.append(includes);

		ScopeEncoder enc = new ScopeEncoder(pw -> new MetalPrintWriter(pw, scope.getName()), Accessibility.EXTERNAL);
		buf.append(enc.apply(scope));

		MetalOperatorMap instSet = new MetalOperatorMap(this, scope.getMetadata(), scope.getName(), buf.toString());
		instructionSets.add(instSet);
		return instSet;
	}

	@Override
	public boolean isKernelSupported() { return true; }

	@Override
	public String getKernelIndex(int dimension) {
		if (dimension != 0)
			throw new IllegalArgumentException();

		return "global_id";
	}

	public MTLDevice getMtlDevice() { return mainDevice; }
	public MTLCommandQueue getMtlQueue() { return queue; }
	public MTLCommandQueue getMtlQueue(boolean kernel) { return kernel ? getKernelMtlQueue() : getMtlQueue(); }
	public MTLCommandQueue getFastMtlQueue() { return fastQueue == null ? getMtlQueue() : fastQueue; }
	public MTLCommandQueue getKernelMtlQueue() { return kernelQueue == null ? getMtlQueue() : kernelQueue; }

	public MetalCommandRunner getCommandRunner() {
		return runner;
	}

	@Override
	public void destroy() {
		this.instructionSets.forEach(InstructionSet::destroy);
		if (queue != null) queue.release();
		if (fastQueue != null) fastQueue.release();
		if (kernelQueue != null) kernelQueue.release();
		queue = null;
		fastQueue = null;
		kernelQueue = null;
	}
}
