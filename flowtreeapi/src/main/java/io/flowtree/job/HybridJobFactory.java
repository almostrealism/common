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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * @author  Michael Murray
 *
 */
public class HybridJobFactory extends HashSet<JobFactory> implements JobFactory {

	/**
	 * @see io.flowtree.job.JobFactory#getTaskId()
	 */
	@Override
	public String getTaskId() {
		// TODO Auto-generated method stub
		return "";
	}

	/**
	 * @see io.flowtree.job.JobFactory#nextJob()
	 */
	@Override
	public Job nextJob() {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * @see io.flowtree.job.JobFactory#createJob(java.lang.String)
	 */
	@Override
	public Job createJob(String data) {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * @see io.flowtree.job.JobFactory#set(java.lang.String, java.lang.String)
	 */
	@Override
	public void set(String key, String value) {
		// TODO Auto-generated method stub

	}

	/**
	 * @see io.flowtree.job.JobFactory#encode()
	 */
	@Override
	public String encode() {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * @see io.flowtree.job.JobFactory#getName()
	 */
	@Override
	public String getName() {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * @see io.flowtree.job.JobFactory#getCompleteness()
	 */
	@Override
	public double getCompleteness() {
		// TODO Auto-generated method stub
		return 0;
	}

	/**
	 * @see io.flowtree.job.JobFactory#isComplete()
	 */
	@Override
	public boolean isComplete() {
		// TODO Auto-generated method stub
		return false;
	}

	/**
	 * @see io.flowtree.job.JobFactory#setPriority(double)
	 */
	@Override
	public void setPriority(double p) {
		// TODO Auto-generated method stub

	}

	/**
	 * @see io.flowtree.job.JobFactory#getPriority()
	 */
	@Override
	public double getPriority() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public CompletableFuture<Void> getCompletableFuture() {
		List<CompletableFuture> futures = new ArrayList<>();
		stream().map(f -> f.getCompletableFuture()).forEach(f -> futures.add(f));
		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
	}
}
