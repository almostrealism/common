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

import java.io.IOException;

/**
 * Starts and cancels the containers a {@link DockerNode} runs its jobs in. This is
 * the seam that lets a {@link DockerNode} be exercised without a real container
 * runtime: production uses {@link DockerCliContainerLauncher} (which shells out to
 * the {@code docker} CLI), while tests substitute a stub that records the requests
 * and returns canned output.
 *
 * <p>The launcher owns only the mechanics of the runtime. What crosses the boundary
 * (the encoded job, the per-job transcript directory, the resource limits) is
 * described by the {@link ContainerRequest} the {@link DockerNode} builds; the
 * launcher does not interpret it beyond turning it into runtime invocations.</p>
 *
 * @author  Michael Murray
 * @see DockerNode
 * @see ContainerSession
 */
public interface ContainerLauncher {

	/**
	 * Starts a container for the given request and returns a live
	 * {@link ContainerSession}. The implementation feeds the request's
	 * {@link ContainerRequest#encodedJob() encoded job} to the container's standard
	 * input and starts streaming its output.
	 *
	 * @param request  the container to start; never {@code null}.
	 * @return  the running session; never {@code null}.
	 * @throws IOException  If the container cannot be started.
	 */
	ContainerSession launch(ContainerRequest request) throws IOException;

	/**
	 * Kills and removes the container with the given name, so a cancelled job's
	 * container stops running. A name that names no running container is a no-op,
	 * because the job may already have finished.
	 *
	 * @param containerName  the name of the container to kill; never {@code null}.
	 */
	void kill(String containerName);
}
