/*
 * Copyright 2024 Michael Murray
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
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.Scope;
import io.almostrealism.lang.ScopeEncoder;
import io.almostrealism.util.FrequencyCache;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.Map;

public class MetalComputeContext extends AbstractComputeContext implements ConsoleFeatures {
	public static boolean enableFastQueue = false;
	public static boolean enableInstructionSetReuse = false;

	private static String includes = "#include <metal_stdlib>\n" +
									"using metal::min;\n" +
									"using metal::max;\n" +
									"using metal::fmod;\n" +
									"using metal::floor;\n" +
									"using metal::ceil;\n" +
									"using metal::abs;\n" +
									"using metal::pow;\n" +
									"using metal::exp;\n" +
									"using metal::log;\n" +
									"using metal::sin;\n" +
									"using metal::cos;\n" +
									"using metal::tan;\n" +
									"using metal::tanh;\n";

	private MTLDevice mainDevice;
	private MTLCommandQueue queue;
	private MTLCommandQueue fastQueue;

	private MetalCommandRunner runner;

	private FrequencyCache<String, MetalOperatorMap> instructionSets;

	public MetalComputeContext(MetalDataContext dc) {
		super(dc);
		this.instructionSets = new FrequencyCache<>(500, 0.4);
		this.instructionSets.setEvictionListener((name, inst) -> inst.destroy());
	}

	protected void init(MTLDevice mainDevice) {
		if (queue != null) return;

		this.mainDevice = mainDevice;

		if (Hardware.enableVerbose) {
			System.out.println("Hardware[" + getDataContext().getName() + "]: Max Threadgroup Size (" +
					mainDevice.maxThreadgroupWidth() + ", " +
					mainDevice.maxThreadgroupHeight() + ", " +
					mainDevice.maxThreadgroupDepth() + ")");
		}

		queue = mainDevice.newCommandQueue();
		if (Hardware.enableVerbose) System.out.println("Hardware[" + getDataContext().getName() + "]: Metal command queue initialized");

		if (enableFastQueue) {
			fastQueue = mainDevice.newCommandQueue();
			if (Hardware.enableVerbose)
				System.out.println("Hardware[" + getDataContext().getName() + "]: Metal fast command queue initialized");
		}

		this.runner = new MetalCommandRunner(queue);
	}

	@Override
	public LanguageOperations getLanguage() {
		return new MetalLanguageOperations(getDataContext().getPrecision());
	}

	@Override
	public InstructionSet deliver(Scope scope) {
		if (instructionSets.containsKey(key(scope.getName(), scope.signature()))) {
			if (enableInstructionSetReuse) {
				warn("Compiling instruction set " + scope.getName() +
						" with duplicate signature");
			} else {
				warn("Recompiling instruction set " + scope.getName());
			}

			instructionSets.evict(key(scope.getName(), scope.signature()));
		}

		long start = System.nanoTime();
		StringBuilder buf = new StringBuilder();

		try {
			buf.append(includes);
			buf.append("\n");

			ScopeEncoder enc = new ScopeEncoder(pw -> new MetalPrintWriter(pw, scope.getName(), getLanguage().getPrecision()), Accessibility.EXTERNAL);
			buf.append(enc.apply(scope));

			MetalOperatorMap instSet = new MetalOperatorMap(this, scope.getMetadata(), scope.getName(), buf.toString());
			instructionSets.put(key(scope.getName(), scope.signature()), instSet);
			return instSet;
		} finally {
			recordCompilation(scope, buf::toString, System.nanoTime() - start);
		}
	}

	protected void accessed(String key, String signature) {
		instructionSets.get(key(key, signature));
	}

	@Override
	public boolean isCPU() { return false; }

	public MTLDevice getMtlDevice() { return mainDevice; }
	public MTLCommandQueue getMtlQueue() { return queue; }
	public MetalCommandRunner getCommandRunner() { return runner; }

	@Override
	public void destroy() {
		this.instructionSets.forEach((name, inst) -> inst.destroy());
		this.instructionSets = null;

		if (queue != null) queue.release();
		if (fastQueue != null) fastQueue.release();

		queue = null;
		fastQueue = null;
	}

	@Override
	public Console console() { return Hardware.console; }

	protected static String key(String name, String signature) {
		return enableInstructionSetReuse ? signature : name;
	}
}
