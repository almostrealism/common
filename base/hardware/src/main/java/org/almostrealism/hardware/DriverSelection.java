/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.hardware;

import io.almostrealism.compute.ComputeRequirement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The backends named by {@code AR_HARDWARE_DRIVER}, and — separately — which of
 * them the caller insisted upon.
 *
 * <p>That distinction is the reason this type exists. A driver may appear in the
 * selection because it was asked for by name, or because the {@code *} wildcard
 * expanded to whatever the platform might support. The two carry different
 * expectations:</p>
 *
 * <ul>
 *   <li><strong>Named</strong> — {@code cl}, {@code mtl}, {@code native},
 *       {@code cpu}, {@code gpu}. The caller stated which backend they want, so
 *       a backend that then fails to initialize is a failure of the request.
 *       {@link #isRequired(ComputeRequirement)} reports true for these.</li>
 *   <li><strong>Wildcard</strong> — {@code *} expands to the backends the
 *       platform plausibly supports, in preference order. These are attempts,
 *       not commitments; one failing is ordinary and is expected to be tolerated.</li>
 * </ul>
 *
 * <p>The two combine, which is the point. {@code cl,*} means "give me everything
 * available, but OpenCL must be among it": {@link ComputeRequirement#CL} is
 * required, while the additions the wildcard contributes are not.</p>
 *
 * <p>Requirements are reported in the order they were requested, and a backend
 * named twice (whether directly or via the wildcard) appears once.</p>
 *
 * <p>Parsing is deliberately free of side effects. Callers that need to act on a
 * selection — enabling shared memory, constraining precision — read the flags
 * here and apply them, so that a selection can be examined without disturbing
 * global state.</p>
 *
 * @see Hardware
 */
public class DriverSelection {
	/** The backends to attempt, in preference order, without duplicates. */
	private final List<ComputeRequirement> requirements;

	/** The subset of {@link #requirements} that was named rather than inferred. */
	private final Set<ComputeRequirement> required;

	/** Whether the selection asks for the NIO shared-memory bridge between backends. */
	private final boolean sharedMemoryPreferred;

	/**
	 * Creates a selection from already-resolved parts.
	 *
	 * @param requirements  backends to attempt, in preference order
	 * @param required  backends that were named, and so must initialize
	 * @param sharedMemoryPreferred  whether shared memory between backends is wanted
	 */
	protected DriverSelection(List<ComputeRequirement> requirements,
							  Set<ComputeRequirement> required,
							  boolean sharedMemoryPreferred) {
		this.requirements = Collections.unmodifiableList(new ArrayList<>(requirements));
		this.required = Collections.unmodifiableSet(new LinkedHashSet<>(required));
		this.sharedMemoryPreferred = sharedMemoryPreferred;
	}

	/**
	 * The backends to attempt, in the order they should be preferred.
	 *
	 * @return an unmodifiable list, free of duplicates
	 */
	public List<ComputeRequirement> getRequirements() { return requirements; }

	/**
	 * The backends that were named explicitly, and which must therefore
	 * initialize successfully.
	 *
	 * @return an unmodifiable set, possibly empty
	 */
	public Set<ComputeRequirement> getRequired() { return required; }

	/**
	 * Whether the given backend was named rather than contributed by the wildcard.
	 *
	 * <p>Note that this reports on the requirement <em>as requested</em>. A
	 * request for {@link ComputeRequirement#GPU} resolves to a concrete backend
	 * only later, and it is the request that was explicit.</p>
	 *
	 * @param requirement  the backend to test
	 * @return whether failing to initialize it should be treated as an error
	 */
	public boolean isRequired(ComputeRequirement requirement) {
		return required.contains(requirement);
	}

	/**
	 * Whether the selection asks for the NIO shared-memory bridge between backends.
	 *
	 * <p>True only when the wildcard stands alone on a platform where it
	 * contributes Metal. A selection that also names a backend is expressing a
	 * preference the shared bridge may not be able to honour, so the bridge is
	 * left off rather than silently overriding what was asked for.</p>
	 *
	 * @return whether shared memory between backends is wanted
	 */
	public boolean isSharedMemoryPreferred() { return sharedMemoryPreferred; }

	/**
	 * Whether the selection requires all backends to agree on precision.
	 *
	 * <p>True when OpenCL was named directly. The wildcard may also contribute
	 * OpenCL, but a selection that merely tolerates it does not constrain the
	 * precision every other backend must use.</p>
	 *
	 * @return whether uniform precision is required across backends
	 */
	public boolean isUniformPrecisionRequired() {
		return isRequired(ComputeRequirement.CL);
	}

	/**
	 * Parses an {@code AR_HARDWARE_DRIVER} value.
	 *
	 * <p>The value is a comma-separated list of {@code cl}, {@code mtl},
	 * {@code native}, {@code cpu}, {@code gpu}, and {@code *}. Case is ignored
	 * and surrounding whitespace is trimmed.</p>
	 *
	 * <p>Backends accumulate in the order encountered, and one contributed more
	 * than once — by being named and then reached again through the wildcard —
	 * is attempted only once, at the position it was first requested.</p>
	 *
	 * @param spec  the driver value; {@code null} or empty is treated as {@code *}
	 * @param macOS  whether the host is macOS
	 * @param aarch64  whether the host is aarch64
	 * @return the parsed selection
	 * @throws IllegalStateException if a token is not a recognized driver
	 */
	public static DriverSelection parse(String spec, boolean macOS, boolean aarch64) {
		String value = (spec == null || spec.trim().isEmpty()) ? "*" : spec;
		String[] tokens = value.split(",");

		Set<ComputeRequirement> requirements = new LinkedHashSet<>();
		Set<ComputeRequirement> required = new LinkedHashSet<>();
		boolean sharedMemory = false;

		for (String token : tokens) {
			String driver = token.trim();

			if ("cl".equalsIgnoreCase(driver)) {
				requirements.add(ComputeRequirement.CL);
				required.add(ComputeRequirement.CL);
			} else if ("mtl".equalsIgnoreCase(driver)) {
				requirements.add(ComputeRequirement.MTL);
				required.add(ComputeRequirement.MTL);
			} else if ("native".equalsIgnoreCase(driver)) {
				requirements.add(ComputeRequirement.JNI);
				required.add(ComputeRequirement.JNI);
			} else if ("cpu".equalsIgnoreCase(driver)) {
				requirements.add(ComputeRequirement.CPU);
				required.add(ComputeRequirement.CPU);
			} else if ("gpu".equalsIgnoreCase(driver)) {
				requirements.add(ComputeRequirement.GPU);
				required.add(ComputeRequirement.GPU);
			} else if ("*".equalsIgnoreCase(driver)) {
				if (macOS) {
					requirements.add(ComputeRequirement.JNI);
					if (aarch64) requirements.add(ComputeRequirement.MTL);
					requirements.add(ComputeRequirement.CL);
				} else {
					requirements.add(ComputeRequirement.CL);
					requirements.add(ComputeRequirement.JNI);
				}

				if (tokens.length <= 1 && requirements.contains(ComputeRequirement.MTL)) {
					sharedMemory = true;
				}
			} else {
				throw new IllegalStateException("Unknown driver " + driver);
			}
		}

		return new DriverSelection(new ArrayList<>(requirements), required, sharedMemory);
	}

	@Override
	public String toString() {
		return "DriverSelection[requirements=" + requirements
				+ ", required=" + required
				+ ", sharedMemory=" + sharedMemoryPreferred + "]";
	}
}
