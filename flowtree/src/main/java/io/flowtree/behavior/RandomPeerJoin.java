/*
 * Copyright 2016 Michael Murray
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

package io.flowtree.behavior;

import io.flowtree.Server;
import io.flowtree.node.Node;

import java.io.IOException;
import java.io.PrintStream;

/**
 * A {@link ServerBehavior} that expands the local server's peer set by
 * randomly selecting and joining a node from a randomly chosen peer's peer
 * list, and then dropping the original peer connection.
 *
 * <p>The algorithm is:
 * <ol>
 *   <li>Pick a random peer index {@code i} from the local server's current
 *       peer array.</li>
 *   <li>Retrieve the peer list of peer {@code i}.</li>
 *   <li>Pick a random entry {@code j} from that list.</li>
 *   <li>Attempt to open a new connection to the host at index {@code j}.</li>
 *   <li>If successful, close the connection to peer {@code i} to maintain a
 *       stable total peer count.</li>
 * </ol>
 *
 * <p>This behavior implements a simplified peer-graph random walk suitable for
 * bootstrapping connectivity in a freshly started FlowTree cluster.
 *
 * @author  Michael Murray
 */
public class RandomPeerJoin implements ServerBehavior {

	/**
	 * Performs the random peer join operation against the given server.
	 * Diagnostic messages including the chosen peer, attempted host, and any
	 * error details are written to {@code out}.
	 *
	 * @param s   the {@link Server} whose peer connections should be modified
	 * @param out print stream for diagnostic and status messages
	 */
	@Override
	public void behave(Server s, PrintStream out) {
		try {
			int i = Node.random.nextInt(s.getPeers().length);
			String[] peers = s.getPeerList(i);
			out.println("RandomPeerJoin: Got peer list for server " + i +
						" (" + peers.length + " peers).");
			if (peers.length <= 0) return;

			int j = Node.random.nextInt(s.getPeers().length);
			out.println("RandomPeerJoin: Attempting to open " + peers[j]);

			if (!s.open(peers[j])) {
				out.println("RandomPeerJoin: Unable to open " + peers[j]);
			} else {
				s.close(i);
			}
		} catch (IOException e) {
			out.println("RandomPeerJoin: " + e);
		}
	}
}
