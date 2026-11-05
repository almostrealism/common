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

import io.flowtree.msg.Connection;
import org.almostrealism.util.Chart;

import java.text.NumberFormat;

/**
 * Renders the HTML status report for a single {@link Node} and owns the
 * display-only state that feeds it: the relay- and connect-probability strip
 * charts and the per-interval probability accumulators sampled by the node's
 * activity thread.
 *
 * <p>Extracted from {@link Node} for the same reason
 * {@link NodeGroupStatusRenderer} was extracted from {@link NodeGroup}: the
 * status page is a cohesive concern that had grown large inside the node, and
 * the accumulators exist solely to drive it. The node samples a probability
 * each activity iteration through {@link #recordRelaySample(double)} and
 * {@link #recordConnectSample(double)}; everything else the report needs is read
 * back through the node's public API.</p>
 *
 * @author Michael Murray
 * @see Node#getStatus(String)
 * @see NodeGroupStatusRenderer
 */
class NodeStatusRenderer {

	/** The node whose state is rendered; read through its public API. */
	private final Node node;

	/** Percentage formatter for the uptime fractions shown in the report. */
	private final NumberFormat pFormat = NumberFormat.getPercentInstance();

	/** Strip charts of relay and connect probability over time. */
	private final Chart relayGraph, connectGraph;

	/**
	 * Running sums of relay- and connect-probability samples accumulated since
	 * the last status snapshot.
	 */
	private double relaySum, connectSum;

	/** Sample counts backing {@link #relaySum} and {@link #connectSum}. */
	private int relayDiv, connectDiv;

	/**
	 * Constructs a renderer bound to the given node, initialising its two
	 * forty-division strip charts.
	 *
	 * @param node the node to render; must not be {@code null}
	 */
	NodeStatusRenderer(Node node) {
		this.node = node;
		this.relayGraph = new Chart();
		this.relayGraph.setDivisions(40);
		this.connectGraph = new Chart();
		this.connectGraph.setDivisions(40);
	}

	/**
	 * Records a relay-probability sample taken by the node's activity thread.
	 *
	 * @param r the probability sampled this iteration
	 */
	void recordRelaySample(double r) {
		this.relaySum += r;
		this.relayDiv++;
	}

	/**
	 * Records a connect-probability sample taken by the node's activity thread.
	 *
	 * @param r the probability sampled this iteration
	 */
	void recordConnectSample(double r) {
		this.connectSum += r;
		this.connectDiv++;
	}

	/**
	 * Builds an HTML status report for the node, using {@code nl} as the
	 * line-break separator between sections (e.g. {@code "<br>\n"} for HTML or
	 * {@code "\n"} for plain text). The report includes uptime fractions, job
	 * counts, communication time, the peer list, and mini strip-chart graphs for
	 * relay and connect probabilities, whose per-interval accumulators are reset
	 * as they are charted.
	 *
	 * @param nl the newline/separator string to use between status lines
	 * @return the HTML status string
	 */
	String getStatus(String nl) {
		StringBuilder buf = new StringBuilder();

		buf.append("<h3>" + node + "</h3>");

		buf.append("<p>");
		buf.append("Sleep time: ");
		buf.append(NodeTimeFormatter.format(node.getSleep()));
		buf.append(nl);

		if (node.isAlive())
			buf.append("Currently alive and ");
		else
			buf.append("Currently dead and ");

		if (node.isWorking())
			buf.append("working." + nl);
		else
			buf.append("not working." + nl);

		double workP = -1.0, comP = -1;
		Client c = Client.getCurrentClient();
		if (c != null) { // TODO  Should be able to do this even without a current client
			double up = c.getServer().getUptime();
			workP = node.getTimeWorked() / up;
			comP = node.getTimeCommunicated() / up;
		}

		buf.append("Worked for " + NodeTimeFormatter.format(node.getTimeWorked()));
		if (workP > 0.0) {
			buf.append(" (");
			buf.append(pFormat.format(workP));
			buf.append(")");
		}
		buf.append(" and completed " + node.getCompletedJobCount() + " jobs");
		if (node.getErrorJobCount() > 0) {
			buf.append(" (");
			buf.append(node.getErrorJobCount());
			buf.append(" errors).");
			buf.append(nl);
		} else {
			buf.append(".");
			buf.append(nl);
		}

		buf.append("Communicated for " + NodeTimeFormatter.format(node.getTimeCommunicated()));
		if (comP > 0.0) {
			buf.append(" (");
			buf.append(pFormat.format(comP));
			buf.append(")");
		}
		buf.append(" and relayed " + node.getRelayedJobCount() + " jobs.");

		buf.append("</p>");

		Connection[] peers = node.getPeers();
		if (peers != null) {
			for (int i = 0; i < peers.length; i++) {
				buf.append("\t <b>Peer " + i + "</b>: " + peers[i].toString() + nl);
			}
		}

		double a = 0.0;
		if (this.relayDiv > 0) {
			a = this.relaySum / this.relayDiv;
			this.relaySum = 0.0;
			this.relayDiv = 0;
		}

		buf.append("<table><tr>");

		this.relayGraph.addEntry(a);
		buf.append("<td><pre>");
		buf.append("<font size=\"-4\">" + nl);
		this.relayGraph.print(buf);
		buf.append("</font></pre></td>");

		a = 0.0;
		if (this.connectDiv > 0) {
			a = this.connectSum / this.connectDiv;
			this.connectSum = 0.0;
			this.connectDiv = 0;
		}

		this.connectGraph.addEntry(a);
		buf.append("<td><pre>");
		buf.append("<font size=\"-4\">" + nl);
		this.connectGraph.print(buf);
		buf.append("</font></pre></td>");

		buf.append("</tr></table>");

		return buf.toString();
	}
}
