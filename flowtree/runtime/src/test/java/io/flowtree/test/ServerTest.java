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

package io.flowtree.test;

import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestSuiteBase;

import io.flowtree.Server;
import io.flowtree.jobs.ExternalProcessJob;
import io.flowtree.job.JobFactory;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Tests for {@link io.flowtree.Server} startup and job instantiation behavior.
 */
public class ServerTest extends TestSuiteBase {
	/**
	 * Verifies that a {@link io.flowtree.Server} can be started on a configured port.
	 */
	@Ignore("Interactive test — starts a server and sleeps for 2 hours")
	@Test(timeout = 10000)
	@TestProperties(longRunning = true)
	public void server() throws IOException, InterruptedException {

		Properties p = new Properties();
		p.setProperty("server.port", "7700");

		Server server = new Server(p);
		server.start();

		Thread.sleep(2 * 60 * 60 * 1000L);
	}

	/**
	 * Verifies that a Base64-encoded job descriptor is correctly decoded into an {@link ExternalProcessJob}.
	 */
	@Test(timeout = 10000)
	public void decodeJobTest() {
		String data = "io.flowtree.jobs.ExternalProcessJob::cmd:=c2xlZXA7MzA=";
		ExternalProcessJob j = (ExternalProcessJob) Server.instantiateJobClass(data);
		log(String.valueOf(j.getCommandString()));
	}

	/**
	 * Regression: {@link Server#instantiateJobClass(String)} shares the
	 * escape-aware separator scan with {@link JobFactory#decode(String)} through
	 * {@link JobFactory#nextEntrySeparator(String, int)}. A final entry whose
	 * second character is {@code /} previously spun forever here — the scan found
	 * no further separator, then restarted the search from the same position —
	 * while the factory decoder had already been guarded against it. The job must
	 * now be instantiated, with the entries before the trailing one applied, and
	 * the {@code timeout} fails the test if the old hang ever returns.
	 */
	@Test(timeout = 10000)
	public void finalEntryWithSlashAsSecondCharacterTerminates() {
		String data = "io.flowtree.jobs.ExternalProcessJob" + JobFactory.ENTRY_SEPARATOR
				+ "cmd" + JobFactory.KEY_VALUE_SEPARATOR + "c2xlZXA7MzA="
				+ JobFactory.ENTRY_SEPARATOR + "a/b" + JobFactory.KEY_VALUE_SEPARATOR + "c";

		ExternalProcessJob j = (ExternalProcessJob) Server.instantiateJobClass(data);

		assertNotNull("a trailing slash-bearing entry must not hang instantiation", j);
		assertEquals("sleep;30", j.getCommandString());
	}
}
