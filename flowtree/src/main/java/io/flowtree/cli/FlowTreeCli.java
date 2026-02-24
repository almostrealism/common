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

import java.net.InetAddress;
import java.net.UnknownHostException;

public class FlowTreeCli {
	protected static final boolean enableConsoleStop = false;

	private final AeshConsole console;

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

	public void start() {
		console.start();
	}

	public String getHostName() {
		try {
			return InetAddress.getLocalHost().getHostAddress();
		} catch (UnknownHostException e) {
			e.printStackTrace();
			return "localhost";
		}
	}

	public static void main(String... args) {
		new FlowTreeCli().start();
	}
}
