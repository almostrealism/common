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

public abstract class BaseNative {
	public static final boolean enableVerbose = false;
	private static final List<Class> libs = new ArrayList<>();

	private String head;
	private String functionName;
	private NativeCompiler compiler;

	public BaseNative(NativeCompiler compiler) {
		this.compiler = compiler;
	}

	protected void initNativeFunctionName() {
		functionName = "Java_" +
				getClass().getName().replaceAll("\\.", "_") +
				"_apply";
	}

	protected void initNative() {
		initNativeFunctionName();

		try {
			loadNative(getClass(), getCode());
		} catch (UnsatisfiedLinkError e) {
			throw new RuntimeException(e);
		}
	}

	protected synchronized void loadNative(Class cls, String code) {
		if (libs.contains(cls)) return;

		compiler.compileAndLoad(cls, code);
		libs.add(cls);
	}


	public String getHead() { return head; }

	public void setHead(String head) { this.head = head; }

	protected String getCode() {
		if (getHead() == null) {
			return getFunctionDefinition();
		} else {
			return getHead() + "\n" + getFunctionDefinition();
		}
	}

	protected String getFunctionName() { return functionName; }

	public abstract String getFunctionDefinition();
}
