/*
 * Copyright 2023 Michael Murray
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

package io.almostrealism.code;

import io.almostrealism.relation.Operation;
import io.almostrealism.relation.Process;
import io.almostrealism.uml.Named;
import org.almostrealism.io.Console;

import java.util.Collection;
import java.util.Collections;
import java.util.function.Supplier;

public class LogOperation implements Operation, Named {
	private String name;
	private Console console;
	private Supplier<String> message;

	public LogOperation(Console console, String message) {
		this(console, () -> message);
		this.name = message;
	}

	public LogOperation(Console console, Supplier<String> message) {
		this.console = console;
		this.message = message;
	}

	@Override
	public String getName() { return name; }

	@Override
	public Collection<Process<?, ?>> getChildren() { return Collections.emptyList(); }

	@Override
	public Runnable get() {
		return () -> console.println(message.get());
	}
}
