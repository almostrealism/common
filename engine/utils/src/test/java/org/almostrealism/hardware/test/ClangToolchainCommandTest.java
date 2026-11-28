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

import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.cl.CLDataContext;
import org.almostrealism.hardware.jni.Clang;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Verifies the compile command a non-local (bundled) toolchain receives.
 *
 * <p>A bundled toolchain carries the SDK's headers in its own include directory, so that
 * directory is passed as a system include ({@code -isystem}), never as {@code -I}: warnings
 * from the SDK's headers are then suppressed by the compiler itself, while warnings in the
 * generated code are not. A bundled toolchain also cannot find the OpenCL framework, so
 * enabling native OpenCL code with one is rejected up front with a message naming the
 * setting, instead of failing at link time with "framework not found".</p>
 */
public class ClangToolchainCommandTest extends TestSuiteBase {
	/** A toolchain path that is never run; command construction only needs the path. */
	private static final String BUNDLED = "/nonexistent/bundled/bin/clang";

	/** The bundled include directory is a system include directory, never a {@code -I} one. */
	@Test(timeout = 60000)
	public void bundledIncludesAreSystemIncludes() {
		List<String> command = new Clang(BUNDLED, false).getCommand("input.c", "output", true);

		int flag = command.indexOf("-isystem");
		Assert.assertTrue("no -isystem in " + command, flag >= 0 && flag + 1 < command.size());
		String bundled = command.get(flag + 1);
		Assert.assertFalse("-isystem must be followed by a directory: " + command, bundled.startsWith("-"));
		Assert.assertFalse("the bundled includes must not also be a -I directory: " + command,
				command.contains("-I" + bundled));
	}

	/** The local toolchain finds its SDK by itself and receives no bundled include directory. */
	@Test(timeout = 60000)
	public void localToolchainHasNoBundledIncludes() {
		List<String> command = new Clang().getCommand("input.c", "output", true);
		Assert.assertFalse("unexpected -isystem in " + command, command.contains("-isystem"));
	}

	/**
	 * Native OpenCL code with a bundled toolchain is refused with a message naming the setting,
	 * while the local toolchain still links against the OpenCL framework.
	 */
	@Test(timeout = 60000)
	public void nativeOpenClRequiresTheLocalToolchain() {
		boolean previous = CLDataContext.enableClNative;

		try {
			CLDataContext.enableClNative = true;

			try {
				new Clang(BUNDLED, false).getCommand("input.c", "output", true);
				Assert.fail("a bundled toolchain must not be given -framework OpenCL");
			} catch (HardwareException e) {
				Assert.assertTrue(e.getMessage(), e.getMessage().contains("AR_HARDWARE_CL_NATIVE"));
			}

			List<String> local = new Clang().getCommand("input.c", "output", true);
			int framework = local.indexOf("-framework");
			Assert.assertTrue("local toolchain lost -framework OpenCL: " + local,
					framework >= 0 && "OpenCL".equals(local.get(framework + 1)));
		} finally {
			CLDataContext.enableClNative = previous;
		}
	}
}
