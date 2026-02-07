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

public class ScheduledJobNode extends Node {
	ScheduledThreadPoolExecutor exec;

	public ScheduledJobNode(NodeGroup parent, int id, int maxJobs, int maxPeers) {
		super(parent, id, maxJobs, maxPeers);
		this.exec = new ScheduledThreadPoolExecutor(maxJobs);
	}

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

	public interface FixedRate {
		Frequency getFrequency();
		long getInitialDelay();
	}
}
