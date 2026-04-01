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
 * A typed envelope that holds a received or queued object together with the
 * receiver node ID it was addressed to.
 *
 * <p>Instances live in the {@link NodeProxy} inbox list and the outbound
 * queue.  They let the proxy match incoming objects to the correct waiting
 * caller via {@link NodeProxy#nextObject(int)} and
 * {@link NodeProxy#waitFor(int, int)}, and replay queued sends after a
 * reconnection via {@link NodeProxy#flushQueue()}.</p>
 *
 * <p>Previously defined as a private inner class of {@link NodeProxy}.
 * Extracted here so that {@code NodeProxy} stays within the 1500-line
 * file-length limit.</p>
 */
class NodeProxyStoredObject {

	/** The wrapped object ({@link Message} or {@link io.almostrealism.db.Query}). */
	Object o;

	/** The receiver node ID associated with this object. */
	int id;

	/**
	 * Constructs a {@code NodeProxyStoredObject} pairing an object with a receiver ID.
	 *
	 * @param o   The object to store.
	 * @param id  The receiver node ID.
	 */
	NodeProxyStoredObject(Object o, int id) {
		this.o = o;
		this.id = id;
	}

	/**
	 * Returns the stored object.
	 *
	 * @return the wrapped object
	 */
	public Object getObject() { return this.o; }

	/**
	 * Returns the receiver node ID associated with the stored object.
	 *
	 * @return the receiver node ID
	 */
	public int getId() { return this.id; }

	/**
	 * Returns a human-readable description for logging.
	 *
	 * @return a debug string
	 */
	public String toString() { return "StoredObject: " + this.getId() + " " + this.getObject(); }
}
