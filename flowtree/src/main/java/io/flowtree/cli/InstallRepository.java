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

@CommandDefinition(name="install", description = "Install a repository on the underlying system.")
public class InstallRepository implements Command {
	@Arguments
	List<String> params;

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
