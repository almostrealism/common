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
		assertInterruptDestroys("echo $$ > %s; exec sleep 50",
				"The compiler process was still running when the interrupted wait ended");
	}

	/**
	 * Interrupting the wait for a compiler driver also destroys the processes the driver started:
	 * a gcc or clang driver runs its work in children (cc1, as) that a kill of the driver alone
	 * leaves running. The shell here stands in for the driver and the sleep for its child.
	 */
	@Test(timeout = 60000)
	public void interruptedCompilationDestroysDescendants() throws Exception {
		assertInterruptDestroys("sleep 50 & echo $! > %s; wait",
				"A child of the compiler process was still running when the interrupted wait ended");
	}

	/**
	 * Runs a shell script as a compiler invocation, interrupts the wait for it once the script has
	 * recorded a process id, and asserts that the runner throws, leaves the interrupt status set,
	 * and returns only after the recorded process has exited.
	 *
	 * @param script         shell script with one {@code %s} for the file it writes a process id to
	 * @param stillAliveText assertion message used when the recorded process is still running
	 */
	private static void assertInterruptDestroys(String script, String stillAliveText) throws Exception {
		Path dir = Files.createTempDirectory("native-compiler-interrupt");
		Path pidFile = dir.resolve("pid");
		RunnerCompiler compiler = new RunnerCompiler(dir.toString());

		AtomicReference<Throwable> thrown = new AtomicReference<>();
		AtomicBoolean interruptedAfter = new AtomicBoolean();

		Thread waiter = new Thread(() -> {
			try {
				compiler.run(List.of("/bin/sh", "-c", String.format(script, pidFile)));
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

		Assert.assertFalse(stillAliveText,
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
