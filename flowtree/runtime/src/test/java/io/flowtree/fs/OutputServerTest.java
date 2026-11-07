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

package io.flowtree.fs;

import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the parts of {@link OutputServer} reachable without binding a server
 * socket or starting an embedded database: the uninitialised no-argument
 * constructor and the JVM-wide current-server registration. The database- and
 * socket-backed behaviour (the {@link OutputServer#init} path, query handling,
 * and the accept loop) is integration territory and is not exercised here.
 *
 * <p>Registering a current server mutates the JVM-wide {@code current} singleton,
 * which other fs tests (for example {@code nextJob} with no server) read. It is
 * snapshotted before each test and restored afterwards via {@link OutputServerState}
 * so these tests neither depend on nor leak that shared state into other tests
 * running in the same JVM.
 */
public class OutputServerTest extends TestSuiteBase {

	/** The current-server singleton captured before each test. */
	private OutputServer savedCurrent;

	/**
	 * Captures the current-server singleton so it can be restored after the test,
	 * keeping the JVM-wide static state unchanged across test boundaries.
	 */
	@Before
	public void snapshotCurrentServer() {
		savedCurrent = OutputServer.getCurrentServer();
	}

	/**
	 * Restores the current-server singleton captured in
	 * {@link #snapshotCurrentServer()}.
	 */
	@After
	public void restoreCurrentServer() {
		OutputServerState.setCurrentServer(savedCurrent);
	}

	/**
	 * An {@link OutputServer} built with the no-argument constructor is not yet
	 * initialised, so it has no associated node server until
	 * {@link OutputServer#init} is called.
	 */
	@Test(timeout = 5000)
	public void uninitialisedServerHasNoNodeServer() {
		Assert.assertNull(new OutputServer().getNodeServer());
	}

	/**
	 * {@link OutputServer#setCurrentServer()} must publish the instance as the
	 * JVM-wide current server, and a later registration must replace it, so that
	 * {@link OutputServer#getCurrentServer()} always reflects the most recent
	 * registration.
	 */
	@Test(timeout = 5000)
	public void setCurrentServerPublishesMostRecentInstance() {
		OutputServer first = new OutputServer();
		first.setCurrentServer();
		Assert.assertSame(first, OutputServer.getCurrentServer());

		OutputServer second = new OutputServer();
		second.setCurrentServer();
		Assert.assertSame(second, OutputServer.getCurrentServer());
	}
}
