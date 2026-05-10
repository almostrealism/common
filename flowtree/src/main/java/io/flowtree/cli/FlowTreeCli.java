/*
 * Copyright 2018 Michael Murray
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

import org.jboss.aesh.console.AeshConsole;
import org.jboss.aesh.console.AeshConsoleBuilder;
import org.jboss.aesh.console.Console;
import org.jboss.aesh.console.Prompt;
import org.jboss.aesh.console.command.registry.AeshCommandRegistryBuilder;
import org.jboss.aesh.console.command.registry.CommandRegistry;
import org.jboss.aesh.console.helper.InterruptHook;
import org.jboss.aesh.console.settings.Settings;
import org.jboss.aesh.console.settings.SettingsBuilder;
import org.jboss.aesh.edit.actions.Action;
import org.jboss.aesh.terminal.Color;
import org.jboss.aesh.terminal.TerminalColor;
import org.jboss.aesh.terminal.TerminalString;

import org.almostrealism.io.ConsoleFeatures;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * An interactive terminal-based command-line interface for FlowTree built on
 * the <a href="https://github.com/aeshell/aesh">Æsh</a> console framework.
 *
 * <p>On construction a command registry is assembled from the built-in commands
 * {@link OpenConnection} and {@link SourcesList}, a prompt showing
 * {@code [flowtree@<host-ip>]$} is configured, and an interrupt hook is
 * installed that optionally stops the console on EOF ({@link Action#EOF}).
 * Whether the hook actually stops the console is controlled by
 * {@link #enableConsoleStop}.
 *
 * <p>The interactive session is started by calling {@link #start()}, which
 * hands control to the underlying Æsh console loop.
 *
 * @author  Michael Murray
 */
public class FlowTreeCli implements ConsoleFeatures {

	/**
	 * When {@code true}, the console is stopped when an EOF action is received
	 * (e.g. Ctrl-D on POSIX systems). When {@code false} (the default), the
	 * interrupt hook takes no action.
	 */
	protected static final boolean enableConsoleStop = false;

	/** The underlying Æsh interactive console instance. */
	private final AeshConsole console;

	/**
	 * Constructs and configures a new {@link FlowTreeCli} instance. Registers
	 * the {@link OpenConnection} and {@link SourcesList} commands, sets up the
	 * terminal prompt, and installs an interrupt hook.
	 */
	public FlowTreeCli() {
		CommandRegistry r = new AeshCommandRegistryBuilder()
								.command(new OpenConnection())
								.command(new SourcesList())
								.create();

		Settings s = new SettingsBuilder()
				.logging(false).persistHistory(false)
				.interruptHook(new InterruptHook() {
					@Override
					public void handleInterrupt(Console console, Action action) {
						if (enableConsoleStop && action == Action.EOF) {
							console.stop();
						}
					}
				}).create();
		console = new AeshConsoleBuilder()
				.commandRegistry(r)
				.settings(s)
				.prompt(new Prompt(new TerminalString("[flowtree@" + getHostName() + "]$ ",
						new TerminalColor(Color.WHITE, Color.BLACK, Color.Intensity.NORMAL))))
				.create();
	}

	/**
	 * Starts the interactive Æsh console loop, blocking until the console is
	 * stopped.
	 */
	public void start() {
		console.start();
	}

	/**
	 * Returns the IP address of the local host as a string, used to build the
	 * terminal prompt. Falls back to {@code "localhost"} if the host address
	 * cannot be resolved.
	 *
	 * @return the local host IP address string, or {@code "localhost"} on error
	 */
	public String getHostName() {
		try {
			return InetAddress.getLocalHost().getHostAddress();
		} catch (UnknownHostException e) {
			warn(e.getMessage(), e);
			return "localhost";
		}
	}

	/**
	 * Application entry point. Constructs a {@link FlowTreeCli} instance and
	 * starts the interactive console.
	 *
	 * @param args command-line arguments (unused)
	 */
	public static void main(String... args) {
		new FlowTreeCli().start();
	}
}
