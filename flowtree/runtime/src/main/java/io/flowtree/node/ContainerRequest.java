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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable description of the container a {@link DockerNode} wants a
 * {@link ContainerLauncher} to start for one job: the image and command to run, the
 * resource limits, the environment and read-only mounts to inject, the single
 * writable per-job transcript mount, and the encoded job to feed to the container's
 * standard input.
 *
 * <p>The request carries only values that are safe to repeat per job and that keep
 * each container isolated from the next: a distinct {@link #transcriptHostDir()} and
 * a distinct {@link #name()} per job, and no shared writable mount. Cross-container
 * isolation is a property of what this request contains, not of how the launcher
 * runs it.</p>
 *
 * @author  Michael Murray
 * @see DockerNode
 * @see ContainerLauncher
 */
public class ContainerRequest {

	/** The unique container name, used both to start and to cancel the container. */
	private final String name;

	/** The container image the worker runs from. */
	private final String image;

	/** The CPU allotment (a {@code --cpus} value), or {@code null} to leave unbounded. */
	private final String cpus;

	/** The memory allotment (a {@code --memory} value), or {@code null} to leave unbounded. */
	private final String memory;

	/** Environment variables injected into the container; never {@code null}. */
	private final Map<String, String> env;

	/** Host-to-container read-only mounts; never {@code null}. */
	private final Map<String, String> readOnlyMounts;

	/** The per-job host directory bound as the writable transcript mount, or {@code null}. */
	private final String transcriptHostDir;

	/** The in-container path the transcript directory is mounted at. */
	private final String transcriptMountPath;

	/** The command the container runs (the worker main invocation); never {@code null}. */
	private final List<String> command;

	/** The encoded job fed to the container's standard input; never {@code null}. */
	private final String encodedJob;

	/**
	 * @param builder  the builder whose accumulated values this request copies.
	 */
	private ContainerRequest(Builder builder) {
		this.name = builder.name;
		this.image = builder.image;
		this.cpus = builder.cpus;
		this.memory = builder.memory;
		this.env = Collections.unmodifiableMap(new LinkedHashMap<>(builder.env));
		this.readOnlyMounts = Collections.unmodifiableMap(new LinkedHashMap<>(builder.readOnlyMounts));
		this.transcriptHostDir = builder.transcriptHostDir;
		this.transcriptMountPath = builder.transcriptMountPath;
		this.command = List.copyOf(builder.command);
		this.encodedJob = builder.encodedJob;
	}

	/**
	 * Returns the unique container name.
	 *
	 * @return  the container name.
	 */
	public String name() {
		return name;
	}

	/**
	 * Returns the container image the worker runs from.
	 *
	 * @return  the container image.
	 */
	public String image() {
		return image;
	}

	/**
	 * Returns the CPU allotment passed to the runtime.
	 *
	 * @return  the CPU allotment, or {@code null} to leave it unbounded.
	 */
	public String cpus() {
		return cpus;
	}

	/**
	 * Returns the memory allotment passed to the runtime.
	 *
	 * @return  the memory allotment, or {@code null} to leave it unbounded.
	 */
	public String memory() {
		return memory;
	}

	/**
	 * Returns the environment injected into the container.
	 *
	 * @return  the injected environment; never {@code null}.
	 */
	public Map<String, String> env() {
		return env;
	}

	/**
	 * Returns the host-to-container read-only mounts.
	 *
	 * @return  the read-only mounts; never {@code null}.
	 */
	public Map<String, String> readOnlyMounts() {
		return readOnlyMounts;
	}

	/**
	 * Returns the per-job writable transcript host directory.
	 *
	 * @return  the transcript host directory, or {@code null}.
	 */
	public String transcriptHostDir() {
		return transcriptHostDir;
	}

	/**
	 * Returns the in-container transcript mount path.
	 *
	 * @return  the transcript mount path.
	 */
	public String transcriptMountPath() {
		return transcriptMountPath;
	}

	/**
	 * Returns the complete command the container runs. Its first element is the
	 * executable, which replaces whatever entrypoint the image declares, so the
	 * container runs exactly this process and nothing the image would otherwise start.
	 *
	 * @return  the command; never {@code null}.
	 */
	public List<String> command() {
		return command;
	}

	/**
	 * Returns the encoded job fed to the container's standard input.
	 *
	 * @return  the encoded job.
	 */
	public String encodedJob() {
		return encodedJob;
	}

	/**
	 * A builder for {@link ContainerRequest}. The container name, image, command and
	 * encoded job are required; the resource limits, environment, mounts and
	 * transcript directory are optional.
	 */
	public static class Builder {

		/** The unique container name. */
		private final String name;

		/** The container image. */
		private final String image;

		/** The CPU allotment, or {@code null} to leave it unbounded. */
		private String cpus;

		/** The memory allotment, or {@code null} to leave it unbounded. */
		private String memory;

		/** Environment variables to inject into the container. */
		private final Map<String, String> env = new LinkedHashMap<>();

		/** Host-to-container read-only mounts. */
		private final Map<String, String> readOnlyMounts = new LinkedHashMap<>();

		/** The per-job writable transcript host directory, or {@code null}. */
		private String transcriptHostDir;

		/** The in-container transcript mount path, or {@code null}. */
		private String transcriptMountPath;

		/** The command the container runs. */
		private List<String> command = List.of();

		/** The encoded job fed to the container's standard input. */
		private String encodedJob = "";

		/**
		 * @param name   the unique container name; must not be {@code null}.
		 * @param image  the container image; must not be {@code null}.
		 */
		public Builder(String name, String image) {
			this.name = name;
			this.image = image;
		}

		/**
		 * Sets the CPU allotment.
		 *
		 * @param cpus  the CPU allotment, or {@code null} to leave it unbounded.
		 * @return  this builder.
		 */
		public Builder cpus(String cpus) {
			this.cpus = cpus;
			return this;
		}

		/**
		 * Sets the memory allotment.
		 *
		 * @param memory  the memory allotment, or {@code null} to leave it unbounded.
		 * @return  this builder.
		 */
		public Builder memory(String memory) {
			this.memory = memory;
			return this;
		}

		/**
		 * Injects an environment variable into the container.
		 *
		 * @param key    the variable name.
		 * @param value  the variable value.
		 * @return  this builder.
		 */
		public Builder env(String key, String value) {
			this.env.put(key, value);
			return this;
		}

		/**
		 * Adds a host-to-container read-only mount.
		 *
		 * @param hostPath       the host path.
		 * @param containerPath  the in-container mount point.
		 * @return  this builder.
		 */
		public Builder readOnlyMount(String hostPath, String containerPath) {
			this.readOnlyMounts.put(hostPath, containerPath);
			return this;
		}

		/**
		 * Binds the per-job writable transcript directory.
		 *
		 * @param hostDir    the per-job host directory.
		 * @param mountPath  the in-container mount point.
		 * @return  this builder.
		 */
		public Builder transcript(String hostDir, String mountPath) {
			this.transcriptHostDir = hostDir;
			this.transcriptMountPath = mountPath;
			return this;
		}

		/**
		 * Sets the complete command the container runs; its first element replaces the
		 * image's entrypoint.
		 *
		 * @param command  the command the container runs; must not be {@code null}.
		 * @return  this builder.
		 */
		public Builder command(List<String> command) {
			this.command = command;
			return this;
		}

		/**
		 * Sets the encoded job fed to the container's standard input.
		 *
		 * @param encodedJob  the encoded job fed to the container's standard input.
		 * @return  this builder.
		 */
		public Builder encodedJob(String encodedJob) {
			this.encodedJob = encodedJob;
			return this;
		}

		/**
		 * Builds the immutable request.
		 *
		 * @return  the built, immutable request.
		 */
		public ContainerRequest build() {
			return new ContainerRequest(this);
		}
	}
}
