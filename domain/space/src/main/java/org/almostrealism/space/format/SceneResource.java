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
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.space.Scene;

import java.io.InputStream;

/**
 * A {@link Resource} holding an in-memory {@link Scene}, which is what {@link RawResource}
 * is read into.
 *
 * <p>Like {@link MeshResource}, it has no serialized form, so the operations that would
 * load, send, save or stream it throw {@link UnsupportedOperationException}.</p>
 *
 * @param <T> the type of surface in the scene
 */
public class SceneResource<T extends ShadableSurface> implements Resource<Scene<T>> {
	/** The scene this resource holds. */
	private final Scene<T> scene;

	/** The permissions of this resource. */
	private final Permissions permissions;

	/** The URI identifying this resource, or {@code null} if none has been set. */
	private String uri;

	/**
	 * Creates a resource holding the given scene, with default permissions.
	 *
	 * @param s the scene
	 */
	public SceneResource(Scene<T> s) {
		this.scene = s;
		this.permissions = new Permissions();
	}

	@Override
	public void load(byte[] data, long offset, int len) { throw MeshResource.unserializable(); }

	@Override
	public void load(IOStreams io) { throw MeshResource.unserializable(); }

	@Override
	public void loadFromURI() { throw MeshResource.unserializable(); }

	@Override
	public void send(IOStreams io) { throw MeshResource.unserializable(); }

	@Override
	public void saveLocal(String file) { throw MeshResource.unserializable(); }

	@Override
	public String getURI() { return uri; }

	@Override
	public void setURI(String uri) { this.uri = uri; }

	@Override
	public Scene<T> getData() { return getScene(); }

	/**
	 * Returns the scene this resource holds.
	 *
	 * @return the scene
	 */
	public Scene<T> getScene() { return scene; }

	@Override
	public InputStream getInputStream() { throw MeshResource.unserializable(); }

	@Override
	public Permissions getPermissions() { return permissions; }
}
