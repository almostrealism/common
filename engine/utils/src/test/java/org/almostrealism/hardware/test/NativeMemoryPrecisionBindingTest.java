/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware.test;

import io.almostrealism.code.Precision;
import org.almostrealism.c.BaseNative;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.nio.Free;
import org.almostrealism.nio.Malloc;
import org.almostrealism.nio.NativeArrayCopy;
import org.almostrealism.nio.NativeMemoryProvider;
import org.almostrealism.nio.NativeRead;
import org.almostrealism.nio.NativeWrite;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tests that precision-dependent native operations cannot share one precision's native code across
 * precisions in a single JVM, and that operations which are not precision-dependent stay unguarded.
 *
 * <p>{@link NativeRead}/{@link NativeWrite} generate different C per precision but bind under the
 * per-class JNI symbol {@code Java_<class>_apply}, which carries no precision, and a class is loaded
 * once per JVM. Before the fail-fast guard, a second calloc provider built at the other precision
 * reused the first precision's already-loaded native code and copied the wrong element width,
 * overrunning its buffers and crashing the JVM with a SIGSEGV. The guard in
 * {@link org.almostrealism.c.BaseNative#loadNative(Class, String)} converts that cross-precision
 * rebind into a catchable {@link HardwareException}. {@link Malloc}/{@link Free}, whose generated
 * code is precision-independent, are left unguarded.</p>
 *
 * <p>Because the guard is JVM-wide and records whichever precision bound first, these tests drive the
 * two precisions relative to the running hardware's precision — binding it first with a correct
 * round-trip, then proving the <em>other</em> precision fails fast. Every other test in the JVM uses
 * the hardware precision, so binding it first leaves the suite's bindings intact regardless of
 * Surefire fork reuse or execution order. The direct-guard and concurrency tests use dedicated
 * {@link NativePrecisionProbe} classes so they never bind the real
 * {@link NativeRead}/{@link NativeWrite}/{@link Malloc} classes at a non-hardware precision.</p>
 */
public class NativeMemoryPrecisionBindingTest extends TestSuiteBase {
	/** A length spanning two full FP32 conversion chunks and part of a third. */
	private static final int LENGTH = 2 * NativeArrayCopy.CONVERSION_CHUNK + 37;

	/** Maximum bytes each provider may allocate. */
	private static final long MEMORY_MAX = 1024L * 1024;

	/** Number of threads contending on the JVM-wide load lock in the concurrency test. */
	private static final int CONCURRENT_THREADS = 8;

	/**
	 * A calloc provider at the hardware precision round-trips a whole block unchanged, and a second
	 * calloc provider at the other precision then fails fast on a write with a {@link HardwareException}
	 * naming {@link NativeWrite} and both precisions, rather than rebinding to the first precision's
	 * native code and crashing the JVM.
	 */
	@Test(timeout = 120000)
	public void crossPrecisionWriteFailsFast() {
		Precision hardware = Hardware.getLocalHardware().getPrecision();
		Precision other = otherPrecision(hardware);

		bindHardwareWithRoundTrip(hardware);

		NativeMemoryProvider mismatched = calloc(other);
		RAM otherMem = mismatched.allocate(LENGTH);

		try {
			mismatched.setMem(otherMem, 0, sequence(LENGTH), 0, LENGTH);
			Assert.fail("A calloc write at " + other + " bound after " + hardware + " was not rejected");
		} catch (HardwareException expected) {
			assertCrossPrecisionMessage(expected, NativeWrite.class, hardware, other);
		} finally {
			mismatched.deallocate(LENGTH, otherMem);
		}
	}

	/**
	 * The read direction is guarded too: after the hardware precision binds, a calloc provider at the
	 * other precision fails fast on a read with a {@link HardwareException} naming {@link NativeRead} and
	 * both precisions. {@link NativeRead} and {@link NativeWrite} are distinct JNI symbols, so the write
	 * guard proven above does not imply the read guard.
	 */
	@Test(timeout = 120000)
	public void crossPrecisionReadFailsFast() {
		Precision hardware = Hardware.getLocalHardware().getPrecision();
		Precision other = otherPrecision(hardware);

		bindHardwareWithRoundTrip(hardware);

		NativeMemoryProvider mismatched = calloc(other);
		RAM otherMem = mismatched.allocate(LENGTH);

		try {
			mismatched.toArray(otherMem, 0, LENGTH);
			Assert.fail("A calloc read at " + other + " bound after " + hardware + " was not rejected");
		} catch (HardwareException expected) {
			assertCrossPrecisionMessage(expected, NativeRead.class, hardware, other);
		} finally {
			mismatched.deallocate(LENGTH, otherMem);
		}
	}

	/**
	 * A rejected cross-precision attempt leaves the first precision's binding usable: after a calloc
	 * provider at the other precision is rejected, a fresh round-trip through the original
	 * hardware-precision provider still reproduces its data exactly, proving the failed attempt neither
	 * rebound the native code nor corrupted the recorded binding.
	 */
	@Test(timeout = 120000)
	public void rejectedRebindLeavesFirstBindingUsable() {
		Precision hardware = Hardware.getLocalHardware().getPrecision();
		Precision other = otherPrecision(hardware);

		NativeMemoryProvider bound = bindHardwareWithRoundTrip(hardware);

		NativeMemoryProvider mismatched = calloc(other);
		RAM otherMem = mismatched.allocate(LENGTH);

		try {
			mismatched.setMem(otherMem, 0, sequence(LENGTH), 0, LENGTH);
			Assert.fail("A calloc write at " + other + " bound after " + hardware + " was not rejected");
		} catch (HardwareException expected) {
			assertCrossPrecisionMessage(expected, NativeWrite.class, hardware, other);
		} finally {
			mismatched.deallocate(LENGTH, otherMem);
		}

		assertRoundTrip(bound);
	}

	/**
	 * {@link Malloc} and {@link Free} are not precision-dependent, so building them at a precision
	 * different from the one they first bound at raises no {@link HardwareException}, and a block
	 * allocated and freed through the second-precision instances still works. Their generated code is
	 * precision-independent — {@link Malloc} is a byte-sized {@code calloc} and {@link Free}'s pointer
	 * cast is benign — so reusing one precision's native code for the other is safe and must not be
	 * guarded.
	 */
	@Test(timeout = 120000)
	public void mallocAndFreeStayUnguarded() {
		Precision hardware = Hardware.getLocalHardware().getPrecision();
		Precision other = otherPrecision(hardware);

		new Malloc(compiler(hardware));
		new Free(compiler(hardware));

		try {
			Malloc malloc = new Malloc(compiler(other));
			Free free = new Free(compiler(other));

			long pointer = malloc.apply(64);
			Assert.assertNotEquals("calloc returned a null pointer", 0L, pointer);
			free.apply(pointer);
		} catch (HardwareException e) {
			throw new AssertionError("Malloc/Free must not be guarded against cross-precision use", e);
		}
	}

	/**
	 * Exercises the {@link BaseNative} guard directly through dedicated probe classes rather than through
	 * {@link NativeMemoryProvider}. A probe whose {@link BaseNative#isPrecisionDependent()} returns
	 * {@code true} binds the hardware precision on first load and then fails fast when rebound at the
	 * other precision; a probe that leaves the default {@code false} is never guarded, binding at both
	 * precisions without complaint.
	 */
	@Test(timeout = 120000)
	public void baseNativeGuardRejectsCrossPrecisionRebind() {
		Precision hardware = Hardware.getLocalHardware().getPrecision();
		Precision other = otherPrecision(hardware);

		new GuardedPrecisionProbe(compiler(hardware));

		try {
			new GuardedPrecisionProbe(compiler(other));
			Assert.fail("A precision-dependent probe rebound at " + other + " was not rejected");
		} catch (HardwareException expected) {
			assertCrossPrecisionMessage(expected, GuardedPrecisionProbe.class, hardware, other);
		}

		new UnguardedPrecisionProbe(compiler(hardware));
		new UnguardedPrecisionProbe(compiler(other));
	}

	/**
	 * Many threads binding the same precision-dependent probe at the same precision at once all complete
	 * without error — the JVM-wide load lock serializes the single compile-and-load and turns the rest
	 * into cache hits — and the guard remains intact afterward, so rebinding the probe at the other
	 * precision still fails fast. The test is bounded ({@link #CONCURRENT_THREADS} threads, all at the
	 * hardware precision) and deterministic: whatever order the threads acquire the lock in, the recorded
	 * binding is the hardware precision and no same-precision load can fail.
	 */
	@Test(timeout = 120000)
	public void concurrentLoadSerializesAndKeepsGuardIntact() {
		Precision hardware = Hardware.getLocalHardware().getPrecision();
		Precision other = otherPrecision(hardware);

		NativeCompiler shared = compiler(hardware);
		ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_THREADS);
		CountDownLatch ready = new CountDownLatch(CONCURRENT_THREADS);
		CountDownLatch start = new CountDownLatch(1);
		List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

		try {
			List<Future<?>> futures = new ArrayList<>();

			for (int i = 0; i < CONCURRENT_THREADS; i++) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					try {
						start.await();
						new ConcurrentPrecisionProbe(shared);
					} catch (Throwable t) {
						failures.add(t);
					}
				}));
			}

			Assert.assertTrue("Threads did not become ready", ready.await(30, TimeUnit.SECONDS));
			start.countDown();

			for (Future<?> future : futures) {
				future.get(60, TimeUnit.SECONDS);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while awaiting concurrent loads", e);
		} catch (ExecutionException | TimeoutException e) {
			throw new AssertionError("A concurrent load did not complete", e);
		} finally {
			pool.shutdownNow();
		}

		Assert.assertTrue("Concurrent same-precision loads must not fail: " + failures, failures.isEmpty());

		try {
			new ConcurrentPrecisionProbe(compiler(other));
			Assert.fail("A probe rebound at " + other + " after concurrent loads was not rejected");
		} catch (HardwareException expected) {
			assertCrossPrecisionMessage(expected, ConcurrentPrecisionProbe.class, hardware, other);
		}
	}

	/**
	 * Binds the hardware precision by writing a block through a calloc provider and reading it back
	 * unchanged, then returns the provider for further use. The round-trip binds both
	 * {@link NativeWrite} and {@link NativeRead} at the hardware precision.
	 *
	 * @param hardware the hardware precision to bind
	 * @return the provider, with its round-trip memory already released
	 */
	private NativeMemoryProvider bindHardwareWithRoundTrip(Precision hardware) {
		requireRoundTripPrecision(hardware);

		NativeMemoryProvider bound = calloc(hardware);
		assertRoundTrip(bound);
		return bound;
	}

	/**
	 * Fails fast if the given precision is not one {@link NativeArrayCopy} can round-trip. Its native
	 * code copies {@code double*} for {@link Precision#FP64} and {@code float*} for every other
	 * precision, so an {@link Precision#FP16} element — two bytes, not four — would have a four-byte
	 * {@code float} written into and read out of it, overrunning the calloc allocation and corrupting
	 * memory before the cross-precision guard this test exercises is ever reached. The round-trip tests
	 * therefore require an {@link Precision#FP32} or {@link Precision#FP64} hardware precision rather
	 * than silently treating {@link Precision#FP16} as {@link Precision#FP32}. (The direct-guard,
	 * concurrency, and {@link Malloc}/{@link Free} tests never copy precision-width memory, so they
	 * remain valid at any hardware precision.)
	 *
	 * @param precision the hardware precision about to be round-tripped
	 */
	private static void requireRoundTripPrecision(Precision precision) {
		if (precision != Precision.FP32 && precision != Precision.FP64) {
			Assert.fail(NativeArrayCopy.class.getName() + " round-trips only FP32 and FP64, but the hardware" +
					" precision is " + precision + "; its float-based native path would overrun a " +
					precision.bytes() + "-byte-per-element allocation. Run this test at an FP32 or FP64" +
					" hardware precision.");
		}
	}

	/**
	 * Allocates a block in the provider, writes a known sequence, reads it back and asserts it is
	 * unchanged, then releases the block.
	 *
	 * @param provider the provider to round-trip through
	 */
	private void assertRoundTrip(NativeMemoryProvider provider) {
		RAM mem = provider.allocate(LENGTH);

		try {
			double[] values = sequence(LENGTH);
			provider.setMem(mem, 0, values, 0, LENGTH);
			Assert.assertArrayEquals(values, provider.toArray(mem, 0, LENGTH), 0.0);
		} finally {
			provider.deallocate(LENGTH, mem);
		}
	}

	/**
	 * Asserts that a cross-precision rejection message names the rejected operation and both precisions.
	 *
	 * @param e         the exception thrown by the guard
	 * @param operation the native operation class expected in the message
	 * @param first     one of the two precisions expected in the message
	 * @param second    the other precision expected in the message
	 */
	private static void assertCrossPrecisionMessage(HardwareException e, Class<?> operation,
													Precision first, Precision second) {
		String message = e.getMessage();
		Assert.assertTrue("Message should name " + operation.getSimpleName() + ": " + message,
				message.contains(operation.getName()));
		Assert.assertTrue("Message should name both precisions: " + message,
				message.contains(first.toString()) && message.contains(second.toString()));
	}

	/**
	 * Returns the precision that is not the given one.
	 *
	 * @param precision a precision
	 * @return {@link Precision#FP32} if given {@link Precision#FP64}, otherwise {@link Precision#FP64}
	 */
	private static Precision otherPrecision(Precision precision) {
		return precision == Precision.FP64 ? Precision.FP32 : Precision.FP64;
	}

	/**
	 * Returns a native compiler for the given precision, matching how
	 * {@link NativeMemoryProvider} resolves its own compiler.
	 *
	 * @param precision the numeric precision for the compiler
	 * @return the compiler
	 */
	private static NativeCompiler compiler(Precision precision) {
		return NativeCompiler.factory(precision, false).construct();
	}

	/**
	 * Returns a provider that allocates with JNI calloc rather than direct buffers, at the given precision.
	 *
	 * @param precision the numeric precision for the provider's memory
	 * @return the provider
	 */
	private static NativeMemoryProvider calloc(Precision precision) {
		return new NativeMemoryProvider(precision, MEMORY_MAX, false, null, false);
	}

	/**
	 * Returns {@code i + 0.5} for each index; every value is exactly representable in FP32.
	 *
	 * @param length the number of values
	 * @return the values
	 */
	private static double[] sequence(int length) {
		double[] values = new double[length];
		for (int i = 0; i < length; i++) values[i] = i + 0.5;
		return values;
	}

}

/**
 * A minimal {@link BaseNative} operation that compiles a no-op JNI function, used by
 * {@link NativeMemoryPrecisionBindingTest} to exercise the load-path guard directly. Subclasses choose
 * whether the operation is precision-dependent.
 *
 * <p>Declared as a top-level class rather than nested in the test so its binary name carries no
 * {@code $}: {@link org.almostrealism.hardware.jni.NativeCompiler} derives the library filename from the
 * class name through a regex replacement, in which a {@code $} would be read as a capture-group
 * reference.</p>
 */
abstract class NativePrecisionProbe extends BaseNative {
	/**
	 * Compiles and loads the probe's native code at the compiler's precision.
	 *
	 * @param compiler the compiler whose precision this probe binds
	 */
	protected NativePrecisionProbe(NativeCompiler compiler) {
		super(compiler);
		initNative();
	}

	@Override
	public String getFunctionDefinition() {
		return "JNIEXPORT void JNICALL " + getFunctionName() + " (JNIEnv* env, jobject thisObject) {\n}\n";
	}
}

/**
 * A precision-dependent probe: a second instance at a different precision must fail fast. It is distinct
 * from {@link ConcurrentPrecisionProbe} so the concurrency test binds a class the direct-guard test has
 * not already loaded.
 */
class GuardedPrecisionProbe extends NativePrecisionProbe {
	/**
	 * Binds this probe at the compiler's precision.
	 *
	 * @param compiler the compiler whose precision this probe binds
	 */
	protected GuardedPrecisionProbe(NativeCompiler compiler) {
		super(compiler);
	}

	@Override
	protected boolean isPrecisionDependent() { return true; }
}

/**
 * A precision-dependent probe bound only by the concurrency test, so its concurrent first load is timed
 * on a class no other test has bound.
 */
class ConcurrentPrecisionProbe extends NativePrecisionProbe {
	/**
	 * Binds this probe at the compiler's precision.
	 *
	 * @param compiler the compiler whose precision this probe binds
	 */
	protected ConcurrentPrecisionProbe(NativeCompiler compiler) {
		super(compiler);
	}

	@Override
	protected boolean isPrecisionDependent() { return true; }
}

/**
 * A probe that keeps the default {@link BaseNative#isPrecisionDependent()} of {@code false}, so it is
 * never guarded and may bind at more than one precision.
 */
class UnguardedPrecisionProbe extends NativePrecisionProbe {
	/**
	 * Binds this probe at the compiler's precision.
	 *
	 * @param compiler the compiler whose precision this probe binds
	 */
	protected UnguardedPrecisionProbe(NativeCompiler compiler) {
		super(compiler);
	}
}
