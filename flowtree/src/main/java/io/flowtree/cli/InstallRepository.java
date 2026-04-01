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

import org.jboss.aesh.cl.Arguments;
import org.jboss.aesh.cl.CommandDefinition;
import org.jboss.aesh.console.command.Command;
import org.jboss.aesh.console.command.CommandException;
import org.jboss.aesh.console.command.CommandResult;
import org.jboss.aesh.console.command.invocation.CommandInvocation;

import java.io.IOException;
import java.util.List;

/**
 * An Æsh {@link Command} that clones a source-code repository to the local
 * machine and builds it with Maven. The command is exposed as {@code install}
 * in the FlowTree CLI.
 *
 * <p>Usage: {@code install <vcs> <url>}
 * <ul>
 *   <li>{@code git} — clones via {@code git clone} then runs {@code mvn install}
 *       in the resulting directory.</li>
 *   <li>{@code hg}  — clones via {@code hg clone} then runs {@code mvn install}
 *       in the resulting directory.</li>
 * </ul>
 *
 * <p>The combined shell command is printed to the console before execution.
 * If the process exits with an error or an {@link IOException} is raised, the
 * command returns {@link CommandResult#FAILURE}.
 *
 * @author  Michael Murray
 */
@CommandDefinition(name="install", description = "Install a repository on the underlying system.")
public class InstallRepository implements Command {

	/** Positional arguments provided to the {@code install} command. */
	@Arguments
	List<String> params;

	/**
	 * Executes the repository install command. Constructs the appropriate
	 * {@code clone} and {@code mvn install} shell pipeline based on the VCS
	 * type in {@code params.get(0)} and the URL in {@code params.get(1)},
	 * then runs the pipeline via {@link Runtime#exec(String)}.
	 *
	 * @param invocation the command invocation context used for output
	 * @return {@link CommandResult#SUCCESS} if the process completes without
	 *         error, {@link CommandResult#FAILURE} on I/O error
	 * @throws CommandException     if the command framework encounters an error
	 * @throws InterruptedException if the thread is interrupted while waiting
	 *                              for the process to finish
	 */
	@Override
	public CommandResult execute(CommandInvocation invocation) throws CommandException, InterruptedException {
		String command = null;

		if (params.get(0).equals("git")) {
			command = "git clone " + params.get(1) + " && cd " +
					params.get(0).substring(params.get(0).lastIndexOf("/")) +
					" && mvn install";
		} else if (params.get(0).equals("hg")) {
			command = "hg clone " + params.get(1) + " && cd " +
					params.get(0).substring(params.get(0).lastIndexOf("/")) +
					" && mvn install";
		}

		invocation.println(command);

		try {
			Process p = Runtime.getRuntime().exec(command);
			p.waitFor();
		} catch (IOException ioe) {
			ioe.printStackTrace();
			return CommandResult.FAILURE;
		}

		return CommandResult.SUCCESS;
	}
}
