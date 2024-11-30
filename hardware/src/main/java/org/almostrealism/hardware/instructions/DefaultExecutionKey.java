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

package org.almostrealism.hardware.instructions;

import java.util.Objects;

public class DefaultExecutionKey implements ExecutionKey {
	private String functionName;
	private int argsCount;

	public DefaultExecutionKey(String functionName, int argsCount) {
		this.functionName = functionName;
		this.argsCount = argsCount;
	}

	public String getFunctionName() { return functionName; }
	public int getArgsCount() { return argsCount; }

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof DefaultExecutionKey that)) return false;
		return argsCount == that.argsCount && Objects.equals(functionName, that.functionName);
	}

	@Override
	public int hashCode() {
		return Objects.hash(functionName, argsCount);
	}
}
