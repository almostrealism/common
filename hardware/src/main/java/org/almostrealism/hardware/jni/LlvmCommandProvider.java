/*
 * Copyright 2023 Michael Murray
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

import org.almostrealism.io.SystemUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public abstract class LlvmCommandProvider implements CompilerCommandProvider {
	private static String includePath = SystemUtils.getProperty("AR_HARDWARE_NATIVE_INCLUDES", "Contents/Resources/include");
	private static String libPath = SystemUtils.getProperty("AR_HARDWARE_NATIVE_LIBS", "Contents/Resources/lib");

	private String path, cmd;
	private List<String> includes;
	private boolean localToolchain;

	public LlvmCommandProvider(String path, String command, boolean localToolchain) {
		this.path = localToolchain ? path : new File(path).getAbsolutePath();
		this.cmd = command;
		this.localToolchain = localToolchain;
	}

	protected void init() {
		includes = new ArrayList<>();

		if (!localToolchain) {
			includes.add("-I" + includePath);
		}

		String baseInclude = System.getProperty("java.home") + "/include";
		includes.add("-I" + baseInclude);

		for (File child : new File(baseInclude).listFiles()) {
			if (child.isDirectory()) {
				includes.add("-I" + child.getAbsolutePath());
			}
		}

		if (!localToolchain) {
			includes.add("-L" + libPath);
		}
	}

	protected void addLinker(List<String> command) { }

	@Override
	public List<String> getCommand(String inputFile, String outputFile, boolean lib) {
		if (includes == null) init();

		List<String> command = new ArrayList<>();
		command.add(path);
		addLinker(command);

		if (!localToolchain) command.add("-w");

		if (SystemUtils.isMacOS()) {
			command.add("-framework");
			command.add("OpenCL");
		}

		command.addAll(includes);

		command.add("-" + cmd);
		command.add(inputFile);
		command.add("-o");
		command.add(outputFile);
		return command;
	}
}
