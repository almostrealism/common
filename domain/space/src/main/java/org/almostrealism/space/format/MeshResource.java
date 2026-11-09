/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.space.format;

import io.almostrealism.resource.IOStreams;
import io.almostrealism.resource.Permissions;
import io.almostrealism.resource.Resource;
import org.almostrealism.space.Mesh;

import java.io.InputStream;

/**
 * A {@link Resource} holding an in-memory {@link Mesh}: what the mesh format readers produce
 * and the writers consume.
 *
 * <p>A mesh has no serialized form of its own, so the operations that would load, send, save
 * or stream one throw {@link UnsupportedOperationException}; convert it to a file format such
 * as {@link GtsResource} for that.</p>
 */
public class MeshResource implements Resource<Mesh> {
	/** The mesh this resource holds. */
	private final Mesh mesh;

	/** The permissions of this resource. */
	private final Permissions permissions;

	/** The URI identifying this resource, or {@code null} if none has been set. */
	private String uri;

	/**
	 * Creates a resource holding the given mesh, with default permissions.
	 *
	 * @param m the mesh
	 */
	public MeshResource(Mesh m) {
		this.mesh = m;
		this.permissions = new Permissions();
	}

	@Override
	public void load(IOStreams io) { throw unserializable(); }

	@Override
	public void load(byte[] data, long offset, int len) { throw unserializable(); }

	@Override
	public void loadFromURI() { throw unserializable(); }

	@Override
	public void send(IOStreams io) { throw unserializable(); }

	@Override
	public void saveLocal(String file) { throw unserializable(); }

	@Override
	public String getURI() { return uri; }

	@Override
	public void setURI(String uri) { this.uri = uri; }

	@Override
	public Mesh getData() { return getMesh(); }

	/**
	 * Returns the mesh this resource holds.
	 *
	 * @return the mesh
	 */
	public Mesh getMesh() { return mesh; }

	@Override
	public InputStream getInputStream() { throw unserializable(); }

	@Override
	public Permissions getPermissions() { return permissions; }

	/**
	 * Returns the exception thrown by the operations that need a serialized form, which an
	 * in-memory resource does not have.
	 *
	 * @return the exception to throw
	 */
	static UnsupportedOperationException unserializable() {
		return new UnsupportedOperationException(
				"An in-memory resource has no serialized form; convert it to a file format first");
	}
}
