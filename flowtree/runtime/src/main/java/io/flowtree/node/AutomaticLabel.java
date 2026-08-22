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

import org.almostrealism.io.ConsoleFeatures;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * A capability label whose value describes the machine a {@link Node} runs on,
 * and can therefore be determined without being configured.
 *
 * <p>Each constant knows how to {@link #detect()} its own value and how to
 * {@link #applyTo(Node)} it, so extending the set of labels a Node advertises
 * about itself means adding a constant here rather than adding a branch
 * wherever labels are applied.</p>
 *
 * <p>Detection never overwrites configuration.  {@link #applyTo(Node)} assigns
 * a value only when the label is absent, which leaves labels supplied through
 * {@code nodes.labels.<key>} or {@code FLOWTREE_NODE_LABELS} in place.  A
 * constant that cannot determine a trustworthy value returns {@code null} from
 * {@link #detect()} and no label is assigned at all; a Node with no value for a
 * label simply never satisfies a job requiring one, which is the safe outcome.</p>
 *
 * @author  Michael Murray
 * @see Node#setLabel(String, String)
 * @see <a href="../docs/node-relay.md">Node Relay and Job Routing</a>
 */
public enum AutomaticLabel implements ConsoleFeatures {
	/**
	 * The operating system family a Node runs on, either {@code macos} or
	 * {@code linux}.  Jobs whose toolchain is platform specific require it.
	 */
	PLATFORM("platform") {
		@Override
		public String detect() {
			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			return os.contains("mac") ? "macos" : "linux";
		}
	},

	/**
	 * The machine a Node runs on, derived from the system host name.
	 *
	 * <p>Some workloads have to run at a particular place on the network rather
	 * than on any machine of a given platform, and the practical way to name
	 * that place is the machine itself.  A job targets one with the requirement
	 * {@code hostname:<name>}.</p>
	 *
	 * <p>The detected name is reduced by {@link #normalize(String)} to the short
	 * form that is ordinarily also the machine's name on the private network
	 * overlay.  When the two differ, the label has to be configured explicitly
	 * rather than detected.</p>
	 */
	HOSTNAME("hostname") {
		@Override
		public String detect() {
			InetAddress local;

			try {
				local = InetAddress.getLocalHost();
			} catch (UnknownHostException e) {
				return null;
			}

			String name = local.getHostName();

			// An unresolved host reports its address as its name, which
			// identifies an interface rather than a machine
			if (name == null || name.equals(local.getHostAddress())) {
				return null;
			}

			return normalize(name);
		}

		/**
		 * Reduces a system host name to its short, lower case form, discarding
		 * any domain suffix so that both {@code Mac-Studio.local} and
		 * {@code mac-studio.example.ts.net} yield {@code mac-studio}.
		 *
		 * <p>Names that do not identify a particular machine
		 * ({@code localhost}) are rejected, since labelling every Node with the
		 * same value would let any of them satisfy a requirement meant for one
		 * of them.</p>
		 *
		 * @param value  The host name to normalize.
		 * @return  The normalized value, or {@code null} if {@code value} does
		 *          not identify a particular machine.
		 */
		@Override
		public String normalize(String value) {
			if (value == null) return null;

			String label = value.trim();
			int dot = label.indexOf('.');
			if (dot >= 0) label = label.substring(0, dot);

			label = label.toLowerCase(Locale.ROOT);

			if (label.isEmpty() || label.equals("localhost")) return null;

			return label;
		}
	};

	/** The label key this constant assigns. */
	private final String key;

	/**
	 * @param key  The label key this constant assigns.
	 */
	AutomaticLabel(String key) {
		this.key = key;
	}

	/**
	 * Returns the label key this constant assigns.
	 *
	 * @return  The label key; never {@code null}.
	 */
	public String key() { return key; }

	/**
	 * Determines the value of this label for the machine the current process
	 * runs on.
	 *
	 * @return  The detected value, or {@code null} if no trustworthy value can
	 *          be determined.
	 */
	public abstract String detect();

	/**
	 * Reduces a detected value to the form used as a label.  The default
	 * implementation returns the value unchanged.
	 *
	 * @param value  The value to normalize.
	 * @return  The normalized value, or {@code null} if {@code value} is not
	 *          usable as a label.
	 */
	public String normalize(String value) { return value; }

	/**
	 * Assigns this label to the given {@link Node} unless it already has a
	 * value for it, leaving any configured value in place.
	 *
	 * <p>A {@link NodeGroup} propagates the assignment to its child Nodes, so
	 * applying a label to a group labels the whole group.</p>
	 *
	 * @param node  The Node to label.
	 */
	public void applyTo(Node node) {
		if (node.getLabels().get(key) != null) return;

		String value = detect();

		if (value == null) {
			log("Unable to determine " + key + " for this machine; "
					+ "jobs requiring one will not run here");
			return;
		}

		node.setLabel(key, value);
		log("Auto-detected " + key + "=" + value);
	}
}
