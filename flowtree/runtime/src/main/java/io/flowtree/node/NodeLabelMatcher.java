/*
 * Copyright 2020 Michael Murray
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

import java.util.Map;

/**
 * Static utilities for matching a node's capability labels against a job's
 * label requirements.
 *
 * <p>The matching logic was extracted from {@link Node#satisfies(Map)} so that
 * {@code Node} stays within the 1500-line file-length limit.  The public
 * {@link Node#satisfies(Map)} method delegates here; all semantics are
 * unchanged.</p>
 */
class NodeLabelMatcher {

	/** Utility class — do not instantiate. */
	private NodeLabelMatcher() { }

	/**
	 * Returns {@code true} if {@code labels} satisfies {@code requirements}.
	 *
	 * <p>A node whose labels contain {@code role=relay} always returns
	 * {@code false}: relay nodes never execute jobs.  An empty or
	 * {@code null} requirements map is satisfied by any non-relay node.</p>
	 *
	 * @param labels       the capability labels of the candidate node
	 * @param requirements the required key-value pairs from the job
	 * @return {@code true} if every requirement is matched
	 */
	static boolean satisfies(Map<String, String> labels,
							 Map<String, String> requirements) {
		if ("relay".equals(labels.get("role"))) {
			return false;
		}
		if (requirements == null || requirements.isEmpty()) {
			return true;
		}
		for (Map.Entry<String, String> entry : requirements.entrySet()) {
			String value = labels.get(entry.getKey());
			if (value == null || !value.equals(entry.getValue())) {
				return false;
			}
		}
		return true;
	}
}
