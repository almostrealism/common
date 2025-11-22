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

import org.almostrealism.hardware.jni.NativeCompiler;

import java.util.ArrayList;
import java.util.List;

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

	/** Cache of classes whose native libraries have been loaded to prevent redundant compilation. */
	private static final List<Class> libs = new ArrayList<>();

	/** JNI function name following Java_package_class_method convention. */
	private String functionName;

	/** Compiler for translating C code to shared libraries and loading them via JNI. */
	private NativeCompiler compiler;

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
	 * Compiles and loads the native code for the specified class.
	 *
	 * <p>This method is synchronized to prevent concurrent compilation of the same class.
	 * If the class's native library has already been loaded (cached in {@link #libs}),
	 * this method returns immediately without recompiling.</p>
	 *
	 * @param cls  The class whose native code to compile and load
	 * @param code The C function definition to compile
	 */
	protected synchronized void loadNative(Class cls, String code) {
		if (libs.contains(cls)) return;

		compiler.compileAndLoad(cls, code);
		libs.add(cls);
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
