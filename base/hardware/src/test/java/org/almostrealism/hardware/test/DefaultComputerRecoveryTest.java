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

package org.almostrealism.hardware.test;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.Precision;
import org.almostrealism.hardware.DefaultComputer;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.jni.NativeDataContext;
import org.junit.Assert;
import org.junit.Test;

/**
 * {@link DefaultComputer#getScopeInstructionsManager} must not fall back to a cached manager's
 * already-destroyed {@link ComputeContext} when no {@link io.almostrealism.compute.Computation}
 * was supplied to select a live replacement.
 *
 * <p>Unlike most tests in this project, this one does not extend {@code TestSuiteBase}: that class
 * lives in the engine layer, which sits above this module, so depending on it would invert the
 * module graph. This needs no engine-layer support: a {@link NativeDataContext} standing in for
 * a destroyed context is enough to reach the recovery branch without compiling anything.</p>
 */
public class DefaultComputerRecoveryTest {
	/**
	 * With no computation to select a live context, recovery must fail fast rather than
	 * recreate a manager bound to the same destroyed context that just failed the check.
	 */
	@Test(timeout = 30000)
	public void nullComputationFailsFastInsteadOfReusingDeadContext() {
		NativeDataContext dataContext = new NativeDataContext("test-native-recovery", Precision.FP64, 1024L * 1024);
		dataContext.init();

		ComputeContext<MemoryData> destroyed = dataContext.getComputeContexts().get(0);
		dataContext.destroy();
		Assert.assertTrue("The compute context should report destroyed once its data context is",
				destroyed.isDestroyed() || destroyed.getDataContext().isDestroyed());

		DefaultComputer computer = Hardware.getLocalHardware().getComputer();
		String signature = "test-null-computation-recovery-" + System.identityHashCode(destroyed);

		try {
			computer.getScopeInstructionsManager(signature, null, destroyed,
					() -> {
						throw new UnsupportedOperationException(
								"The scope should never be compiled once recovery has failed fast");
					});
			Assert.fail("getScopeInstructionsManager() should fail fast when computation is null " +
					"and the cached manager's context is destroyed");
		} catch (IllegalStateException expected) {
			Assert.assertTrue("The message should name the signature",
					expected.getMessage().contains(signature));
		}
	}
}
