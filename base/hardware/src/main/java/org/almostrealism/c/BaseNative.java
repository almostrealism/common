/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.c;

import io.almostrealism.code.Precision;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.jni.NativeCompiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for operations that execute native C code via JNI.
 *
 * <p>{@link BaseNative} provides infrastructure for compiling C code, loading shared libraries,
 * and invoking JNI functions. It handles function naming conventions, library caching, and
 * integration with {@link NativeCompiler}.</p>
 *
 * <h2>JNI Function Naming</h2>
 *
 * <p>Native function names follow JNI conventions:</p>
 * <pre>{@code
 * // For class org.almostrealism.hardware.MyOperation
 * // JNI function name: Java_org_almostrealism_hardware_MyOperation_apply
 * }</pre>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Typical usage pattern:</p>
 * <pre>{@code
 * public class MyNativeOp extends BaseNative {
 *     public MyNativeOp(NativeCompiler compiler) {
 *         super(compiler);
 *         initNative();  // Compiles and loads native code
 *     }
 *
 *     @Override
 *     public String getFunctionDefinition() {
 *         return """
 *             JNIEXPORT void JNICALL Java_org_almostrealism_hardware_MyNativeOp_apply(
 *                 JNIEnv *env, jobject obj, jdoubleArray input) {
 *                 // Native C code here
 *             }
 *             """;
 *     }
 *
 *     public native void apply(double[] input);  // JNI method
 * }
 * }</pre>
 *
 * <h2>Library Caching</h2>
 *
 * <p>Once a class's native code is compiled and loaded, it's cached in {@link #libs} to prevent
 * redundant compilation. Each class's shared library is loaded once per JVM instance.</p>
 *
 * @see NativeCompiler
 */
public abstract class BaseNative {
	/** Enables verbose logging of native compilation and loading (currently disabled). */
	public static final boolean enableVerbose = false;

	/**
	 * JVM-wide monitor guarding the load path. Both {@link #libs} and {@link #loadedPrecisions} are
	 * static, so the load path must serialize on a single static monitor; see {@link #loadNative(Class, String)}.
	 */
	private static final Object loadLock = new Object();

	/** Cache of classes whose native libraries have been loaded to prevent redundant compilation. */
	private static final List<Class> libs = new ArrayList<>();

	/**
	 * Records the {@link Precision} under which each precision-dependent class
	 * (see {@link #isPrecisionDependent()}) was first compiled and bound in this JVM.
	 *
	 * <p>JNI binds a {@code native} method to the symbol {@code Java_<declaringClass>_apply}, which
	 * carries no precision, and {@link #libs} loads each class exactly once per JVM. So once a
	 * precision-dependent class has been bound, a later instance built at a different precision would
	 * reuse the already-loaded code — overrunning buffers sized for the other precision. This map lets
	 * {@link #loadNative(Class, String)} detect that cross-precision rebind and fail fast instead.</p>
	 */
	private static final Map<Class, Precision> loadedPrecisions = new HashMap<>();

	/** JNI function name following Java_package_class_method convention. */
	private String functionName;

	/** Compiler for translating C code to shared libraries and loading them via JNI. */
	private final NativeCompiler compiler;

	/**
	 * Creates a new native operation with the specified compiler.
	 *
	 * @param compiler The {@link NativeCompiler} for compiling and loading native code
	 */
	public BaseNative(NativeCompiler compiler) {
		this.compiler = compiler;
	}

	/**
	 * Returns the {@link NativeCompiler} used by this operation.
	 *
	 * @return the native compiler
	 */
	protected NativeCompiler getNativeCompiler() { return compiler; }

	/**
	 * Initializes the JNI function name following Java_package_class_method convention.
	 *
	 * <p>For example, for class {@code org.almostrealism.hardware.MyOperation}, generates:
	 * {@code Java_org_almostrealism_hardware_MyOperation_apply}</p>
	 */
	protected void initNativeFunctionName() {
		functionName = "Java_" +
				getClass().getName().replaceAll("\\.", "_") +
				"_apply";
	}

	/**
	 * Initializes and loads the native code for this operation.
	 *
	 * <p>This method:
	 * <ol>
	 * <li>Generates the JNI function name via {@link #initNativeFunctionName()}</li>
	 * <li>Compiles and loads the native library via {@link #loadNative(Class, String)}</li>
	 * </ol>
	 *
	 * @throws RuntimeException if the native library cannot be loaded
	 */
	protected void initNative() {
		initNativeFunctionName();

		try {
			loadNative(getClass(), getFunctionDefinition());
		} catch (UnsatisfiedLinkError e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Whether this operation's generated native code depends on the compiler's {@link Precision}.
	 *
	 * <p>Precision-dependent operations are guarded against cross-precision rebinding in
	 * {@link #loadNative(Class, String)}: because JNI binds by the per-class symbol
	 * {@code Java_<declaringClass>_apply} and a class is loaded once per JVM, a second instance of the
	 * same class built at a different precision would reuse the first precision's native code and corrupt
	 * memory. Operations whose generated code is identical for every precision (or whose precision
	 * differences are benign) return {@code false} — the default — and are not guarded.</p>
	 *
	 * @return {@code true} if cross-precision rebinding of this class must fail fast; {@code false} otherwise
	 */
	protected boolean isPrecisionDependent() { return false; }

	/**
	 * Compiles and loads the native code for the specified class.
	 *
	 * <p>The whole load path serializes on a single JVM-wide monitor ({@link #loadLock}) because the
	 * {@link #libs} cache and the {@link #loadedPrecisions} record it consults are both static. If the
	 * class's native library has already been loaded (cached in {@link #libs}), this method returns
	 * immediately without recompiling.</p>
	 *
	 * <p>When {@link #isPrecisionDependent()} is {@code true}, the precision this instance was built at
	 * is checked against the precision the class was first bound at: a mismatch throws a
	 * {@link HardwareException} rather than silently rebinding to the other precision's native code (which
	 * would overrun buffers and crash the JVM). A second load at the same precision remains an ordinary
	 * cache hit.</p>
	 *
	 * @param cls  The class whose native code to compile and load
	 * @param code The C function definition to compile
	 * @throws HardwareException if a precision-dependent class is loaded at a precision different from the
	 *                           one it was first bound at in this JVM
	 */
	protected void loadNative(Class cls, String code) {
		synchronized (loadLock) {
			if (isPrecisionDependent()) {
				Precision precision = getNativeCompiler().getPrecision();
				Precision bound = loadedPrecisions.get(cls);

				if (bound != null && bound != precision) {
					throw new HardwareException("Native operation " + cls.getName() +
							" was already bound at precision " + bound +
							" and cannot be rebound at precision " + precision +
							" in the same JVM");
				}
			}

			if (libs.contains(cls)) return;

			compiler.compileAndLoad(cls, code);
			libs.add(cls);

			if (isPrecisionDependent()) {
				loadedPrecisions.put(cls, getNativeCompiler().getPrecision());
			}
		}
	}

	/**
	 * Returns the JNI function name for this operation.
	 *
	 * @return the JNI function name
	 */
	protected String getFunctionName() { return functionName; }

	/**
	 * Returns the C function definition for this operation's JNI method.
	 *
	 * <p>Implementations should return a complete C function definition including
	 * JNI signature (JNIEXPORT, JNICALL, JNIEnv, jobject parameters) and function body.</p>
	 *
	 * @return The C code defining the JNI function
	 */
	public abstract String getFunctionDefinition();
}
