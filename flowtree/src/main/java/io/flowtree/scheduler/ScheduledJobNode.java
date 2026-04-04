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

package io.flowtree.scheduler;

import io.flowtree.job.Job;
import io.flowtree.node.Node;
import io.flowtree.node.NodeGroup;
import org.almostrealism.time.Frequency;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A {@link Node} subclass that executes {@link FixedRate} jobs on a fixed
 * time schedule rather than dispatching them from a work queue. When a
 * {@link FixedRate} job is added, it is submitted to an internal
 * {@link ScheduledThreadPoolExecutor} using
 * {@link ScheduledThreadPoolExecutor#scheduleAtFixedRate}; all other job
 * types are forwarded to {@link Node#addJob(Job)}.
 *
 * @author  Michael Murray
 */
public class ScheduledJobNode extends Node {
	/** Thread pool used to schedule {@link FixedRate} jobs at their requested frequency. */
	ScheduledThreadPoolExecutor exec;

	/**
	 * Constructs a new {@link ScheduledJobNode}.
	 *
	 * @param parent   the {@link NodeGroup} this node belongs to
	 * @param id       unique numeric identifier for this node within the group
	 * @param maxJobs  maximum number of concurrently executing jobs (also the
	 *                 thread-pool core size)
	 * @param maxPeers maximum number of peer connections this node will maintain
	 */
	public ScheduledJobNode(NodeGroup parent, int id, int maxJobs, int maxPeers) {
		super(parent, id, maxJobs, maxPeers);
		this.exec = new ScheduledThreadPoolExecutor(maxJobs);
	}

	/**
	 * Adds a job to this node. If the job implements {@link FixedRate} it is
	 * submitted to the internal scheduler using the job's declared frequency;
	 * otherwise the job is handled by the parent {@link Node#addJob(Job)}
	 * implementation.
	 *
	 * @param j  the job to add
	 * @return   the slot index the job was assigned to, or the value returned
	 *           by {@link Node#addJob(Job)} for non-{@link FixedRate} jobs
	 */
	@Override
	public int addJob(Job j) {
		if (j instanceof FixedRate) {
			FixedRate r = (FixedRate) j;
			ScheduledFuture f = exec.scheduleAtFixedRate(j, r.getInitialDelay(),
									(long) (1000.0 / r.getFrequency().asHertz()),
									TimeUnit.MILLISECONDS);
			// TODO  Do something with f
			return getMaxJobs() - 1;
		} else {
			return super.addJob(j);
		}
	}

	/**
	 * Marker interface for {@link Job} implementations that should be executed
	 * at a fixed rate rather than dispatched from the ordinary work queue.
	 */
	public interface FixedRate {
		/**
		 * Returns the frequency at which the job should be repeated.
		 *
		 * @return the target execution frequency
		 */
		Frequency getFrequency();

		/**
		 * Returns the initial delay in milliseconds before the first execution.
		 *
		 * @return initial delay in milliseconds
		 */
		long getInitialDelay();
	}
}
