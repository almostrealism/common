package org.almostrealism.hardware;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.Computer;
import org.almostrealism.hardware.jni.NativeCompiler;

public abstract class AbstractComputeContext implements ComputeContext<MemoryData> {
	private DefaultComputer computer;

	protected AbstractComputeContext(Hardware hardware) { this(hardware, true, false); }

	protected AbstractComputeContext(Hardware hardware, boolean isCl, boolean isNative) {
		computer = isNative ? new DefaultComputer(NativeCompiler.factory(hardware, isCl).construct()) : new DefaultComputer();
	}

	@Override
	public DefaultComputer getComputer() { return computer; }
}
