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

package org.almostrealism.hardware.metal;

import io.almostrealism.code.Precision;
import org.almostrealism.c.BaseNative;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.jni.NativeCompiler;

/**
 * Runtime-compiled JNI operation returning the value an {@code MTLSharedEvent} has been signaled
 * to so far, without waiting: {@code -[MTLSharedEvent signaledValue]}. {@link MTLEvent} is its
 * only user (see {@link MTLEvent#isSignaled(long)}).
 *
 * <p>Compiled at runtime by {@link NativeCompiler}, like the NIO operations, so reading an event
 * needs no prebuilt native library beyond the one that creates it. The compiled library is
 * plain C and links nothing beyond the C library, so it sends the message through the
 * Objective-C runtime that {@code libMTL.dylib} has already loaded into the process, resolving
 * {@code objc_msgSend} and the {@code signaledValue} selector with {@code dlsym} when it is
 * loaded. An event pointer is the Objective-C object itself (metal-cpp's objects are), and a
 * 64-bit integer result is returned by plain {@code objc_msgSend} on every architecture, which is
 * how metal-cpp's own {@code MTL::SharedEvent::signaledValue()} sends it.</p>
 *
 * <p>The operation is compiled once per JVM, on first use ({@link #get()}), with the toolchain
 * the JNI backend compiles with. A host without that toolchain cannot compile it, which
 * {@link #isAvailable()} reports once, with the cause.</p>
 */
class MTLSignaledValue extends BaseNative {
	/** The operation, once compiled. */
	private static volatile MTLSignaledValue operation;

	/** Why the operation could not be compiled, once an attempt has failed. Guarded by the class. */
	private static RuntimeException failure;

	/**
	 * Compiles and loads the native operation.
	 *
	 * @param compiler the compiler used to build the operation
	 * @throws RuntimeException if the operation cannot be compiled or loaded
	 */
	private MTLSignaledValue(NativeCompiler compiler) {
		super(compiler);
		initNative();
	}

	@Override
	public String getFunctionDefinition() {
		return "#include <dlfcn.h>\n" +
				"#include <stdint.h>\n" +
				"static void* signaledValueSelector;\n" +
				"static uint64_t (*sendSignaledValue)(void*, void*);\n" +
				"__attribute__((constructor)) static void resolveSignaledValue(void) {\n" +
				"\tvoid* (*registerName)(const char*) = (void* (*)(const char*)) dlsym(RTLD_DEFAULT, \"sel_registerName\");\n" +
				"\tif (registerName != NULL) signaledValueSelector = registerName(\"signaledValue\");\n" +
				"\tsendSignaledValue = (uint64_t (*)(void*, void*)) dlsym(RTLD_DEFAULT, \"objc_msgSend\");\n" +
				"}\n" +
				"JNIEXPORT jlong JNICALL " + getFunctionName() +
				" (JNIEnv* env, jobject thisObject, jlong event) {\n" +
				"\tif (signaledValueSelector == NULL || sendSignaledValue == NULL) {\n" +
				"\t\t(*env)->ThrowNew(env, (*env)->FindClass(env, \"java/lang/IllegalStateException\"),\n" +
				"\t\t\t\t\"objc_msgSend could not be resolved: the Objective-C runtime was not loaded\");\n" +
				"\t\treturn 0;\n" +
				"\t}\n" +
				"\treturn (jlong) sendSignaledValue((void*) event, signaledValueSelector);\n" +
				"}\n";
	}

	/**
	 * Returns the value the given event has been signaled to so far, by an encoded signal the GPU
	 * has executed or by the host, without waiting for anything. The GPU updates the value when
	 * it executes the signal, so a host thread reading it observes a command buffer's signal
	 * ahead of the buffer's completion notification.
	 *
	 * @param event native {@code MTLSharedEvent} pointer, which must not have been released
	 * @return the event's current signaled value
	 * @throws IllegalStateException if the Objective-C runtime was not loaded with the operation
	 */
	public native long apply(long event);

	/**
	 * Returns the operation, compiling it if this is the first use in the JVM.
	 *
	 * @return the compiled operation
	 * @throws RuntimeException if the operation cannot be compiled, now or by an earlier attempt
	 */
	static MTLSignaledValue get() {
		MTLSignaledValue compiled = operation;
		return compiled == null ? compile() : compiled;
	}

	/**
	 * Returns whether the operation can be compiled on this host, compiling it if no attempt has
	 * been made yet. A failed attempt is logged, with its cause, when it happens; later calls
	 * report it without trying again.
	 *
	 * @return true if {@link #get()} returns the operation, false if it throws
	 */
	static synchronized boolean isAvailable() {
		if (operation == null && failure == null) {
			try {
				compile();
			} catch (RuntimeException e) {
				Hardware.console.warn("Metal event values cannot be read on this host, so host waits " +
						"wait for each command buffer's completion rather than polling for its signal", e);
			}
		}

		return operation != null;
	}

	/**
	 * Compiles the operation, unless it has been compiled already or an attempt has failed.
	 *
	 * @return the compiled operation
	 * @throws RuntimeException if the operation cannot be compiled, now or by an earlier attempt
	 */
	private static synchronized MTLSignaledValue compile() {
		if (operation != null) return operation;
		if (failure != null) {
			throw new IllegalStateException("Metal event values cannot be read on this host", failure);
		}

		try {
			operation = new MTLSignaledValue(NativeCompiler.factory(Precision.FP32, false).construct());
			return operation;
		} catch (RuntimeException e) {
			failure = e;
			throw e;
		}
	}
}
