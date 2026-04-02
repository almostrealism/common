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

/**
 * Listener interface for monitoring the activity state of a {@link Node}.
 * Implementations are notified at the end of each activity iteration and
 * whenever the node transitions between working, idle, and isolated states.
 *
 * <p>The canonical reference is {@link Node.ActivityListener}, which is kept
 * as a backward-compatible alias extending this interface so that existing
 * call sites and {@code implements} declarations do not need to change.</p>
 */
public interface NodeActivityListener {

	/**
	 * Called at the end of each activity-thread iteration for the given node.
	 *
	 * @param n the node that completed an iteration
	 */
	void iteration(Node n);

	/**
	 * Called when the node's worker thread begins executing a job.
	 */
	void startedWorking();

	/**
	 * Called when the node's worker thread finishes executing a job.
	 */
	void stoppedWorking();

	/**
	 * Called when the node detects it has no peer connections and cannot
	 * relay jobs to any other part of the network.
	 */
	void becameIsolated();
}
