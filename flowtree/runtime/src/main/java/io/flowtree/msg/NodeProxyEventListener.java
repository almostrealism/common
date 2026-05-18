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

package io.flowtree.msg;

/**
 * Callback interface for objects that want to be notified of connection
 * lifecycle events and incoming messages from a {@link NodeProxy}.
 *
 * <p>{@link Connection} and {@link io.flowtree.node.NodeGroup} are the primary
 * implementations.  Multiple listeners may be registered on a single
 * {@code NodeProxy}; the proxy iterates them in registration order.</p>
 *
 * <p>The canonical reference is {@link NodeProxy.EventListener}, which is kept
 * as a backward-compatible alias extending this interface so that existing
 * {@code implements} declarations and casts do not need to change.</p>
 */
public interface NodeProxyEventListener {

	/**
	 * Called when the {@link NodeProxy} establishes or re-establishes its
	 * socket connection to the remote peer.
	 *
	 * @param p  The connected {@link NodeProxy}.
	 */
	void connect(NodeProxy p);

	/**
	 * Called when the {@link NodeProxy} loses its socket connection to the
	 * remote peer.  The listener should clean up any state that depends on
	 * the connection being alive.
	 *
	 * @param p  The disconnected {@link NodeProxy}.
	 * @return   An implementation-defined integer; typically {@code 0}.
	 */
	int disconnect(NodeProxy p);

	/**
	 * Called by the {@link NodeProxy} reader thread each time a
	 * {@link Message} arrives.  Returns {@code true} if this listener
	 * claimed and fully processed the message, or {@code false} to pass
	 * it to the next listener.  If no listener claims the message it is
	 * stored in the proxy's inbox for later retrieval.
	 *
	 * @param m         The received message.
	 * @param reciever  The node ID from the message's receiver field.
	 * @return  {@code true} if this listener handled the message.
	 */
	boolean recievedMessage(Message m, int reciever);
}
