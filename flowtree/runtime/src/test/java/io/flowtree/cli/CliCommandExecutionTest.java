/*
 * Copyright 2026 Michael Murray
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

package io.flowtree.cli;

import com.mchange.v2.c3p0.ComboPooledDataSource;
import io.almostrealism.db.DatabasePools;
import org.almostrealism.util.TestSuiteBase;
import org.jboss.aesh.console.AeshContext;
import org.jboss.aesh.console.Prompt;
import org.jboss.aesh.console.command.Command;
import org.jboss.aesh.console.command.CommandOperation;
import org.jboss.aesh.console.command.CommandResult;
import org.jboss.aesh.console.command.invocation.CommandInvocation;
import org.jboss.aesh.console.command.registry.CommandRegistry;
import org.jboss.aesh.console.operator.ControlOperator;
import org.jboss.aesh.terminal.Shell;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the two &AElig;sh {@link Command} implementations registered by
 * {@link FlowTreeCli}: {@link OpenConnection} and {@link SourcesList}. Each is
 * driven through its {@link Command#execute(CommandInvocation)} contract using a
 * {@link RecordingCommandInvocation} that captures the lines the command prints,
 * and the observable effect on the application-wide {@link DatabasePools}
 * registry is asserted.
 */
public class CliCommandExecutionTest extends TestSuiteBase {

	/**
	 * {@link OpenConnection} prints a usage message and reports failure when fewer
	 * than the three required arguments are supplied, without registering a pool.
	 */
	@Test(timeout = 30000)
	public void openConnectionRejectsMissingArguments() throws Exception {
		OpenConnection command = new OpenConnection();
		command.params = new ArrayList<>(List.of("jdbc:mysql://localhost/only"));

		RecordingCommandInvocation invocation = new RecordingCommandInvocation();
		CommandResult result = command.execute(invocation);

		assertEquals(CommandResult.FAILURE, result);
		assertTrue(invocation.output(),
				invocation.output().contains("Usage: open <uri> <user> <password>"));
	}

	/**
	 * {@link OpenConnection} with a full argument triple registers a c3p0 pool in
	 * {@link DatabasePools}, echoes the open, and reports success.
	 */
	@Test(timeout = 30000)
	public void openConnectionRegistersPool() throws Exception {
		String url = "jdbc:mysql://localhost/cli_open_test";
		OpenConnection command = new OpenConnection();
		command.params = new ArrayList<>(List.of(url, "user", "secret"));

		RecordingCommandInvocation invocation = new RecordingCommandInvocation();
		CommandResult result = command.execute(invocation);

		assertEquals(CommandResult.SUCCESS, result);
		assertNotNull("pool should be registered", DatabasePools.get(url));
		assertTrue(invocation.output(), invocation.output().contains("Opening " + url));
		assertTrue(invocation.output(), invocation.output().contains("Opened " + url));
	}

	/**
	 * {@link SourcesList} prints exactly one line per registered
	 * {@link DatabasePools} entry, formatted as {@code key\t\tmin/max}, and
	 * reports success.
	 */
	@Test(timeout = 30000)
	public void sourcesListPrintsRegisteredPool() throws Exception {
		String url = "jdbc:mysql://localhost/cli_sources_test";
		DatabasePools.open(url, "user", "secret");
		ComboPooledDataSource pool = DatabasePools.get(url);
		assertNotNull(pool);
		String expectedLine = url + "\t\t" + pool.getMinPoolSize() + "/" + pool.getMaxPoolSize();

		SourcesList command = new SourcesList();
		RecordingCommandInvocation invocation = new RecordingCommandInvocation();
		CommandResult result = command.execute(invocation);

		assertEquals(CommandResult.SUCCESS, result);
		assertTrue(invocation.output(), invocation.lines().contains(expectedLine));
	}

	/**
	 * A minimal {@link CommandInvocation} that records every {@code print} and
	 * {@code println} call so the command's terminal output can be asserted. All
	 * other operations are inert defaults, since the commands under test only use
	 * the output methods.
	 */
	private static final class RecordingCommandInvocation implements CommandInvocation {
		/** Accumulates the raw characters written via {@code print}/{@code println}. */
		private final StringBuilder buffer = new StringBuilder();

		/** Each line emitted via {@code println}, in order. */
		private final List<String> lines = new ArrayList<>();

		/**
		 * Returns the complete captured output, including {@code println} newlines.
		 *
		 * @return the accumulated output text
		 */
		String output() {
			return buffer.toString();
		}

		/**
		 * Returns the individual lines emitted via {@code println}.
		 *
		 * @return the captured {@code println} lines in order
		 */
		List<String> lines() {
			return lines;
		}

		@Override
		public ControlOperator getControlOperator() {
			return null;
		}

		@Override
		public CommandRegistry getCommandRegistry() {
			return null;
		}

		@Override
		public Shell getShell() {
			return null;
		}

		@Override
		public void setPrompt(Prompt prompt) {
		}

		@Override
		public Prompt getPrompt() {
			return null;
		}

		@Override
		public String getHelpInfo(String commandName) {
			return null;
		}

		@Override
		public void stop() {
		}

		@Override
		public AeshContext getAeshContext() {
			return null;
		}

		@Override
		public CommandOperation getInput() throws InterruptedException {
			return null;
		}

		@Override
		public String getInputLine() throws InterruptedException {
			return null;
		}

		@Override
		public int getPid() {
			return 0;
		}

		@Override
		public void putProcessInBackground() {
		}

		@Override
		public void putProcessInForeground() {
		}

		@Override
		public void executeCommand(String input) {
		}

		@Override
		public void print(String msg) {
			buffer.append(msg);
		}

		@Override
		public void println(String msg) {
			buffer.append(msg).append('\n');
			lines.add(msg);
		}

		@Override
		public boolean isEchoing() {
			return true;
		}

		@Override
		public void setEcho(boolean echo) {
		}

		@Override
		public Command getPopulatedCommand(String line) {
			return null;
		}
	}
}
