/*
 * Copyright 2020 Michael Murray
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

package io.almostrealism.code;

import java.util.List;
import java.util.function.Consumer;

public class ExplicitScope<T> extends Scope<T> {
	private StringBuffer code;
	private List<ArrayVariable<?>> arguments;

	public ExplicitScope(OperationAdapter op) {
		this(op.getFunctionName());
		setArguments(op.getArguments());
	}

	public ExplicitScope(String name) {
		this(name, null);
	}

	public ExplicitScope(String name, String code) {
		super(name);
		this.code = new StringBuffer();
		if (code != null) this.code.append(code);
	}

	public void setArguments(List<ArrayVariable<?>> arguments) { this.arguments = arguments; }

	public List<ArrayVariable<?>> getArguments() {
		return arguments == null ? super.getArguments() : arguments;
	}

	public Consumer<String> code() { return code::append; }

	@Override
	public void write(CodePrintWriter w) {
		w.println(code.toString());
	}
}
