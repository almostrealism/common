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

import java.lang.reflect.Field;

/**
 * Reflective access to the JVM-wide current-server singleton of
 * {@link OutputServer}. The instance-side {@link OutputServer#setCurrentServer()}
 * can only install {@code this}, never {@code null}, so a test that registers a
 * server cannot restore a previously captured value (including the common
 * {@code null}) through the public API. This seam sets the private {@code current}
 * field directly, letting tests snapshot it before a test and restore it
 * afterwards so their effects do not leak into other tests sharing the same JVM.
 */
final class OutputServerState {

	/** Not instantiable; all access is through the static seam methods. */
	private OutputServerState() {
	}

	/**
	 * Installs the given server as the JVM-wide current-server singleton, which
	 * has no public setter accepting an arbitrary value.
	 *
	 * @param server the server to install, or {@code null} to clear the singleton
	 */
	static void setCurrentServer(OutputServer server) {
		try {
			Field f = OutputServer.class.getDeclaredField("current");
			f.setAccessible(true);
			f.set(null, server);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Unable to restore current server", e);
		}
	}
}
