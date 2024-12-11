/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.profile;

import java.util.List;

public class OperationSource {
	private String source;
	private List<String> argumentKeys;
	private List<String> argumentNames;

	public OperationSource() { }

	public OperationSource(String source, List<String> argumentKeys, List<String> argumentNames) {
		this.source = source;
		this.argumentKeys = argumentKeys;
		this.argumentNames = argumentNames;
	}

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	public List<String> getArgumentKeys() {
		return argumentKeys;
	}

	public void setArgumentKeys(List<String> argumentKeys) {
		this.argumentKeys = argumentKeys;
	}

	public List<String> getArgumentNames() {
		return argumentNames;
	}

	public void setArgumentNames(List<String> argumentNames) {
		this.argumentNames = argumentNames;
	}
}
