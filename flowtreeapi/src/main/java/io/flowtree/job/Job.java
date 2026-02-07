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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Implementations of the Job interface represent jobs that can be processed
 * by nodes and encoded to transmit to remote nodes.
 * 
 * @author  Michael Murray
 */
public interface Job extends Runnable, KeyValueStore {
	/**
	 * @return  A network wide unique id for the task (JobFactory) this Job is associated with.
	 *          This value should be included in the string produced by the encode method
	 *          and the set method should set the id.
	 */
	String getTaskId();
	
	/**
	 * @return  A string describing the task (JobFactory) this Job is associated with.
	 */
	String getTaskString();

	/**
	 * Provides an {@link ExecutorService} which is to be used when parallelism is
	 * required by the {@link Job}.
	 */
	default void setExecutorService(ExecutorService executor) {
	}

	/**
	 * @return  A {@link CompletableFuture} that is complete when the {@link Job} is complete.
	 */
	CompletableFuture<Void> getCompletableFuture();

	/**
	 * Provides a {@link Consumer} that can optionally be used to
	 * process the result of this {@link Job}.
	 */
	default void setOutputConsumer(Consumer<JobOutput> outputConsumer) {
	}
}
