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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.PrintStream;
import java.util.LinkedHashMap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the static command-parsing and dispatch surface of
 * {@link FlowTreeCliServer}: {@link FlowTreeCliServer#parseCommand(String)},
 * the {@link FlowTreeCliServer#runCommand(String, PrintStream, LinkedHashMap)}
 * dispatcher, custom {@link FlowTreeCliServer.Command} registration, and the
 * singleton accessor {@link FlowTreeCliServer#getCurrentInstance()}.
 *
 * <p>These exercise only the branches that are reachable without a running
 * {@link io.flowtree.node.Client} or network server: argument validation,
 * reflective help, and the dispatch of commands whose handlers either return
 * early or operate against collaborators that are {@code null} in a unit-test
 * JVM. Branches that require a live client, the Jython interpreter, or
 * {@code System.exit} are intentionally not driven here.</p>
 */
public class FlowTreeCliServerTest extends TestSuiteBase {

	/**
	 * {@link FlowTreeCliServer#parseCommand(String)} drops the leading verb and
	 * splits the remainder on single spaces.
	 */
	@Test(timeout = 30000)
	public void parseCommandSplitsArgumentsAfterVerb() {
		assertArrayEquals(new String[] {"0", "com.example.Foo"},
				FlowTreeCliServer.parseCommand("::sendtask 0 com.example.Foo"));
		assertArrayEquals(new String[] {"single"},
				FlowTreeCliServer.parseCommand("cmd single"));
	}

	/**
	 * With no space in the input, {@link FlowTreeCliServer#parseCommand(String)}
	 * returns the whole token as the single element (it never yields an empty
	 * array), so a handler guarded by {@code length <= 0} always proceeds.
	 */
	@Test(timeout = 30000)
	public void parseCommandWithoutSpaceReturnsWholeToken() {
		assertArrayEquals(new String[] {"::peers"},
				FlowTreeCliServer.parseCommand("::peers"));
	}

	/**
	 * {@code ::help sendtask} is dispatched to the help handler and returns the
	 * sendtask usage text.
	 */
	@Test(timeout = 30000)
	public void runCommandDispatchesHelp() {
		String out = FlowTreeCliServer.runCommand("::help sendtask", null);
		assertTrue(out, out.startsWith("sendtask <host index>"));
		assertTrue(out, out.contains("Using -1 for host index sends the task to localhost."));
	}

	/**
	 * {@code ::classhelp} reflectively instantiates the named class; a class that
	 * does not implement {@link org.almostrealism.util.Help} yields the
	 * no-help message rather than throwing.
	 */
	@Test(timeout = 30000)
	public void runCommandClassHelpForNonHelpClass() {
		assertEquals("java.lang.Object does not provide help.",
				FlowTreeCliServer.runCommand("::classhelp java.lang.Object", null));
	}

	/**
	 * {@code ::confighelp server.port} returns the documentation for that key, and
	 * an unknown key returns the explicit unknown-parameter message.
	 */
	@Test(timeout = 30000)
	public void runCommandConfigHelpPerKey() {
		String port = FlowTreeCliServer.runCommand("::confighelp server.port", null);
		assertTrue(port, port.contains("The port that the NodeGroup server accepts connections on."));
		assertTrue(port, port.contains("Default: 6767"));

		assertEquals("Unknown parameter.",
				FlowTreeCliServer.runCommand("::confighelp no.such.key", null));
	}

	/**
	 * {@code ::sendtask} with only a verb reports the missing class name, and with
	 * a peer and a class that is not a {@link io.flowtree.job.JobFactory} reports
	 * the type mismatch — both before any client is consulted.
	 */
	@Test(timeout = 30000)
	public void runCommandSendTaskArgumentValidation() {
		assertEquals("Please specify class name.",
				FlowTreeCliServer.runCommand("::sendtask", null));
		assertEquals("java.lang.Object is not a JobFactory.",
				FlowTreeCliServer.runCommand("::sendtask 0 java.lang.Object", null));
	}

	/**
	 * A registered custom {@link FlowTreeCliServer.Command} is matched by its
	 * leading token and invoked with the full command string, in preference to
	 * the Jython fallback.
	 */
	@Test(timeout = 30000)
	public void runCommandDispatchesRegisteredCustomCommand() {
		LinkedHashMap commands = new LinkedHashMap();
		String[] captured = new String[1];
		commands.put("mycmd", (FlowTreeCliServer.Command) (command, out) -> {
			captured[0] = command;
			return "custom-result";
		});

		assertEquals("custom-result",
				FlowTreeCliServer.runCommand("mycmd some args", null, commands));
		assertEquals("mycmd some args", captured[0]);
	}

	/**
	 * Constructing a {@link FlowTreeCliServer} publishes it as the current
	 * instance; a {@code ::render} command then uses that instance's
	 * configuration to start a render thread, and the instance-level
	 * {@link FlowTreeCliServer#runCommand(String)} routes through the same static
	 * dispatcher. An ephemeral port ({@code 0}) is used so the test never
	 * collides with a fixed port.
	 */
	@Test(timeout = 30000)
	public void currentInstanceDrivesDispatch() throws Exception {
		FlowTreeCliServer server = new FlowTreeCliServer("http://localhost/", 4, 0);
		assertSame(server, FlowTreeCliServer.getCurrentInstance());

		String out = FlowTreeCliServer.runCommand("::render scene.xml", null);
		assertTrue(out, out.startsWith("Started render thread:"));

		assertEquals("No help info.", server.runCommand("::help"));

		assertFalse("received messages are left for other listeners",
				server.recievedMessage(null, 0));
	}

	/**
	 * When a numeric argument cannot be parsed, the dispatcher's
	 * {@link NumberFormatException} handler converts it to a stable error message
	 * rather than propagating the exception. This is reached before any client is
	 * consulted, since the parse happens first.
	 */
	@Test(timeout = 30000)
	public void runCommandReportsNumberParseFailure() {
		assertEquals("An error occurred while parsing a number.",
				FlowTreeCliServer.runCommand("::inputrate notanumber", null));
		assertEquals("An error occurred while parsing a number.",
				FlowTreeCliServer.runCommand("::parating notanumber", null));
	}

	/**
	 * {@code ::date} returns the current date rendered by {@link java.util.Date},
	 * which always ends in a four-digit year.
	 */
	@Test(timeout = 30000)
	public void runCommandDateEndsWithYear() {
		String out = FlowTreeCliServer.runCommand("::date", null);
		String year = out.substring(out.length() - 4);
		assertTrue(out, Integer.parseInt(year) >= 2024);
	}
}
