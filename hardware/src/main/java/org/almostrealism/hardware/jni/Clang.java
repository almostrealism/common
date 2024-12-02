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
import java.util.List;

public class Clang extends LlvmCommandProvider {
	private String linker;

	public Clang() {
		this("gcc", true);
	}

	public Clang(String path, boolean localToolchain) {
		super(path, SystemUtils.isMacOS() ? "dynamiclib" : "shared", localToolchain);
	}

	public String getLinker() {
		return linker;
	}

	public void setLinker(String linker) {
		File ld = new File(linker);

		if (ld.exists()) {
			this.linker = ld.getAbsolutePath();
		} else {
			this.linker = linker;
		}
	}

	@Override
	protected void addLinker(List<String> command) {
		if (linker != null) {
			command.add("-fuse-ld=" + linker);
		}
	}
}
