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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Verifies that the JVM that surefire forks for tests is granted native access.
 *
 * <p>The hardware backends load native libraries ({@code System.load} in the JNI, CUDA and
 * Metal bindings, plus JOCL, ONNX Runtime and SentencePiece). JDK 24 and later print a
 * restricted-method warning for such a load unless the launcher passes
 * {@code --enable-native-access}, and a future JDK refuses it. The grant is made by the root
 * {@code maven-surefire-plugin} {@code argLine}, which every module inherits; this test fails
 * if that configuration is removed or replaced by a caller's {@code -DargLine}.</p>
 */
public class NativeAccessGrantTest extends TestSuiteBase {

	/** The launcher option that grants native access to every class on the classpath. */
	public static final String NATIVE_ACCESS_GRANT = "--enable-native-access=ALL-UNNAMED";

	/**
	 * The forked test JVM must have been started with the native-access grant.
	 */
	@Test(timeout = 10000)
	public void forkedJvmIsGrantedNativeAccess() {
		List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
		Assert.assertTrue("JVM input arguments " + arguments + " do not contain " + NATIVE_ACCESS_GRANT,
				arguments.contains(NATIVE_ACCESS_GRANT));
	}
}
