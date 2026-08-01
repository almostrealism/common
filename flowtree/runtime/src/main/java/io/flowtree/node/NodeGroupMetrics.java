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

package io.flowtree.node;

import io.flowtree.job.Job;
import io.flowtree.msg.NodeProxy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Computes aggregate performance and connectivity metrics over a
 * {@link NodeGroup}'s child {@link Node} collection and peer proxies.
 *
 * <p>This class is a package-private collaborator extracted from
 * {@link NodeGroup} to keep that class within the 1 500-line limit imposed
 * by the project's Checkstyle configuration.
 *
 * <p>All computations read live collections provided at construction time;
 * no results are cached.
 *
 * @author  Michael Murray
 * @see NodeGroup
 */
class NodeGroupMetrics {

	/**
	 * The child {@link Node} collection to aggregate over.  This is the same
	 * live list held by the owning {@link NodeGroup}.
	 */
	private final Collection<Node> nodes;

	/**
	 * Bias offset added to the raw average-activity computation in
	 * {@link #getAverageActivityRating(double)}.  Supplied by the owning
	 * {@link NodeGroup} on each call.
	 */
	// Note: activityOffset is passed as a parameter rather than stored here so
	// that the NodeGroup can mutate it via setActivityOffset() at any time.

	/**
	 * Constructs a metrics calculator for the given node collection.
	 *
	 * @param nodes  The live child-node collection; must not be {@code null}.
	 */
	NodeGroupMetrics(Collection<Node> nodes) {
		this.nodes = nodes;
	}

	/**
	 * Returns the total number of jobs completed by all child nodes combined.
	 *
	 * @return  Sum of {@link Node#getCompletedJobCount()} across all children.
	 */
	int getCompletedJobCount() {
		int t = 0;
		Iterator<Node> itr = nodes.iterator();
		while (itr.hasNext()) t += itr.next().getCompletedJobCount();
		return t;
	}

	/**
	 * Returns the total time all child nodes have spent executing jobs,
	 * in milliseconds, truncated to whole milliseconds.
	 *
	 * @return  Aggregate work time in milliseconds.
	 */
	double getTimeWorked() {
		double t = 0;
		Iterator<Node> itr = nodes.iterator();
		while (itr.hasNext()) t += itr.next().getTimeWorked();
		return t - (t % 1);
	}

	/**
	 * Returns the total time all child nodes have spent on network
	 * communication, in milliseconds, truncated to whole milliseconds.
	 *
	 * @return  Aggregate communication time in milliseconds.
	 */
	double getTimeCommunicated() {
		double t = 0;
		Iterator<Node> itr = nodes.iterator();
		while (itr.hasNext()) t += itr.next().getTimeCommunicated();
		return t - (t % 1);
	}

	/**
	 * Returns the average time for a child node to complete a single job,
	 * in milliseconds.
	 *
	 * @return  Mean job time, or {@code -1.0} if no jobs have been completed.
	 */
	double getAverageJobTime() {
		Iterator<Node> itr = nodes.iterator();
		int count = 0;
		double tot = 0.0;

		while (itr.hasNext()) {
			Node n = itr.next();
			tot += n.getTimeWorked();
			count += n.getCompletedJobCount();
		}

		return count == 0 ? -1.0 : tot / count;
	}

	/**
	 * Returns the mean connectivity rating across all child nodes.
	 *
	 * @return  Average connectivity rating, or {@code 0.0} if there are no children.
	 */
	double getAverageConnectivityRating() {
		Iterator<Node> itr = nodes.iterator();
		int count = 0;
		double tot = 0.0;

		while (itr.hasNext()) {
			tot += itr.next().getConnectivityRating();
			count++;
		}

		return count == 0 ? 0.0 : tot / count;
	}

	/**
	 * Returns the mean activity rating across all child nodes, offset by
	 * {@code activityOffset}.  A negative offset biases the result lower,
	 * making the group appear less busy than the raw average suggests.
	 *
	 * @param activityOffset  The bias applied after computing the raw mean.
	 * @return  Biased average activity rating, or {@code 0.0} if there are
	 *          no child nodes.
	 */
	double getAverageActivityRating(double activityOffset) {
		Iterator<Node> itr = nodes.iterator();
		int count = 0;
		double tot = 0.0;

		while (itr.hasNext()) {
			tot += itr.next().getActivityRating();
			count++;
		}

		return count == 0 ? 0.0 : tot / count + activityOffset;
	}

	/**
	 * Returns the mean activity rating reported by the supplied peer proxies,
	 * considering only those that have reported a positive rating.
	 *
	 * @param proxies  Snapshot of the currently connected {@link NodeProxy} instances.
	 * @return  Mean peer activity rating, or {@code 0.0} if no peer has reported
	 *          a positive rating.
	 */
	static double getAveragePeerActivityRating(NodeProxy[] proxies) {
		double sum = 0.0;
		int peers = 0;

		for (int i = 0; i < proxies.length; i++) {
			double j = proxies[i].getActivityRating();
			if (j > 0) {
				sum += j;
				peers++;
			}
		}

		return peers > 0 ? sum / peers : 0.0;
	}

	/**
	 * Returns the child {@link Node} with the lowest connectivity rating (fewest
	 * active peer connections), or {@code null} if the collection is empty.
	 *
	 * @return  Least-connected node, or {@code null}.
	 */
	Node getLeastConnectedNode() {
		Node result = null;

		synchronized (nodes) {
			Iterator<Node> itr = nodes.iterator();
			while (itr.hasNext()) {
				Node next = itr.next();
				if (result == null || result.getConnectivityRating() > next.getConnectivityRating()) {
					result = next;
				}
			}
		}

		return result;
	}

	/**
	 * Returns the child {@link Node} with the lowest activity rating (least busy).
	 * When several nodes share the minimum rating one is chosen at random.
	 * Returns {@code null} if the collection is empty.
	 *
	 * @return  Least-active node, or {@code null}.
	 */
	Node getLeastActiveNode() {
		List<Node> candidates = new ArrayList<>();
		double rating = -1.0;

		synchronized (nodes) {
			Iterator<Node> itr = nodes.iterator();
			while (itr.hasNext()) {
				Node next = itr.next();
				double a = next.getActivityRating();
				if (rating == -1.0 || rating > a) {
					candidates.clear();
					candidates.add(next);
					rating = a;
				} else if (a == rating) {
					candidates.add(next);
				}
			}
		}

		if (candidates.isEmpty()) return null;
		return candidates.get(Node.random.nextInt(candidates.size()));
	}

	/**
	 * Finds the least-active child {@link Node} whose labels satisfy the
	 * required labels of the given job. Returns {@code null} if no child
	 * qualifies.
	 *
	 * @param j  The job whose required labels must be matched.
	 * @return   The best-matching node, or {@code null}.
	 */
	Node findNodeForJob(Job j) {
		Map<String, String> requirements = j.getRequiredLabels();
		List<Node> candidates = new ArrayList<>();
		double rating = -1.0;

		synchronized (nodes) {
			for (Node n : nodes) {
				if (n.satisfies(requirements)) {
					double a = n.getActivityRating();
					if (rating == -1.0 || rating > a) {
						candidates.clear();
						candidates.add(n);
						rating = a;
					} else if (a == rating) {
						candidates.add(n);
					}
				}
			}
		}

		if (candidates.isEmpty()) return null;
		return candidates.get(Node.random.nextInt(candidates.size()));
	}
}
