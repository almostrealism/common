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

package io.flowtree.workstream;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link Workstream#getTrackerCapabilities()}: the roles that let
 * agents on a workstream use the narrow tracker tools.
 *
 * <p>ar-manager decides whether to allow those tools by reading the roles from
 * the controller's workstream list, so a role that is not emitted in
 * {@link Workstream#toSummaryJson()}, or not persisted across a controller
 * restart, is a role that silently stops working.</p>
 */
public class WorkstreamTrackerCapabilitiesTest extends TestSuiteBase {

	/** A fresh workstream grants no roles, and says nothing about them. */
	@Test(timeout = 10000)
	public void noRolesByDefault() {
		Workstream ws = new Workstream("ws-fresh", "C", "#c");
		assertTrue(ws.getTrackerCapabilities().isEmpty());
		assertFalse(ws.toSummaryJson().contains("trackerCapabilities"));
	}

	/** Granted roles are emitted as an array for ar-manager to read. */
	@Test(timeout = 10000)
	public void rolesAreEmittedInTheSummary() {
		Workstream ws = new Workstream("ws-roles", "C", "#c");
		ws.setTrackerCapabilities(Arrays.asList("planner", "steward"));
		assertTrue(ws.toSummaryJson().contains("\"trackerCapabilities\":[\"planner\",\"steward\"]"));
	}

	/**
	 * The roles share their array writer with {@code dependentRepos}; both are
	 * written in the same shape, and an empty list is omitted.
	 */
	@Test(timeout = 10000)
	public void dependentReposUseTheSameArrayShape() {
		Workstream ws = new Workstream("ws-deps", "C", "#c");
		ws.setDependentRepos(List.of());
		assertFalse(ws.toSummaryJson().contains("dependentRepos"));
		ws.setDependentRepos(List.of("git@example.com:a/b.git", "c\"d"));
		assertTrue(ws.toSummaryJson().contains(
				"\"dependentRepos\":[\"git@example.com:a/b.git\",\"c\\\"d\"]"));
	}

	/** An unknown role is refused, and a null list grants nothing. */
	@Test(timeout = 10000)
	public void onlyKnownRolesAreAccepted() {
		assertEquals("admin", Workstream.unknownTrackerCapability(List.of("planner", "admin")));
		assertNull(Workstream.unknownTrackerCapability(null));
		Workstream ws = new Workstream("ws-bad", "C", "#c");
		try {
			ws.setTrackerCapabilities(List.of("admin"));
			throw new AssertionError("an unknown role must be refused");
		} catch (IllegalArgumentException expected) {
			assertTrue(ws.getTrackerCapabilities().isEmpty());
		}
		ws.setTrackerCapabilities(null);
		assertTrue(ws.getTrackerCapabilities().isEmpty());
	}

	/** Roles survive a YAML load, a runtime change and a save-and-reload. */
	@Test(timeout = 10000)
	public void rolesRoundTripThroughYaml() throws IOException {
		String yaml = "workstreams:\n"
				+ "  - workstreamId: \"ws-steward\"\n"
				+ "    channelId: \"C-S\"\n"
				+ "    channelName: \"#steward\"\n"
				+ "    defaultBranch: \"feature/steward\"\n"
				+ "    trackerCapabilities:\n"
				+ "      - steward\n";
		WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
		Workstream live = config.getWorkstreams().get(0).toWorkstream();
		assertEquals(List.of("steward"), live.getTrackerCapabilities());

		live.setTrackerCapabilities(List.of("steward", "planner"));
		config.syncFromWorkstreams(Arrays.asList(live));
		File file = File.createTempFile("ws-tracker-roles", ".yaml");
		file.deleteOnExit();
		config.saveToYaml(file);
		Workstream reloaded = WorkstreamConfig.loadFromYaml(file).getWorkstreams().get(0).toWorkstream();
		assertEquals(List.of("steward", "planner"), reloaded.getTrackerCapabilities());
	}

	/** A workstream with no roles writes no roles key. */
	@Test(timeout = 10000)
	public void noRolesMeansNoYamlKey() throws IOException {
		String yaml = "workstreams:\n"
				+ "  - workstreamId: \"ws-plain\"\n"
				+ "    channelId: \"C-P\"\n"
				+ "    channelName: \"#plain\"\n"
				+ "    defaultBranch: \"feature/plain\"\n";
		WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
		File file = File.createTempFile("ws-tracker-none", ".yaml");
		file.deleteOnExit();
		config.saveToYaml(file);
		assertFalse(new String(Files.readAllBytes(file.toPath())).contains("trackerCapabilities"));
	}
}
