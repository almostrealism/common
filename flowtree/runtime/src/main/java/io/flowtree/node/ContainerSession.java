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

import java.io.BufferedReader;
import java.io.IOException;

/**
 * A running container started by a {@link ContainerLauncher}. It exposes the
 * container's combined standard output as a line stream and lets the caller block
 * until the container process exits.
 *
 * <p>A {@link DockerNode} reads {@link #output()} line by line — treating each line
 * as liveness for its outer silence guard and parsing the worker's framed result
 * line — and then {@link #awaitExit() awaits} the exit status it maps back onto the
 * job's completion. The container is named, so a cancellation can reach it through
 * {@link ContainerLauncher#kill(String)}.</p>
 *
 * @author  Michael Murray
 * @see ContainerLauncher
 * @see DockerNode
 */
public interface ContainerSession {

	/**
	 * Returns the container's combined standard output and error as a line stream.
	 * Each worker event is one line, so a reader that keeps receiving lines knows the
	 * container is alive.
	 *
	 * @return  the output reader; never {@code null}.
	 */
	BufferedReader output();

	/**
	 * Blocks until the container process exits and returns its exit status. A
	 * non-zero status is an infrastructure failure the {@link DockerNode} maps onto
	 * the job's failure path.
	 *
	 * @return  the container process exit status.
	 * @throws InterruptedException  If the wait is interrupted.
	 * @throws IOException  If the encoded job could not be delivered to the container,
	 *                      so whatever status it exited with does not describe a run of
	 *                      that job.
	 */
	int awaitExit() throws InterruptedException, IOException;

	/**
	 * Returns the name the container was started with, used to cancel it.
	 *
	 * @return  the container name; never {@code null}.
	 */
	String name();
}
