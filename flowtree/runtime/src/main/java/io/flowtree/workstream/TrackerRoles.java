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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The tracker roles a workstream grants to the agents that run on it, in
 * canonical form.
 *
 * <p>There is one role, {@link #PLANNER}. Every agent may read the tracker
 * within its own workspace, file a task into the backlog, and edit the tasks
 * linked to its own workstream; a planner may also create tasks with every
 * field set, edit any task in its workspace, and create releases. ar-manager
 * enforces this on its side, reading the roles from the controller's
 * workstream list.</p>
 *
 * <p>Earlier configurations also used {@code steward}, which is accepted as a
 * legacy alias for {@code planner} so saved workstream configurations keep
 * loading. Constructing a {@code TrackerRoles} resolves aliases and drops
 * duplicates, keeping the first occurrence's position, so the roles held are
 * always canonical.</p>
 *
 * @param roles the canonical roles, without duplicates; never {@code null}
 */
public record TrackerRoles(List<String> roles) {

	/** The planner role: full task fields, editing any task in the workspace, creating releases. */
	public static final String PLANNER = "planner";

	/** Every canonical role. */
	public static final Set<String> KNOWN = Set.of(PLANNER);

	/** Legacy role names accepted in place of a canonical role, mapped to that role. */
	public static final Map<String, String> LEGACY_ALIASES = Map.of("steward", PLANNER);

	/**
	 * Canonicalises {@code roles}: resolves legacy aliases and removes
	 * duplicates.
	 *
	 * @param roles the requested roles; {@code null} grants none
	 * @throws IllegalArgumentException if an entry is not a known role or alias
	 */
	public TrackerRoles {
		String unknown = firstUnknown(roles);
		if (unknown != null) {
			throw new IllegalArgumentException("Unknown tracker capability: " + unknown);
		}
		List<String> canonical = new ArrayList<>();
		if (roles != null) {
			for (String role : roles) {
				String resolved = LEGACY_ALIASES.getOrDefault(role, role);
				if (!canonical.contains(resolved)) canonical.add(resolved);
			}
		}
		roles = List.copyOf(canonical);
	}

	/**
	 * Returns the first entry of {@code roles} that is neither a known role nor
	 * a legacy alias of one, or {@code null} when every entry is accepted.
	 *
	 * <p>A {@code null} entry is itself unknown and is reported as the string
	 * {@code "null"}: returning the entry verbatim would collide with the
	 * method's {@code null} success sentinel, letting a malformed list (e.g.
	 * from malformed YAML) pass validation and then fail later with an opaque
	 * {@link NullPointerException} instead of a clear rejection.</p>
	 *
	 * @param roles the proposed roles; may be {@code null}
	 * @return the first unknown value ({@code "null"} for a null entry), or
	 *         {@code null} when every entry is accepted
	 */
	public static String firstUnknown(List<String> roles) {
		if (roles == null) return null;
		for (String role : roles) {
			if (role == null) return "null";
			if (!KNOWN.contains(role) && !LEGACY_ALIASES.containsKey(role)) return role;
		}
		return null;
	}
}
