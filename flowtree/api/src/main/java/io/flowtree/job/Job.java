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

	/**
	 * Returns the id of the wire format this job should be serialized with. The
	 * default is {@link StringJobCodec#FORMAT_ID}, so every job stays on the
	 * historic {@code classname::key:=value} format unless it overrides this to
	 * select another registered {@link JobCodec}.
	 *
	 * <p>This is the <em>sender's</em> choice and is never itself serialized.</p>
	 *
	 * @return the wire format id; {@link StringJobCodec#FORMAT_ID} by default
	 */
	default String getWireFormat() {
		return StringJobCodec.FORMAT_ID;
	}

	/**
	 * Serializes this job for transmission, selecting the {@link JobCodec} named
	 * by {@link #getWireFormat()}.
	 *
	 * <p>When that codec is the {@link StringJobCodec} the result is
	 * {@link #encode()} unchanged, so an unmarked string payload is byte-identical
	 * to the historic form. Otherwise the result is a {@link WireEnvelope}
	 * carrying the format id, this job's class name and the codec payload, so a
	 * receiver can pick the right codec from the payload alone.</p>
	 *
	 * @return the serialized wire payload
	 * @throws UnsupportedWireFormatException if the chosen codec does not
	 *                                        {@link JobCodec#supports(Class) support} this class
	 */
	default String toWire() {
		JobCodec codec = JobCodecRegistry.getInstance().forFormat(getWireFormat());
		return codec.renderWire(getClass(), codec.encodePayload(this));
	}

	/**
	 * Reconstructs a {@link Job} from its serialized wire form. This is the
	 * single job-side decoder, the counterpart to {@link JobFactory#decode(String)}:
	 * it parses the {@link WireEnvelope} format marker, selects the
	 * {@link JobCodec} for that format, verifies the resolved class is a
	 * {@link Job} the codec {@link JobCodec#supports(Class) supports}, and
	 * delegates the payload parsing to the codec. An unmarked payload is the
	 * historic {@code classname::key:=value} string form, decoded by the
	 * {@link StringJobCodec}.
	 *
	 * <p>Returns {@code null} — never a misparsed job — when the payload is
	 * {@code null}, carries a structurally malformed {@link WireEnvelope} format
	 * marker, carries no class name, names an unsupported wire format, names a
	 * class that cannot be found or is not a {@link Job}, or names a class the
	 * selected codec does not support. A payload with no
	 * {@link JobFactory#ENTRY_SEPARATOR} (which the historic inline parser
	 * crashed on) is one such case and now yields {@code null}. The resolution
	 * and checks are shared with {@link JobFactory#decode(String)} through
	 * {@link JobCodecRegistry#decode(Class, String, JobCodecRegistry.PayloadDecoder)}.</p>
	 *
	 * <p>Unlike {@link JobFactory#decode(String)}, a string-format payload is
	 * decoded leniently: {@link StringJobCodec#decodeJob(Class, String)} keeps the
	 * historic behaviour of returning a job that is only partially configured
	 * when a {@link #set(String, String)} call throws part-way through the
	 * entries. Codecs other than the string codec never return a partially
	 * decoded job (see {@link JobCodec}).</p>
	 *
	 * @param data the serialized wire payload, optionally preceded by a
	 *             {@link WireEnvelope} format marker
	 * @return the reconstructed job, or {@code null} if it cannot be decoded
	 */
	static Job decode(String data) {
		return JobCodecRegistry.getInstance().<Job>decode(Job.class, data, JobCodec::decodeJob);
	}
}
