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

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public abstract class LlvmCommandProvider implements CompilerCommandProvider {
	private String path, cmd;
	private List<String> includes;
	private boolean appBundle;

	public LlvmCommandProvider(String path, String command, boolean appBundle) {
		this.path = appBundle ? new File(path).getAbsolutePath() : path;
		this.cmd = command;
		this.appBundle = appBundle;
	}

	protected void init() {
		includes = new ArrayList<>();

		if (appBundle) {
			includes.add("-IContents/Resources/include");
		}

		String baseInclude = System.getProperty("java.home") + "/include";
		includes.add("-I" + baseInclude);

		for (File child : new File(baseInclude).listFiles()) {
			if (child.isDirectory()) {
				includes.add("-I" + child.getAbsolutePath());
			}
		}

		if (appBundle) {
			includes.add("-LContents/Resources/lib");
		}
	}

	protected void addLinker(List<String> command) { }

	@Override
	public List<String> getCommand(String inputFile, String outputFile, boolean lib) {
		if (includes == null) init();

		List<String> command = new ArrayList<>();
		command.add(path);
		addLinker(command);
		if (appBundle) command.add("-w");
		command.addAll(includes);
		command.add("-" + cmd);
		command.add(inputFile);
		command.add("-o");
		command.add(outputFile);
		return command;
	}
}

