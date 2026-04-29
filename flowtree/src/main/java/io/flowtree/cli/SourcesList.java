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

import com.mchange.v2.c3p0.ComboPooledDataSource;
import io.almostrealism.db.DatabasePools;
import org.jboss.aesh.cl.Arguments;
import org.jboss.aesh.cl.CommandDefinition;
import org.jboss.aesh.console.command.Command;
import org.jboss.aesh.console.command.CommandException;
import org.jboss.aesh.console.command.CommandResult;
import org.jboss.aesh.console.command.invocation.CommandInvocation;

import java.util.List;

/**
 * An Æsh {@link Command} that lists all currently registered JDBC data sources
 * from the application-wide {@link DatabasePools}. The command is exposed as
 * {@code sources} in the FlowTree CLI.
 *
 * <p>For each data source the command prints one line containing the pool key
 * followed by the minimum and maximum pool sizes separated by a slash, e.g.:
 * <pre>
 *   mydb    2/10
 * </pre>
 *
 * @author  Michael Murray
 */
@CommandDefinition(name="sources", description = "List data sources")
public class SourcesList implements Command {

	/** Positional arguments (unused; present for Æsh framework compatibility). */
	@Arguments
	List<String> params;

	/**
	 * Executes the sources-list command, printing one line per registered
	 * {@link DatabasePools} entry showing the pool key and its configured
	 * minimum/maximum connection counts.
	 *
	 * @param invocation the command invocation context used for output
	 * @return {@link CommandResult#SUCCESS} always
	 * @throws CommandException     if the command framework encounters an error
	 * @throws InterruptedException if the thread is interrupted during execution
	 */
	@Override
	public CommandResult execute(CommandInvocation invocation) throws CommandException, InterruptedException {
		for (String s : DatabasePools.keys()) {
			ComboPooledDataSource sr = DatabasePools.get(s);
			invocation.println(s + "\t\t" + sr.getMinPoolSize() + "/" + sr.getMaxPoolSize());
		}

		return CommandResult.SUCCESS;
	}
}
