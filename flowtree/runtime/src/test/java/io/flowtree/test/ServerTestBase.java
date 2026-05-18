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

package io.flowtree.test;

import io.flowtree.Server;
import io.flowtree.node.NodeGroupNodeConfig;
import org.almostrealism.util.TestSuiteBase;
import org.junit.BeforeClass;

/**
 * Base class for tests that construct real {@link Server} instances.
 *
 * <p>Activates offline mode before any {@link Server} is constructed so that
 * tests running in a production environment (where {@code FLOWTREE_ROOT_HOST}
 * is set) do not connect to the live FlowTree controller and receive real
 * jobs.</p>
 *
 * @author Michael Murray
 * @see NodeGroupNodeConfig#OFFLINE_MODE_PROPERTY
 */
public abstract class ServerTestBase extends TestSuiteBase {

	/**
	 * Activates offline mode before any {@link Server} is constructed.
	 *
	 * <p>Without this guard, a test running in a production environment
	 * (where {@code FLOWTREE_ROOT_HOST} is set) would connect to the live
	 * FlowTree controller and receive real jobs.</p>
	 */
	@BeforeClass
	public static void enforceOfflineMode() {
		System.setProperty(NodeGroupNodeConfig.OFFLINE_MODE_PROPERTY, "true");
	}
}
