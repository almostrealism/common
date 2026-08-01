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

package io.flowtree.job;

import org.almostrealism.io.JobOutput;
import org.almostrealism.io.OutputHandler;

import java.util.concurrent.CompletableFuture;

/**
 * Produces a queue of {@link Job}s for a named task and handles their
 * serialization for distribution across the FlowTree node cluster.
 *
 * <p>A {@link JobFactory} represents a task — a logical unit of work that may
 * decompose into many individual {@link Job} instances. Nodes in the cluster pull
 * jobs from the factory's queue via {@link #nextJob()} and execute them. When the
 * factory is transmitted to a remote node, the receiving node calls
 * {@link #createJob(String)} to reconstruct individual {@link Job} objects from
 * their encoded string representations.</p>
 *
 * <h2>Implementation Contract</h2>
 * <ul>
 *   <li>{@link #encode()} must produce a string of the form
 *       {@code classname::key0:=value0::key1:=value1...} sufficient to reconstruct
 *       the factory's state via repeated {@link #set(String, String)} calls.</li>
 *   <li>{@link #nextJob()} returns {@code null} when no more jobs are available.</li>
 *   <li>{@link #isComplete()} returns {@code true} once {@link #getCompleteness()}
 *       reaches {@code 1.0}.</li>
 *   <li>Implementations must complete the future returned by
 *       {@link #getCompletableFuture()} when all jobs have finished.</li>
 * </ul>
 *
 * @author  Michael Murray
 * @see Job
 * @see AbstractJobFactory
 */
public interface JobFactory {
    /**
     * The separator token used between encoded entries in the serialized form of a
     * {@link JobFactory} or {@link org.almostrealism.io.JobOutput}.
     *
     * <p>Value is {@code "::"}, inherited from {@link JobOutput#ENTRY_SEPARATOR}.</p>
     */
    String ENTRY_SEPARATOR = JobOutput.ENTRY_SEPARATOR;

    /**
     * The separator token used between a key and its value in the encoded form
     * of a {@link JobFactory}.
     *
     * <p>Value is {@code ":="}.</p>
     */
    String KEY_VALUE_SEPARATOR = ":=";

    /**
     * Returns the network-wide unique identifier for the task represented by
     * this factory.
     *
     * <p>This ID ties individual {@link Job} instances back to their originating
     * factory so that output can be routed correctly throughout the cluster.</p>
     *
     * @return the task identifier; must be unique across the network
     */
	String getTaskId();
	
    /**
     * Returns the next job in the queue.
     *
     * @return  Next job in the queue.
     */
    Job nextJob();
    
    /**
     * This should probably return net.sf.j3d.network.Server.instantiateJobClass(data)
     * to decode a Job object properly, but the stub is left open for other implementations
     * of the encoding.
     * 
     * @return  A Job object using the specified string parameter.
     */
    Job createJob(String data);
    
    /**
     * Sets a property of this JobFactory object. Any JobFactory object that is to be
     * transmitted between network nodes will have this method called when it arrives at
     * a new host to initialize its variables based on the string returned by the encode
     * method.
     * 
     * @param key  Property name.
     * @param value  Property value.
     */
    void set(String key, String value);
    
    /**
     * The encode method must return a string of the form:
     * "classname:key0=value0:key1=value1:key2=value2..."
     * Where classname is the name of the class that is implementing JobFactory, and the
     * key=value pairs are pairs of keys and values that will be passed to the set method
     * of the class to initialize the state of the object after it has been transmitted
     * from one node to another.
     * 
     * @return  A String representation of this JobFactory object.
     */
    String encode();
    
    /**
     * Returns a human-readable name for the task represented by this factory.
     *
     * <p>The name is used for logging and monitoring purposes and does not need
     * to be unique across the network.</p>
     *
     * @return a descriptive name for this task; may be {@code null} if unset
     */
    String getName();

    /**
     * Returns the fraction of this task that has been completed so far.
     *
     * <p>The value progresses from {@code 0.0} (not started) to {@code 1.0}
     * (fully complete). Once this method returns {@code 1.0}, {@link #isComplete()}
     * must also return {@code true} and {@link #nextJob()} must return {@code null}.</p>
     *
     * @return a value in the range {@code [0.0, 1.0]} representing the fraction complete
     */
    double getCompleteness();

    /**
     * Returns whether this factory has finished producing jobs.
     *
     * <p>Implementations should return {@code true} when {@link #getCompleteness()}
     * reaches {@code 1.0}. Once complete, {@link #nextJob()} must not return any
     * further jobs and the future from {@link #getCompletableFuture()} should be done.</p>
     *
     * @return {@code true} if no more jobs will be produced; {@code false} otherwise
     */
    boolean isComplete();

    /**
     * Sets the scheduling priority of this task.
     *
     * <p>Higher-priority tasks should be scheduled before lower-priority ones by
     * node queue implementations. By convention, {@code 1.0} is the default priority.</p>
     *
     * @param p the new priority value; higher values indicate higher priority
     */
    void setPriority(double p);

    /**
     * Returns the scheduling priority of this task.
     *
     * <p>By convention, the default priority is {@code 1.0}. Higher values indicate
     * higher priority and may cause this task to be scheduled before others.</p>
     *
     * @return the priority of this task
     */
    double getPriority();

    /**
     * Returns a {@link CompletableFuture} that is completed when this entire task
     * finishes — that is, when all jobs produced by this factory have been executed.
     *
     * <p>Callers may use this future to await task-wide completion or chain
     * follow-up actions. Implementations must ensure this future is eventually
     * completed, even in error cases.</p>
     *
     * @return the future representing completion of the full task
     */
    CompletableFuture<Void> getCompletableFuture();

    /**
     * Returns the {@link OutputHandler} that should receive results produced by
     * jobs from this factory, or {@code null} if no handler is configured.
     *
     * <p>The default implementation returns {@code null}. Implementations that
     * need to persist or forward job output should override this method to return
     * an appropriate handler.</p>
     *
     * @return the output handler for this task, or {@code null}
     */
    default OutputHandler getOutputHandler() {
        return null;
    }
}
