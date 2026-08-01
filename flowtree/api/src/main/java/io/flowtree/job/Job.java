/*
 * Copyright 2018 Michael Murray
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

package io.flowtree.job;

import org.almostrealism.io.JobOutput;
import org.almostrealism.util.KeyValueStore;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Represents a single executable unit of work within the FlowTree distributed
 * workflow orchestration system.
 *
 * <p>A {@link Job} is both {@link Runnable} — its {@link #run()} method performs the
 * actual work — and a {@link KeyValueStore}, allowing its state to be encoded as a
 * string and reconstructed on any node in the cluster. This dual nature supports
 * transparent distribution: a job created on one node can be serialized, transmitted
 * over the wire, and executed on a remote node without any shared class registry.</p>
 *
 * <h2>Implementation Contract</h2>
 * <ul>
 *   <li>{@link #run()} must complete the work and mark the future returned by
 *       {@link #getCompletableFuture()} as done when finished.</li>
 *   <li>{@link #encode()} (inherited from {@link KeyValueStore}) must include the
 *       task ID so that the receiving node can associate the job with the correct
 *       {@link JobFactory}.</li>
 *   <li>{@link #set(String, String)} must restore all fields that {@link #encode()}
 *       serializes, including the task ID.</li>
 * </ul>
 *
 * @author  Michael Murray
 * @see JobFactory
 * @see org.almostrealism.util.KeyValueStore
 */
public interface Job extends Runnable, KeyValueStore {
	/**
	 * Returns the network-wide unique identifier for the task ({@link JobFactory})
	 * that this {@link Job} belongs to.
	 *
	 * <p>This value must be included in the string produced by {@link #encode()} and
	 * must be restored by {@link #set(String, String)} so that remote nodes can
	 * correctly associate a decoded job with its originating factory.</p>
	 *
	 * @return the task ID for the owning {@link JobFactory}
	 */
	String getTaskId();

	/**
	 * Returns a human-readable description of the task ({@link JobFactory}) that
	 * this {@link Job} is associated with.
	 *
	 * @return a descriptive string identifying the owning task
	 */
	String getTaskString();

	/**
	 * Provides an {@link ExecutorService} to be used when the {@link Job} requires
	 * internal parallelism during execution.
	 *
	 * <p>The default implementation is a no-op; implementations that perform
	 * parallel work should override this to accept and use the provided executor
	 * rather than creating their own threads.</p>
	 *
	 * @param executor the executor service to use for parallel sub-tasks
	 */
	default void setExecutorService(ExecutorService executor) {
	}

	/**
	 * Returns a {@link CompletableFuture} that will be completed when this
	 * {@link Job} finishes executing.
	 *
	 * <p>Callers may use this future to await job completion or chain follow-up
	 * actions. Implementations are responsible for completing this future at the
	 * end of {@link #run()}, even in error cases.</p>
	 *
	 * @return the future representing the completion of this job
	 */
	CompletableFuture<Void> getCompletableFuture();

	/**
	 * Supplies a {@link Consumer} that receives the {@link JobOutput} result
	 * once this {@link Job} completes.
	 *
	 * <p>The default implementation is a no-op. Implementations that produce
	 * output should override this to capture the consumer and invoke it at the
	 * end of {@link #run()}.</p>
	 *
	 * @param outputConsumer the consumer to receive the job's output
	 */
	default void setOutputConsumer(Consumer<JobOutput> outputConsumer) {
	}

	/**
	 * Returns the labels that a Node must possess in order to execute this
	 * {@link Job}.
	 *
	 * <p>A Node will only execute this Job if its own label set contains every
	 * key-value entry in the returned map. An empty map (the default) indicates
	 * that the Job can run on any Node, with no label requirements.</p>
	 *
	 * <p>Label keys and values are arbitrary strings; by convention the key
	 * {@code "platform"} is used to target jobs to specific operating systems
	 * (e.g., {@code "macos"}) and {@code "role"} to target nodes with a
	 * specific function.</p>
	 *
	 * @return an unmodifiable map of required label key-value pairs; never {@code null}
	 */
	default Map<String, String> getRequiredLabels() {
		return Collections.emptyMap();
	}
}
