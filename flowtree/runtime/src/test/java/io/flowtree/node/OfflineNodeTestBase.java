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

package io.flowtree.node;

import org.almostrealism.util.TestSuiteBase;
import org.junit.AfterClass;
import org.junit.BeforeClass;

/**
 * Base class for node-group tests that construct a real {@link NodeGroup}. The
 * {@link NodeGroup} constructor connects to the {@code FLOWTREE_ROOT_HOST} controller
 * named in the environment unless offline mode is active, so a test running where that
 * variable is set would otherwise reach the live controller and receive real jobs. This
 * base forces {@link NodeGroupNodeConfig#OFFLINE_MODE_PROPERTY} on before any group is
 * built and restores its prior value afterwards, so every subclass inherits the guard
 * without repeating it.
 *
 * @author Michael Murray
 */
public abstract class OfflineNodeTestBase extends TestSuiteBase {

	/** The offline-mode property value captured before the suite forced it on, or {@code null} if it was unset. */
	private static String originalOfflineMode;

	/** Forces offline mode so the constructor cannot reach a live controller. */
	@BeforeClass
	public static void enforceOfflineMode() {
		originalOfflineMode = System.getProperty(NodeGroupNodeConfig.OFFLINE_MODE_PROPERTY);
		System.setProperty(NodeGroupNodeConfig.OFFLINE_MODE_PROPERTY, "true");
	}

	/**
	 * Restores the offline-mode property to the value it held before the suite ran so a
	 * later test in the same JVM does not inherit the forced {@code "true"}.
	 */
	@AfterClass
	public static void restoreOfflineMode() {
		if (originalOfflineMode == null) {
			System.clearProperty(NodeGroupNodeConfig.OFFLINE_MODE_PROPERTY);
		} else {
			System.setProperty(NodeGroupNodeConfig.OFFLINE_MODE_PROPERTY, originalOfflineMode);
		}
	}
}
