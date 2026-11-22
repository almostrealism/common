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

import io.almostrealism.code.Precision;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies that an interrupted native compilation does not leave the compiler process running.
 *
 * <p>A test timeout interrupts the thread waiting for the compiler. A compiler process that
 * outlives that wait keeps competing for the CPU with every compilation after it, which is how
 * one timeout turns into several.</p>
 */
public class NativeCompilerInterruptTest extends TestSuiteBase {

	/**
	 * Exposes the command runner of a {@link NativeCompiler} that compiles nothing itself.
	 */
	private static final class RunnerCompiler extends NativeCompiler {
		/**
		 * Creates a compiler whose library directory is the given directory.
		 *
		 * @param libDir directory for generated sources and libraries
		 */
		RunnerCompiler(String libDir) {
			super(Precision.FP64, null, libDir, "%NAME%", null, false);
		}

		/**
		 * Runs a command as this compiler runs a compiler invocation.
		 *
		 * @param command the command line to run
		 */
		void run(List<String> command) {
			runner("interrupt-test").accept(command);
		}
	}

	/**
	 * Interrupting the wait for a compiler process destroys the process, waits for it to exit,
	 * throws, and leaves the interrupt status set: the process is already gone when the runner
	 * returns, rather than merely asked to terminate.
	 */
	@Test(timeout = 60000)
	public void interruptedCompilationDestroysProcess() throws Exception {
		Path dir = Files.createTempDirectory("native-compiler-interrupt");
		Path pidFile = dir.resolve("pid");
		RunnerCompiler compiler = new RunnerCompiler(dir.toString());

		AtomicReference<Throwable> thrown = new AtomicReference<>();
		AtomicBoolean interruptedAfter = new AtomicBoolean();

		Thread waiter = new Thread(() -> {
			try {
				compiler.run(List.of("/bin/sh", "-c", "echo $$ > " + pidFile + "; exec sleep 50"));
			} catch (Throwable t) {
				thrown.set(t);
			} finally {
				interruptedAfter.set(Thread.currentThread().isInterrupted());
			}
		});

		waiter.start();
		long pid = awaitPid(pidFile);
		waiter.interrupt();
		waiter.join(10000);

		Assert.assertFalse("The wait did not end on the interrupt", waiter.isAlive());
		Assert.assertTrue("Expected a HardwareException but got " + thrown.get(),
				thrown.get() instanceof HardwareException);
		Assert.assertTrue("The interrupt status was not left set", interruptedAfter.get());

		Assert.assertFalse("The compiler process was still running when the interrupted wait ended",
				ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
	}

	/**
	 * Waits for the child process to record its id.
	 *
	 * @param pidFile the file the child writes its id to
	 * @return the id of the child process
	 */
	private static long awaitPid(Path pidFile) throws IOException, InterruptedException {
		long deadline = System.currentTimeMillis() + 20000;

		while (System.currentTimeMillis() < deadline) {
			if (Files.exists(pidFile)) {
				String text = Files.readString(pidFile).trim();
				if (!text.isEmpty()) return Long.parseLong(text);
			}

			Thread.sleep(20);
		}

		throw new AssertionError("The child process never recorded its id");
	}
}
