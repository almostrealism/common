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

package io.flowtree.fs;

import io.almostrealism.resource.IOStreams;
import io.almostrealism.resource.Permissions;
import io.almostrealism.resource.Resource;

import java.io.InputStream;

/**
 * A minimal {@link Resource} whose {@link #getData()} returns a fixed
 * {@code byte[][]} chunk array. It is the common test fixture for driving the
 * {@link DistributedResource} wrapping constructor and the chunk-serving paths
 * of {@link ResourceDistributionTask} without touching the network or database,
 * shared by every {@code io.flowtree.fs} test that needs a pre-loaded resource
 * so the stub is defined in exactly one place.
 */
final class ChunkResource implements Resource<byte[][]> {

	/** URI reported by this stub resource. */
	private String uri;

	/** Fixed chunk array returned by {@link #getData()}. */
	private final byte[][] data;

	/**
	 * Constructs a stub resource with the given URI and chunk data.
	 *
	 * @param uri  the resource URI
	 * @param data the chunk array to expose
	 */
	ChunkResource(String uri, byte[][] data) {
		this.uri = uri;
		this.data = data;
	}

	@Override
	public void load(IOStreams io) { }

	@Override
	public void load(byte[] data, long offset, int len) { }

	@Override
	public void loadFromURI() { }

	@Override
	public void send(IOStreams io) { }

	@Override
	public void saveLocal(String file) { }

	@Override
	public String getURI() { return uri; }

	@Override
	public void setURI(String uri) { this.uri = uri; }

	@Override
	public byte[][] getData() { return data; }

	@Override
	public InputStream getInputStream() { return null; }

	@Override
	public Permissions getPermissions() { return new Permissions(); }
}
