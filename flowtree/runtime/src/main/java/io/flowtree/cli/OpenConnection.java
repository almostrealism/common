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

import io.almostrealism.db.DatabasePools;
import org.jboss.aesh.cl.Arguments;
import org.jboss.aesh.cl.CommandDefinition;
import org.jboss.aesh.console.command.Command;
import org.jboss.aesh.console.command.CommandException;
import org.jboss.aesh.console.command.CommandResult;
import org.jboss.aesh.console.command.invocation.CommandInvocation;

import java.util.List;

/**
 * An Æsh {@link Command} that opens a new JDBC data-source connection and
 * registers it with the application-wide {@link DatabasePools}. The command is
 * exposed as {@code open} in the FlowTree CLI.
 *
 * <p>Usage: {@code open <uri> <user> <password>}
 * <ul>
 *   <li>{@code uri}      — JDBC connection URI (e.g. {@code jdbc:mysql://host/db})</li>
 *   <li>{@code user}     — database user name</li>
 *   <li>{@code password} — database password</li>
 * </ul>
 *
 * <p>If fewer than three arguments are supplied the command prints a usage
 * message and returns {@link CommandResult#FAILURE}.
 *
 * @author  Michael Murray
 */
@CommandDefinition(name="open", description = "Introduce a data source")
public class OpenConnection implements Command {

	/** Positional arguments provided to the {@code open} command. */
	@Arguments
	List<String> params;

	/**
	 * Executes the open-connection command. Validates that at least three
	 * arguments are present, then delegates to
	 * {@link DatabasePools#open(String, String, String)} with the URI, user,
	 * and password.
	 *
	 * @param invocation the command invocation context used for output
	 * @return {@link CommandResult#SUCCESS} if the connection was opened, or
	 *         {@link CommandResult#FAILURE} if arguments are missing
	 * @throws CommandException     if the command framework encounters an error
	 * @throws InterruptedException if the thread is interrupted during execution
	 */
	@Override
	public CommandResult execute(CommandInvocation invocation) throws CommandException, InterruptedException {
		if (params == null || params.size() < 3) {
			invocation.println("Usage: open <uri> <user> <password>");
			return CommandResult.FAILURE;
		}

		invocation.println("Opening " + params.get(0));
		DatabasePools.open(params.get(0), params.get(1), params.get(2));
		invocation.println("Opened " + params.get(0));
		return CommandResult.SUCCESS;
	}
}
