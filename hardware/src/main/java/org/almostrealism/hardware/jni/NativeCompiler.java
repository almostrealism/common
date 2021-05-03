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

package org.almostrealism.hardware.jni;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

public class NativeCompiler {
	public static final String LIB_NAME_REPLACE = "%NAME%";

	private final String executable;
	private final String compiler;
	private final String libDir;
	private final String libFormat;

	public NativeCompiler(String compiler, String libDir, String libFormat) {
		this.executable = compiler.contains(".") ? compiler.substring(compiler.lastIndexOf(".") + 1) : null;
		this.compiler = compiler;
		this.libDir = libDir;
		this.libFormat = libFormat;
	}

	protected String getExecutable() {
		if (executable != null) return executable;
		return compiler;
	}

	protected String getInputFile(String name) {
		return libDir + "/" + name + ".c";
	}

	protected String getOutputFile(String name) {
		return libDir + "/" + libFormat.replaceAll(LIB_NAME_REPLACE, name);
	}

	protected List<String> getArguments(String name) {
		List<String> command = new ArrayList<>();
		if (executable != null) command.add(compiler);
		command.add(getInputFile(name));
		command.add(getOutputFile(name));
		return command;
	}

	protected List<String> getCommand(String name) {
		List<String> command = new ArrayList<>();
		command.add(getExecutable());
		command.addAll(getArguments(name));
		return command;
	}

	public String compile(Class target, String code) throws IOException, InterruptedException {
		String name = target.getName();

		try (FileOutputStream out = new FileOutputStream(getInputFile(name));
				BufferedWriter buf = new BufferedWriter(new OutputStreamWriter(out))) {
			buf.write(code);
		}

		Process process = new ProcessBuilder(getCommand(name)).inheritIO().start();
		process.waitFor();
		return name;
	}

	public void compileAndLoad(Class target, String code) throws IOException, InterruptedException {
		System.loadLibrary(compile(target, code));
	}

	public void compileAndLoad(Class target, NativeLibrary lib) throws IOException, InterruptedException {
		compileAndLoad(target, lib.getFunctionDefinition());
	}
}
