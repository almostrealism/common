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
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * Verifies that operation targets are reserved uniquely across every
 * {@link NativeCompiler} in the JVM.
 *
 * <p>A JVM holds several compilers — one per data context, plus one for any
 * {@code NativeMemoryProvider} constructed without one — and the counter behind
 * the reservation is shared by all of them. The reserved name becomes both the
 * generated source path and the compiled library path, so two compilers handing
 * out the same name puts two operations in one file and the native toolchain
 * fails to build it.</p>
 */
public class NativeCompilerTargetReservationTest extends TestSuiteBase {

	/** Number of independent compilers reserving concurrently. */
	private static final int COMPILERS = 4;

	/** Number of targets each compiler reserves. */
	private static final int PER_COMPILER = 40;

	/**
	 * Targets reserved concurrently through separate compilers are all distinct.
	 */
	@Test(timeout = 120000)
	public void concurrentReservationsAreUnique() throws InterruptedException {
		List<Class<?>> reserved = new CopyOnWriteArrayList<>();
		List<Throwable> failures = new CopyOnWriteArrayList<>();

		CountDownLatch ready = new CountDownLatch(COMPILERS);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(COMPILERS);

		for (int i = 0; i < COMPILERS; i++) {
			new Thread(() -> {
				try {
					NativeCompiler compiler =
							NativeCompiler.factory(Precision.FP64, false).construct();
					ready.countDown();
					start.await();

					for (int j = 0; j < PER_COMPILER; j++) {
						reserved.add(compiler.reserveLibraryTarget().getClass());
					}
				} catch (Throwable t) {
					failures.add(t);
					ready.countDown();
				} finally {
					done.countDown();
				}
			}).start();
		}

		ready.await();
		start.countDown();
		done.await();

		Assert.assertTrue("Reservation threads failed: " + failures, failures.isEmpty());

		Set<Class<?>> distinct = new HashSet<>(reserved);
		Assert.assertEquals(COMPILERS * PER_COMPILER, reserved.size());
		Assert.assertEquals("A target was reserved more than once",
				reserved.size(), distinct.size());
	}

	/**
	 * The reported instruction set total accounts for every reserved target.
	 */
	@Test(timeout = 120000)
	public void totalCoversEveryReservation() {
		NativeCompiler compiler = NativeCompiler.factory(Precision.FP64, false).construct();

		long before = NativeCompiler.getTotalInstructionSets();
		Set<Class<?>> reserved = new HashSet<>();

		for (int i = 0; i < PER_COMPILER; i++) {
			reserved.add(compiler.reserveLibraryTarget().getClass());
		}

		Assert.assertEquals(PER_COMPILER, reserved.size());
		Assert.assertEquals(PER_COMPILER,
				NativeCompiler.getTotalInstructionSets() - before);
	}
}
