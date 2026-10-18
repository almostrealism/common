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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the individual command handlers in {@link FlowTreeCliCommands}.
 *
 * <p>Each handler is driven directly (the class is package-private) for the
 * branches reachable without a live {@link io.flowtree.node.Client},
 * {@link io.flowtree.fs.OutputServer}, or
 * {@link io.flowtree.fs.ResourceDistributionTask}: the help text producers, the
 * string-only {@code output}/{@code ccache} dispatchers, the reflective
 * {@code classhelp}/{@code sendtask} type checks, and the "no running
 * collaborator" early returns of the resource and database commands. Handlers
 * that unconditionally touch a running server or the filesystem are not driven
 * here.</p>
 */
public class FlowTreeCliCommandsTest extends TestSuiteBase {

	/**
	 * {@link FlowTreeCliCommands#help(String)} returns distinct usage text for
	 * each recognised sub-topic and a fallback for everything else.
	 */
	@Test(timeout = 30000)
	public void helpReturnsPerTopicUsage() {
		assertTrue(FlowTreeCliCommands.help("::help ls").startsWith("ls <uri> [color]"));
		assertTrue(FlowTreeCliCommands.help("::help set").contains("server.hostname"));
		assertTrue(FlowTreeCliCommands.help("::help dbupdate").contains("dbupdate <relay> <table> <task>"));
		assertTrue(FlowTreeCliCommands.help("::help plyrender").startsWith("plyrender <uri>"));
		assertTrue(FlowTreeCliCommands.help("::help dbs").contains("dbs {start, add}"));
		assertTrue(FlowTreeCliCommands.help("::help sendtask").contains("sendtask <host index>"));
		assertEquals("No help info.", FlowTreeCliCommands.help("::help wat"));
	}

	/**
	 * {@link FlowTreeCliCommands#classHelp(String)} instantiates the named class
	 * and, when it does not implement {@link org.almostrealism.util.Help},
	 * reports that it provides no help.
	 */
	@Test(timeout = 30000)
	public void classHelpReportsNoHelpForPlainClass() throws Exception {
		assertEquals("java.lang.Object does not provide help.",
				FlowTreeCliCommands.classHelp("::classhelp java.lang.Object"));
	}

	/**
	 * {@link FlowTreeCliCommands#configHelp(String)} documents a known key and
	 * rejects an unknown one.
	 */
	@Test(timeout = 30000)
	public void configHelpDocumentsKnownKeyOnly() throws Exception {
		assertTrue(FlowTreeCliCommands.configHelp("::confighelp server.port").contains("Default: 6767"));
		assertEquals("Unknown parameter.", FlowTreeCliCommands.configHelp("::confighelp bogus"));
	}

	/**
	 * {@link FlowTreeCliCommands#output(String)} maps the recognised
	 * {@code -image} suffix and rejects other resource types and the no-argument
	 * form.
	 */
	@Test(timeout = 30000)
	public void outputClassifiesResourceType() {
		assertEquals("not implemented", FlowTreeCliCommands.output("::output foo-image"));
		assertEquals("Unknown resource type.", FlowTreeCliCommands.output("::output datafile"));
		assertEquals("Must specify output resource.", FlowTreeCliCommands.output("::output"));
	}

	/**
	 * {@link FlowTreeCliCommands#ccache(String)} recognises the {@code scene}
	 * cache and rejects any other name.
	 */
	@Test(timeout = 30000)
	public void ccacheRecognisesSceneOnly() {
		assertEquals("not implemented", FlowTreeCliCommands.ccache("::ccache scene now"));
		assertEquals("Unknown cache: other", FlowTreeCliCommands.ccache("::ccache other now"));
	}

	/**
	 * {@link FlowTreeCliCommands#render(String, String, int)} and its PLY/GTS
	 * variants report a started render thread.
	 */
	@Test(timeout = 30000)
	public void renderCommandsStartThread() {
		assertTrue(FlowTreeCliCommands.render("::render scene.xml", "http://localhost/", 4)
				.startsWith("Started render thread:"));
		assertTrue(FlowTreeCliCommands.plyRender("::plyrender mesh.ply", null, 4, null)
				.startsWith("Started render thread:"));
		assertTrue(FlowTreeCliCommands.gtsRender("::gtsrender mesh.gts", null, 4, null)
				.startsWith("Started render thread:"));
	}

	/**
	 * {@link FlowTreeCliCommands#sendTask(String, java.io.PrintStream)} validates
	 * its arguments before touching the client: a missing class name and a class
	 * that is not a {@link io.flowtree.job.JobFactory} are both reported.
	 */
	@Test(timeout = 30000)
	public void sendTaskValidatesBeforeDispatch() throws Exception {
		assertEquals("Please specify class name.",
				FlowTreeCliCommands.sendTask("::sendtask", null));
		assertEquals("java.lang.Object is not a JobFactory.",
				FlowTreeCliCommands.sendTask("::sendtask 0 java.lang.Object", null));
	}

	/**
	 * The resource commands return a "no running task" message when no
	 * {@link io.flowtree.fs.ResourceDistributionTask} is active, which is the
	 * case in a unit-test JVM.
	 */
	@Test(timeout = 30000)
	public void resourceCommandsRequireRunningTask() {
		assertEquals("No running ResourceDistributionTask.",
				FlowTreeCliCommands.ls("::ls /", null));
		assertEquals("No running ResourceDistributionTask.",
				FlowTreeCliCommands.mkdir("::mkdir /dir"));
		assertEquals("No running ResourceDistributionTask.",
				FlowTreeCliCommands.rmdir("::rmdir /dir"));
		assertEquals("No running ResourceDistributionTask.",
				FlowTreeCliCommands.rm("::rm /file"));
	}

	/**
	 * The database commands return a "no DBS running" message when no
	 * {@link io.flowtree.fs.OutputServer} is active, and {@code dbs} reports an
	 * unknown sub-command for an unrecognised verb.
	 */
	@Test(timeout = 30000)
	public void databaseCommandsRequireRunningServer() throws Exception {
		assertEquals("No DBS running.", FlowTreeCliCommands.dbs("::dbs create"));
		assertTrue(FlowTreeCliCommands.dbs("::dbs frobnicate")
				.startsWith("Unknown DBS command: frobnicate"));
		assertEquals("No DBS running.", FlowTreeCliCommands.dbNotify("::dbnotify src"));
		assertEquals("No DBS running.", FlowTreeCliCommands.dbUpdate("::dbupdate", null));
	}
}
