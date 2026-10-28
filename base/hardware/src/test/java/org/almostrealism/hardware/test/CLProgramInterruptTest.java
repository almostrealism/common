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
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.hardware.CompilationInterruptedException;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.cl.CLComputeContext;
import org.almostrealism.hardware.cl.CLDataContext;
import org.almostrealism.hardware.cl.CLMemoryProvider;
import org.almostrealism.hardware.cl.CLOperatorMap;
import org.almostrealism.hardware.cl.CLProgram;
import org.almostrealism.io.Console;
import org.almostrealism.hardware.MemoryData;
import org.jocl.CL;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_device_id;
import org.jocl.cl_kernel;
import org.jocl.cl_program;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies that OpenCL compilation honours a thread interrupt only before a build
 * starts: {@link CLProgram#compile()} and {@link CLOperatorMap} refuse to start a build
 * on an interrupted thread, a build already in progress always completes against a live
 * {@link cl_program}, and a build that fails releases its program.
 *
 * <p>Like {@link CLDataContextLifecycleTest}, this test does not extend
 * {@code TestSuiteBase}, which lives in the engine layer above this module. It builds its
 * own {@link CLDataContext} rather than following the configured driver, so it runs on any
 * host that exposes an OpenCL device and skips, with {@link Assume}, on a host that does
 * not. Nothing here assumes a particular vendor's build timing.</p>
 */
public class CLProgramInterruptTest {
	/** Number of statements in the kernel that is built first, so its build takes noticeable time. */
	private static final int LARGE_KERNEL_STATEMENTS = 6000;

	/** Number of statements in each later kernel of a sequence. */
	private static final int SMALL_KERNEL_STATEMENTS = 500;

	/** Number of programs in each compiled sequence. */
	private static final int SEQUENCE_LENGTH = 6;

	/** How long the interrupter waits for the first build thread to appear before interrupting anyway. */
	private static final long BUILD_THREAD_WAIT_NANOS = 30_000_000_000L;

	/** The program created by the most recent {@link RecordingOperatorMap}. */
	private static final AtomicReference<CLProgram> created = new AtomicReference<>();

	/** The data context under test. */
	private CLDataContext dataContext;

	/** The OpenCL compute context programs are created in. */
	private CLComputeContext computeContext;

	/** Skips the test unless an OpenCL device is available, and otherwise creates the contexts. */
	@Before
	public void setUp() {
		dataContext = new CLDataContext("test-cl-interrupt", Precision.FP32,
				1024L * 1024, 1024, CLMemoryProvider.Location.DEVICE);
		dataContext.init();

		try {
			List<ComputeContext<MemoryData>> contexts = dataContext.getComputeContexts();
			ComputeContext<MemoryData> first = contexts.isEmpty() ? null : contexts.get(0);
			Assume.assumeTrue("No OpenCL compute context", first instanceof CLComputeContext);
			computeContext = (CLComputeContext) first;
		} catch (LinkageError | RuntimeException e) {
			Assume.assumeNoException("OpenCL is unavailable", e);
		}

		created.set(null);
	}

	/** Clears any interrupt left by a failed test and releases the data context. */
	@After
	public void tearDown() {
		Thread.interrupted();
		if (dataContext != null) dataContext.destroy();
	}

	/**
	 * Returns OpenCL C source for a kernel with the given name and the given number of
	 * arithmetic statements. Each statement depends on the previous one, so the compiler
	 * cannot discard the work, and the build time grows with the count.
	 */
	private static String kernelSource(String name, int statements) {
		StringBuilder src = new StringBuilder();
		src.append("__kernel void ").append(name).append("(__global float *a) {\n");
		src.append("\tint i = get_global_id(0);\n");
		src.append("\tfloat v0 = a[i];\n");

		for (int s = 1; s <= statements; s++) {
			src.append("\tfloat v").append(s).append(" = v").append(s - 1)
					.append(" * ").append(1.0f + s % 7 / 10.0f).append("f + sin(v")
					.append(s - 1).append(" + ").append(s % 13).append(".0f);\n");
		}

		src.append("\ta[i] = v").append(statements).append(";\n");
		src.append("}\n");
		return src.toString();
	}

	/** Creates a program for a kernel of the given name, with metadata of the same name. */
	private CLProgram program(String name, int statements) {
		return CLProgram.create(computeContext,
				new OperationMetadata(name, "Interrupt test kernel"),
				kernelSource(name, statements));
	}

	/** Returns the first device the program is associated with. */
	private static cl_device_id device(cl_program program) {
		int[] count = new int[1];
		CL.clGetProgramInfo(program, CL.CL_PROGRAM_NUM_DEVICES, Sizeof.cl_uint,
				Pointer.to(count), null);

		cl_device_id[] devices = new cl_device_id[count[0]];
		CL.clGetProgramInfo(program, CL.CL_PROGRAM_DEVICES, (long) Sizeof.cl_device_id * count[0],
				Pointer.to(devices), null);
		return devices[0];
	}

	/** Returns the {@code CL_PROGRAM_BUILD_STATUS} of the program on its first device. */
	private static int buildStatus(cl_program program) {
		int[] status = new int[1];
		CL.clGetProgramBuildInfo(program, device(program), CL.CL_PROGRAM_BUILD_STATUS,
				Sizeof.cl_int, Pointer.to(status), null);
		return status[0];
	}

	/** Asserts that a kernel with the given name can be created from the program. */
	private static void assertKernelCreatable(cl_program program, String name) {
		cl_kernel kernel = CL.clCreateKernel(program, name, null);
		Assert.assertNotNull("No kernel could be created from " + name, kernel);
		CL.clReleaseKernel(kernel);
	}

	/** Returns whether a live thread whose name starts with the given prefix exists. */
	private static boolean threadAlive(String prefix) {
		return Thread.getAllStackTraces().keySet().stream()
				.anyMatch(t -> t.isAlive() && t.getName().startsWith(prefix));
	}

	/** Creates the programs of a sequence, the first of them large and the rest moderate. */
	private List<CLProgram> sequence(String prefix) {
		List<CLProgram> programs = new ArrayList<>();

		for (int i = 0; i < SEQUENCE_LENGTH; i++) {
			programs.add(program(prefix + i, i == 0 ? LARGE_KERNEL_STATEMENTS : SMALL_KERNEL_STATEMENTS + i));
		}

		return programs;
	}

	/** Destroys every program in the list. */
	private static void destroyAll(List<CLProgram> programs) {
		for (CLProgram p : programs) {
			if (p.getProgram() != null) p.destroy();
		}
	}

	/**
	 * Compiling on an interrupted thread throws {@link CompilationInterruptedException},
	 * leaves the interrupt status set, and does not start a build.
	 */
	@Test(timeout = 60000)
	public void compileOnInterruptedThreadDoesNotBuild() {
		CLProgram program = program("interruptedCompile", 4);

		try {
			Thread.currentThread().interrupt();

			try {
				program.compile();
				Assert.fail("compile() started a build on an interrupted thread");
			} catch (CompilationInterruptedException expected) {
				Assert.assertTrue("interrupt status was cleared", Thread.currentThread().isInterrupted());
			} finally {
				Thread.interrupted();
			}

			Assert.assertEquals("A build was started", CL.CL_BUILD_NONE, buildStatus(program.getProgram()));
		} finally {
			program.destroy();
		}
	}

	/**
	 * An interrupt that arrives while a sequence of builds is running lets the build in
	 * progress complete against a live program, restores the interrupt, and stops the
	 * sequence at the next build.
	 */
	@Test(timeout = 300000)
	public void interruptDuringBuildCompletesBuildAndStopsSequence() throws InterruptedException {
		List<CLProgram> programs = sequence("interruptSequence");
		// Any build of the sequence counts, so a fast first build the interrupter misses
		// does not leave it waiting while the rest of the sequence runs
		String buildThread = "CLProgram build interruptSequence";

		int[] cancelledAt = { -1 };
		boolean[] flagAfterCompile = new boolean[SEQUENCE_LENGTH];
		boolean[] flagAfterCancel = new boolean[1];
		Throwable[] unexpected = new Throwable[1];

		Thread worker = new Thread(() -> {
			try {
				for (int i = 0; i < programs.size(); i++) {
					try {
						programs.get(i).compile();
						flagAfterCompile[i] = Thread.currentThread().isInterrupted();
					} catch (CompilationInterruptedException e) {
						cancelledAt[0] = i;
						flagAfterCancel[0] = Thread.currentThread().isInterrupted();
						return;
					}
				}
			} catch (Throwable t) {
				unexpected[0] = t;
			}
		}, "CLProgramInterruptTest-worker");

		boolean[] observedBuild = new boolean[1];
		boolean[] buildAliveAfterInterrupt = new boolean[1];

		Thread interrupter = new Thread(() -> {
			long deadline = System.nanoTime() + BUILD_THREAD_WAIT_NANOS;

			while (!threadAlive(buildThread) && worker.isAlive() && System.nanoTime() < deadline) {
				Thread.onSpinWait();
			}

			observedBuild[0] = threadAlive(buildThread);
			worker.interrupt();
			buildAliveAfterInterrupt[0] = threadAlive(buildThread);
		}, "CLProgramInterruptTest-interrupter");

		try {
			worker.start();
			interrupter.start();
			interrupter.join();
			worker.join();

			if (unexpected[0] != null) {
				throw new AssertionError("The worker failed", unexpected[0]);
			}

			Assert.assertTrue("The interrupter never saw a build start", observedBuild[0]);
			Assert.assertTrue("Every build in the sequence ran despite the interrupt", cancelledAt[0] >= 0);
			Assert.assertTrue("The interrupt status was cleared by the cancellation", flagAfterCancel[0]);

			int k = cancelledAt[0];
			Assert.assertTrue("The build in progress was abandoned instead of completed", k >= 1);

			if (buildAliveAfterInterrupt[0]) {
				// The interrupt certainly arrived during build k - 1, so that compile()
				// must have returned normally with the interrupt restored
				Assert.assertTrue("The compile() in progress returned without restoring the interrupt",
						flagAfterCompile[k - 1]);
			}

			for (int i = 0; i < k; i++) {
				cl_program p = programs.get(i).getProgram();
				Assert.assertEquals("Build " + i + " did not complete", CL.CL_BUILD_SUCCESS, buildStatus(p));
				assertKernelCreatable(p, programs.get(i).getMetadata().getDisplayName());
			}

			for (int i = k; i < programs.size(); i++) {
				Assert.assertEquals("Build " + i + " was started after the interrupt",
						CL.CL_BUILD_NONE, buildStatus(programs.get(i).getProgram()));
			}

			// Records which case the run covered: only when the build thread was still alive
			// after interrupt() did the interrupt certainly arrive during a build
			Console.root().println("interruptDuringBuild inProgressCovered=" +
					buildAliveAfterInterrupt[0] + " cancelledAt=" + k);
		} finally {
			destroyAll(programs);
		}
	}

	/** The same sequence of builds on an uninterrupted thread builds every program. */
	@Test(timeout = 300000)
	public void uninterruptedSequenceCompilesAll() {
		List<CLProgram> programs = sequence("uninterruptedSequence");

		try {
			for (CLProgram p : programs) {
				p.compile();
			}

			for (CLProgram p : programs) {
				Assert.assertEquals(CL.CL_BUILD_SUCCESS, buildStatus(p.getProgram()));
				assertKernelCreatable(p.getProgram(), p.getMetadata().getDisplayName());
			}
		} finally {
			destroyAll(programs);
		}
	}

	/**
	 * A {@link CLOperatorMap} constructed on an interrupted thread does not create or build
	 * a program, and reports the cancellation rather than a compile failure.
	 */
	@Test(timeout = 60000)
	public void operatorMapOnInterruptedThreadCreatesNoProgram() {
		Thread.currentThread().interrupt();

		try {
			new RecordingOperatorMap(computeContext,
					new OperationMetadata("interruptedMap", "Interrupt test kernel"),
					kernelSource("interruptedMap", 4));
			Assert.fail("CLOperatorMap compiled on an interrupted thread");
		} catch (CompilationInterruptedException expected) {
			Assert.assertTrue("interrupt status was cleared", Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}

		Assert.assertNull("A program was created for a cancelled compile", created.get());
	}

	/** A {@link CLOperatorMap} whose build fails releases the program it created for the build. */
	@Test(timeout = 60000)
	public void failedCompileReleasesProgram() {
		try {
			new RecordingOperatorMap(computeContext,
					new OperationMetadata("invalidMap", "Interrupt test kernel"),
					"__kernel void invalidMap(__global float *a) { this is not OpenCL C; }\n");
			Assert.fail("An invalid program compiled");
		} catch (CompilationInterruptedException e) {
			throw new AssertionError("The failure was reported as a cancellation", e);
		} catch (HardwareException expected) {
			// The genuine compile failure is reported
		}

		CLProgram program = created.get();
		Assert.assertNotNull("No program was created for the failed build", program);
		Assert.assertNull("The program of the failed build was not destroyed", program.getProgram());
	}

	/**
	 * A {@link CLOperatorMap} that records the program it creates, so the release of that
	 * program after a failed build can be observed without querying a released
	 * {@link cl_program}.
	 */
	private static class RecordingOperatorMap extends CLOperatorMap {
		/**
		 * Creates the map, compiling the given source.
		 *
		 * @param ctx      the compute context
		 * @param metadata metadata naming the program
		 * @param src      the OpenCL C source
		 */
		RecordingOperatorMap(CLComputeContext ctx, OperationMetadata metadata, String src) {
			super(ctx, metadata, src, null);
		}

		/** Creates the program and records it. */
		@Override
		protected CLProgram createProgram(OperationMetadata metadata, String src) {
			CLProgram program = super.createProgram(metadata, src);
			created.set(program);
			return program;
		}
	}
}
