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

public class RandomPeerJoin implements ServerBehavior {

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
			System.out.println("RandomPeerJoin: " + e);
		}
	}
}
