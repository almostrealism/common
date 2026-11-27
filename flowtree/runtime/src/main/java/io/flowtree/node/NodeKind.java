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

import java.util.Locale;

/**
 * The kind of a {@link Node}: what runtime executes the jobs routed to it, and
 * therefore which {@link Node} subtype a {@link NodeGroup} constructs for a typed
 * {@code nodes.<i>.kind} configuration entry.
 *
 * <p>A node kind is the single point that decides a Node's execution strategy.
 * Each constant knows how to {@link #create(NodeGroup, int, int, NodeConfig)}
 * the Node it names, so adding a container runtime as a new Node kind means adding
 * a constant here (returning a {@code Node} subtype that overrides the launch seam)
 * rather than threading a type switch through {@link NodeGroup}.</p>
 *
 * <p>The native kind runs jobs in-process on the host the Server runs on, so the
 * host's {@link AutomaticLabel} values (platform, hostname) describe it. A container
 * kind runs jobs in an image whose platform is a property of that image rather than
 * of the host, so {@link #describesHost()} returns {@code false} for it and the
 * host's automatic labels are not copied onto it.</p>
 *
 * @author  Michael Murray
 * @see NodeConfig
 * @see <a href="../docs/node-relay.md">Node Relay and Job Routing</a>
 */
public enum NodeKind {
	/**
	 * A Node that runs its jobs in-process on the host, using the inherited
	 * {@link Node#launch(io.flowtree.job.Job)} body. This is today's {@code Node}
	 * and the only kind a legacy {@code nodes.initial} configuration produces.
	 */
	NATIVE("native") {
		@Override
		Node create(NodeGroup group, int maxJobs, int maxPeers, NodeConfig config) {
			return new Node(group, config.index(), maxJobs, maxPeers);
		}
	},

	/**
	 * A Node that runs each job in a fresh Docker container, overriding
	 * {@link Node#launch(io.flowtree.job.Job)} to hand the job to a
	 * {@link DockerNode} worker. Its platform is a property of the configured
	 * {@code nodes.<i>.image}, not of the host, so {@link #describesHost()} is
	 * {@code false} and the host's automatic labels are not copied onto it.
	 */
	DOCKER("docker") {
		@Override
		boolean describesHost() {
			return false;
		}

		@Override
		Node create(NodeGroup group, int maxJobs, int maxPeers, NodeConfig config) {
			return new DockerNode(group, config.index(), maxJobs, maxPeers, config);
		}
	};

	/** The {@code nodes.<i>.kind} value that selects this constant. */
	private final String key;

	/**
	 * @param key  The configuration value that selects this kind.
	 */
	NodeKind(String key) {
		this.key = key;
	}

	/**
	 * Returns the {@code nodes.<i>.kind} value that selects this constant.
	 *
	 * @return  The configuration key; never {@code null}.
	 */
	public String key() {
		return key;
	}

	/**
	 * Whether a Node of this kind runs its jobs on the host the Server runs on.
	 * When {@code true}, the host's {@link AutomaticLabel} values describe the Node
	 * and are applied to it unless a configured label already supplies the key.
	 *
	 * @return  {@code true} for host-resident kinds; {@code false} for kinds whose
	 *          jobs run in a separate runtime whose platform is not the host's.
	 */
	boolean describesHost() {
		return true;
	}

	/**
	 * Constructs the {@link Node} this kind names, as a child of the given group.
	 * The Node's id is the {@link NodeConfig#index() index} of the configuration
	 * entry it is built from, so it is read from {@code config} rather than passed
	 * separately.
	 *
	 * @param group     The owning {@link NodeGroup}.
	 * @param maxJobs    Maximum queued jobs before the Node sheds to peers.
	 * @param maxPeers   Maximum peer connections.
	 * @param config     The typed configuration entry this Node is built from.
	 * @return  The constructed Node; never {@code null}.
	 */
	abstract Node create(NodeGroup group, int maxJobs, int maxPeers, NodeConfig config);

	/**
	 * Resolves a {@code nodes.<i>.kind} configuration value to its constant.
	 *
	 * @param key  The configuration value, matched case-insensitively.
	 * @return  The matching kind.
	 * @throws IllegalArgumentException  If no kind uses the given value.
	 */
	static NodeKind forKey(String key) {
		String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
		for (NodeKind kind : values()) {
			if (kind.key.equals(normalized)) return kind;
		}
		throw new IllegalArgumentException("Unknown node kind: " + key);
	}
}
