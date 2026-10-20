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
import java.util.ArrayList;
import java.util.List;

/**
 * Reflective access to the JVM-wide static state of
 * {@link ResourceDistributionTask} — the current-task singleton and the
 * resource-type parser registry — so that tests which unavoidably mutate that
 * shared state can snapshot it before a test and restore it afterwards, keeping
 * their effects from leaking into other tests running in the same JVM. Neither
 * field has a public accessor, which is why the access lives here rather than on
 * the class itself; the reflection is shared by every fs test that needs it so
 * the seam is defined in exactly one place.
 */
final class ResourceDistributionTaskState {

	/** Not instantiable; all access is through the static seam methods. */
	private ResourceDistributionTaskState() {
	}

	/**
	 * Returns the live JVM-wide parser registry list. The raw {@link List} type
	 * mirrors the field's own declaration.
	 *
	 * @return the mutable {@code resourceTypes} list
	 */
	static List registry() {
		try {
			Field f = ResourceDistributionTask.class.getDeclaredField("resourceTypes");
			f.setAccessible(true);
			return (List) f.get(null);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Unable to access resourceTypes registry", e);
		}
	}

	/**
	 * Captures a snapshot of the current parser registry contents.
	 *
	 * @return a copy of the registry list, suitable for {@link #restoreRegistry(List)}
	 */
	static List snapshotRegistry() {
		return new ArrayList(registry());
	}

	/**
	 * Restores the parser registry to a previously captured snapshot.
	 *
	 * @param snapshot the contents captured by {@link #snapshotRegistry()}
	 */
	static void restoreRegistry(List snapshot) {
		List live = registry();
		live.clear();
		live.addAll(snapshot);
	}

	/**
	 * Installs the given task as the JVM-wide current-task singleton, which has
	 * no public setter.
	 *
	 * @param task the task to install, or {@code null} to clear the singleton
	 */
	static void setCurrentTask(ResourceDistributionTask task) {
		try {
			Field f = ResourceDistributionTask.class.getDeclaredField("current");
			f.setAccessible(true);
			f.set(null, task);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Unable to restore current task", e);
		}
	}
}
