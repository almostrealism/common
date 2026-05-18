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

import io.almostrealism.compute.Process;
import io.almostrealism.relation.Operation;
import io.almostrealism.uml.Named;
import org.almostrealism.io.Console;

import java.util.Collection;
import java.util.Collections;
import java.util.function.Supplier;

/**
 * An {@link Operation} that writes a message to a {@link Console} when executed.
 *
 * <p>{@code LogOperation} is used to inject diagnostic log statements into a
 * computation pipeline. It has no children and its only effect is printing
 * the message to the supplied console.</p>
 *
 * @see Operation
 * @see Console
 */
public class LogOperation implements Operation, Named {
	/** The display name of this log operation, used for tracing and profiling. */
	private String name;
	/** The console to write messages to. */
	private Console console;
	/** Supplier that produces the message string at execution time. */
	private Supplier<String> message;

	/**
	 * Creates a log operation that prints a fixed string message to the console.
	 *
	 * @param console the console to write to
	 * @param message the fixed message string to print
	 */
	public LogOperation(Console console, String message) {
		this(console, () -> message);
		this.name = message;
	}

	/**
	 * Creates a log operation that prints a dynamically computed message to the console.
	 *
	 * @param console the console to write to
	 * @param message a supplier that produces the message string at execution time
	 */
	public LogOperation(Console console, Supplier<String> message) {
		this.console = console;
		this.message = message;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return the name of this log operation
	 */
	@Override
	public String getName() { return name; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>A log operation has no child processes.
	 *
	 * @return an empty collection
	 */
	@Override
	public Collection<Process<?, ?>> getChildren() { return Collections.emptyList(); }

	/**
	 * Returns a {@link Runnable} that prints the message to the console when run.
	 *
	 * @return a runnable that performs the log output
	 */
	@Override
	public Runnable get() {
		return () -> console.println(message.get());
	}
}
