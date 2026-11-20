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
 * Runtime-compiled JNI operation that registers a Java {@link Runnable} as a completed handler of
 * an {@code MTLCommandBuffer}: {@code -[MTLCommandBuffer addCompletedHandler:]}.
 * {@link MTLCommandBuffer#addCompletedHandler(Runnable)} is its only user.
 *
 * <p>Compiled at runtime by {@link NativeCompiler}, like {@link MTLSignaledValue}, so registering
 * a handler needs no prebuilt native library beyond the one that creates the buffer. The compiled
 * library is plain C with clang blocks (on by default for Apple targets), and links nothing beyond
 * the C library: it sends the message through the Objective-C runtime that {@code libMTL.dylib}
 * has already loaded into the process, resolving {@code objc_msgSend} and the
 * {@code addCompletedHandler:} selector with {@code dlsym} when it is loaded. A command buffer
 * pointer is the Objective-C object itself (metal-cpp's objects are), and Metal copies the block
 * it is given, so the stack block the operation builds outlives the call.</p>
 *
 * <p>Metal runs the handler on one of its own threads, which the block attaches to the JVM for
 * the call. The block holds a global reference to the handler only until it has run, and does not
 * use the buffer: Metal keeps a committed buffer until its completed handlers have run,
 * independently of the retain {@link MTL#commandBuffer(long)} took for the caller.</p>
 *
 * <p>The operation is compiled once per JVM, on first use, with the toolchain the JNI backend
 * compiles with. A host without that toolchain cannot compile it, which {@link #isAvailable()}
 * reports once, with the cause.</p>
 */
class MTLCompletedHandler extends BaseNative {
	/** The operation, once compiled. */
	private static volatile MTLCompletedHandler operation;

	/** Why the operation could not be compiled, once an attempt has failed. Guarded by the class. */
	private static RuntimeException failure;

	/**
	 * Compiles and loads the native operation.
	 *
	 * @param compiler the compiler used to build the operation
	 * @throws RuntimeException if the operation cannot be compiled or loaded
	 */
	private MTLCompletedHandler(NativeCompiler compiler) {
		super(compiler);
		initNative();
	}

	@Override
	public String getFunctionDefinition() {
		return "#include <dlfcn.h>\n" +
				"#include <stddef.h>\n" +
				"static void* addCompletedHandlerSelector;\n" +
				"static void (*sendAddCompletedHandler)(void*, void*, void*);\n" +
				"__attribute__((constructor)) static void resolveAddCompletedHandler(void) {\n" +
				"\tvoid* (*registerName)(const char*) = (void* (*)(const char*)) dlsym(RTLD_DEFAULT, \"sel_registerName\");\n" +
				"\tif (registerName != NULL) addCompletedHandlerSelector = registerName(\"addCompletedHandler:\");\n" +
				"\tsendAddCompletedHandler = (void (*)(void*, void*, void*)) dlsym(RTLD_DEFAULT, \"objc_msgSend\");\n" +
				"}\n" +
				"JNIEXPORT void JNICALL " + getFunctionName() +
				" (JNIEnv* env, jobject thisObject, jlong buffer, jobject handler) {\n" +
				"\tif (addCompletedHandlerSelector == NULL || sendAddCompletedHandler == NULL) {\n" +
				"\t\t(*env)->ThrowNew(env, (*env)->FindClass(env, \"java/lang/IllegalStateException\"),\n" +
				"\t\t\t\t\"objc_msgSend could not be resolved: the Objective-C runtime was not loaded\");\n" +
				"\t\treturn;\n" +
				"\t}\n" +
				"\tJavaVM* vm = NULL;\n" +
				"\tif ((*env)->GetJavaVM(env, &vm) != JNI_OK) {\n" +
				"\t\t(*env)->ThrowNew(env, (*env)->FindClass(env, \"java/lang/IllegalStateException\"),\n" +
				"\t\t\t\t\"The Java VM could not be obtained\");\n" +
				"\t\treturn;\n" +
				"\t}\n" +
				"\tjclass runnable = (*env)->FindClass(env, \"java/lang/Runnable\");\n" +
				"\tif (runnable == NULL) return;\n" +
				"\tjmethodID run = (*env)->GetMethodID(env, runnable, \"run\", \"()V\");\n" +
				"\t(*env)->DeleteLocalRef(env, runnable);\n" +
				"\tif (run == NULL) return;\n" +
				"\tjobject callback = (*env)->NewGlobalRef(env, handler);\n" +
				"\tif (callback == NULL) {\n" +
				"\t\t(*env)->ThrowNew(env, (*env)->FindClass(env, \"java/lang/OutOfMemoryError\"),\n" +
				"\t\t\t\t\"No global reference could be created for the handler\");\n" +
				"\t\treturn;\n" +
				"\t}\n" +
				"\tvoid (^completed)(void*) = ^(void* finished) {\n" +
				"\t\tJNIEnv* threadEnv = NULL;\n" +
				"\t\tint attached = 0;\n" +
				"\t\tjint status = (*vm)->GetEnv(vm, (void**) &threadEnv, JNI_VERSION_1_8);\n" +
				"\t\tif (status == JNI_EDETACHED) {\n" +
				"\t\t\tif ((*vm)->AttachCurrentThreadAsDaemon(vm, (void**) &threadEnv, NULL) != JNI_OK) return;\n" +
				"\t\t\tattached = 1;\n" +
				"\t\t} else if (status != JNI_OK) {\n" +
				"\t\t\treturn;\n" +
				"\t\t}\n" +
				"\t\t(*threadEnv)->CallVoidMethod(threadEnv, callback, run);\n" +
				"\t\tif ((*threadEnv)->ExceptionCheck(threadEnv)) {\n" +
				"\t\t\t(*threadEnv)->ExceptionDescribe(threadEnv);\n" +
				"\t\t\t(*threadEnv)->ExceptionClear(threadEnv);\n" +
				"\t\t}\n" +
				"\t\t(*threadEnv)->DeleteGlobalRef(threadEnv, callback);\n" +
				"\t\tif (attached) (*vm)->DetachCurrentThread(vm);\n" +
				"\t};\n" +
				"\tsendAddCompletedHandler((void*) buffer, addCompletedHandlerSelector, (void*) completed);\n" +
				"}\n";
	}

	/**
	 * Registers {@code handler} to run once the given command buffer has finished, by executing
	 * or by failing. Must be called before the buffer is committed.
	 *
	 * @param buffer  native {@code MTLCommandBuffer} pointer, not yet committed
	 * @param handler run on a Metal thread once the buffer has finished
	 * @throws IllegalStateException if the Objective-C runtime was not loaded with the operation
	 */
	public native void apply(long buffer, Runnable handler);

	/**
	 * Returns the operation, compiling it if this is the first use in the JVM.
	 *
	 * @return the compiled operation
	 * @throws RuntimeException if the operation cannot be compiled, now or by an earlier attempt
	 */
	static MTLCompletedHandler get() {
		MTLCompletedHandler compiled = operation;
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
				Hardware.console.warn("Metal completed handlers cannot be registered on this host, so " +
						"finished command buffers are released only when the runner next drains", e);
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
	private static synchronized MTLCompletedHandler compile() {
		if (operation != null) return operation;
		if (failure != null) {
			throw new IllegalStateException("Metal completed handlers cannot be registered on this host", failure);
		}

		try {
			operation = new MTLCompletedHandler(NativeCompiler.factory(Precision.FP32, false).construct());
			return operation;
		} catch (RuntimeException e) {
			failure = e;
			throw e;
		}
	}
}
