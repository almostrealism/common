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

import io.flowtree.fs.OutputServer;
import io.flowtree.job.JobFactory;
import io.flowtree.msg.Message;
import io.flowtree.msg.NodeProxy;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.util.Chart;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/**
 * Renders HTML status reports for a {@link NodeGroup} and manages the
 * activity and throughput time-series charts used by those reports.
 *
 * <p>This class is a package-private collaborator extracted from
 * {@link NodeGroup} to keep that class within the 1 500-line limit imposed
 * by the project's Checkstyle configuration.
 *
 * <p>The status report covers:
 * <ul>
 *   <li>Current server connections and registered tasks.</li>
 *   <li>Per-node status sections delegated back to {@link Node#getStatus(String)}.</li>
 *   <li>Activity-rating and sleep-time trend charts sampled by the monitor thread.</li>
 *   <li>Output-server (DBS) throughput metrics, sampled every {@code tpFreq} calls.</li>
 * </ul>
 *
 * @author  Michael Murray
 * @see NodeGroup
 */
class NodeGroupStatusRenderer implements ConsoleFeatures {

	/**
	 * Format used to print floating-point throughput and job-time metrics in the
	 * status page.
	 */
	private static final DecimalFormat D_FORMAT = new DecimalFormat("0.000");

	/**
	 * The {@link NodeGroup} whose state is rendered. All data is read through
	 * the group's public or package-private API.
	 */
	private final NodeGroup group;

	/**
	 * Time-series chart of the group's average activity rating, sampled once
	 * per status poll.
	 */
	private final Chart activityGraph;

	/**
	 * Time-series chart of {@link OutputServer} job throughput, sampled every
	 * {@link #tpFreq} status polls to avoid over-weighting short bursts.
	 */
	private final Chart throughputGraph;

	/**
	 * Throughput sampling interval: one throughput sample is taken every
	 * {@code tpFreq} calls to {@link #getStatus(String)}.
	 */
	private final int tpFreq;

	/**
	 * Counter tracking the current position within the {@link #tpFreq} sampling
	 * interval for throughput measurements.
	 */
	private int tpLast;

	/**
	 * Accumulator for activity ratings gathered since the last status poll, used
	 * to compute the interval average displayed on the status page.
	 */
	private double activitySum;

	/**
	 * Lifetime accumulator for all activity rating samples ever recorded, used
	 * to compute the running-total average shown in the status page.
	 */
	private double totalActivitySum;

	/**
	 * Number of samples added to {@link #activitySum} since the last status poll.
	 * Reset to zero after each poll.
	 */
	private int activityDivisor;

	/**
	 * Lifetime count of all activity rating samples ever added to
	 * {@link #totalActivitySum}.
	 */
	private int totalActivityDiv;

	/**
	 * Constructs a renderer bound to the given {@link NodeGroup}.
	 *
	 * @param group          The owning group; must not be {@code null}.
	 * @param activityGraph  Pre-constructed activity chart, or {@code null} to
	 *                       skip activity charting.
	 * @param tpFreq         Throughput sampling interval (polls between samples).
	 */
	NodeGroupStatusRenderer(NodeGroup group, Chart activityGraph, int tpFreq) {
		this.group = group;
		this.activityGraph = activityGraph;
		this.throughputGraph = new Chart();
		this.tpFreq = tpFreq;
		this.tpLast = 0;
	}

	/**
	 * Records a monitor-thread sample of the group's current activity rating.
	 * Called periodically by the background monitor thread inside
	 * {@link NodeGroup} so that status polls report meaningful interval averages
	 * even when no poll has occurred recently.
	 *
	 * @param activityRating  The instantaneous average activity rating to record.
	 */
	void recordActivitySample(double activityRating) {
		this.activitySum += activityRating;
		this.totalActivitySum += activityRating;
		this.activityDivisor++;
		this.totalActivityDiv++;
	}

	/**
	 * Adds a message annotation to the activity chart. Used by {@link NodeGroup}
	 * to mark configuration events (e.g. task additions, parameter changes) on
	 * the timeline.
	 *
	 * @param msg  The annotation text to add.
	 */
	void addActivityMessage(String msg) {
		if (this.activityGraph != null) {
			this.activityGraph.addMessage(msg);
		}
	}

	/**
	 * Returns the underlying activity {@link Chart}, or {@code null} if activity
	 * charting was not enabled at construction time.
	 *
	 * @return  The activity chart, or {@code null}.
	 */
	Chart getActivityGraph() {
		return this.activityGraph;
	}

	/**
	 * Prints a minimal HTML page wrapping the output of {@link #getStatus(String)}
	 * to the supplied stream.
	 *
	 * @param out  Stream to write to; typically {@link System#out}.
	 */
	void printStatus(PrintStream out) {
		out.println("<html>");
		out.println("<head><title>");
		out.println("Node Group Status");
		out.println("</title></head><body>");
		out.println(this.getStatus("<br>\n"));
		out.println("</body></html>");
	}

	/**
	 * Builds and returns a full HTML status string for the owning
	 * {@link NodeGroup}, including server connections, registered tasks, per-node
	 * status sections, activity and sleep-time charts, and DBS throughput metrics.
	 *
	 * @param nl  Newline token inserted between logical sections (typically
	 *            {@code "<br>\n"} for HTML output).
	 * @return    The rendered HTML status string; never {@code null}.
	 */
	String getStatus(String nl) {
		if (Message.verbose) log("NodeGroup: Starting status check.");

		StringBuilder buf = new StringBuilder();

		Date now = new Date();

		buf.append(now + nl + nl);

		buf.append("<center><h1>Network Node Group Status</h1>");
		buf.append("<p><h3>" + group + "</h3>" + nl);
		buf.append("<b>Sleep time:</b> " + NodeTimeFormatter.format(group.getSleep()) + "</p></center>" + nl);

		NodeProxy[] s = group.getServers();
		if (Message.verbose) log("NodeGroup.getStatus: Got server list.");

		buf.append("<table><tr><td><h3>Servers</h3></td><td><h3>TaskList</h3></td></tr><tr>");

		buf.append("<td>");

		for (int i = 0; i < s.length; i++) {
			buf.append("\t" + s[i].toString(true) + nl);
		}

		buf.append("</td><td>");

		List<JobFactory> tasksCopy = group.getTasksCopy();
		Iterator<JobFactory> itr = tasksCopy.iterator();
		while (itr.hasNext()) {
			buf.append("\t" + itr.next().getName() + nl);
		}

		buf.append("</td></tr></table>");

		for (Node n : group.nodes()) {
			buf.append(n.getStatus(nl));
		}

		buf.append(nl);

		if (this.activityGraph != null) {
			double a = 0.0;

			if (this.activityDivisor > 0) {
				a = this.activitySum / this.activityDivisor;
				this.activitySum = 0.0;
				this.activityDivisor = 0;
			} else {
				a = group.getActivityRating();
			}

			this.activityGraph.addEntry(a);
		}

		Chart sleepGraph = group.getSleepGraph();
		if (sleepGraph != null) {
			double sl = 0.0;

			if (group.getSleepDiv() > 0) {
				sl = group.getSleepSum() / group.getSleepDiv();
				group.resetSleepMetrics();
			} else {
				sl = group.getSleep();
			}

			sleepGraph.addEntry(sl);
		}

		if (this.activityGraph != null) {
			buf.append("<b>Activity Rating</b>" + nl);
			buf.append("Running Total Average = ");
			if (this.totalActivityDiv > 0) {
				buf.append(this.totalActivitySum / this.totalActivityDiv);
			} else {
				buf.append(0.0);
			}
			buf.append(nl);
			buf.append("<pre><font size=\"-2\">" + nl);
			this.activityGraph.print(buf);
			buf.append("</font></pre>" + nl);
		}

		if (Message.verbose) log("NodeGroup: Getting dbs info...");

		OutputServer dbs = OutputServer.getCurrentServer();
		if (dbs != null) {
			if (this.tpLast % this.tpFreq == 0) {
				this.throughputGraph.addEntry(dbs.getThroughput());
				this.tpLast = 1;
			} else {
				this.tpLast++;
			}

			synchronized (dbs) {
				buf.append("<b>DBS Throughput</b>" + nl);
				buf.append("Running Total Average = ");
				buf.append(D_FORMAT.format(dbs.getTotalAverageThroughput()));
				buf.append(" jobs per minute.");
				buf.append(nl);
				buf.append("Average Job Time = ");
				buf.append(D_FORMAT.format(dbs.getTotalAverageJobTime() / 60000.0));
				buf.append(" minutes per job.");
				buf.append(nl);
				buf.append("<pre><font size=\"-2\">" + nl);
				this.throughputGraph.print(buf);
				buf.append("</font></pre>" + nl);
			}
		}

		if (Message.verbose) log("NodeGroup: Returning status check.");

		return buf.toString();
	}

	/**
	 * Persists the activity-rating time-series to a file.
	 *
	 * @param f  File to write; the format is newline-separated decimal values.
	 * @return   {@code true} if the file was written; {@code false} if activity
	 *           charting was not enabled at construction time.
	 * @throws IOException  If an I/O error occurs while writing.
	 */
	boolean storeActivityGraph(File f) throws IOException {
		if (this.activityGraph != null) {
			this.activityGraph.storeValues(f);
			return true;
		}

		return false;
	}
}
