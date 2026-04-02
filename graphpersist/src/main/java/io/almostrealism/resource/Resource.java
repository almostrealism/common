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

package io.almostrealism.resource;

import io.almostrealism.relation.Node;

import java.io.IOException;
import java.io.InputStream;

/**
 * Core abstraction for a named, permissioned resource that can be loaded from and sent
 * over network streams or local storage.
 *
 * <p>A resource is identified by a URI string and carries {@link Permissions}. It can be
 * loaded from an {@link IOStreams} pair, from a raw byte array, or by resolving its URI.
 * Its data can be sent to an {@link IOStreams} pair or saved to a local file.</p>
 *
 * @param <T> The type of the data object held by this resource
 * @author  Michael Murray
 */
public interface Resource<T extends Object> extends Node {
	/**
	 * Loads resource data from the given IO streams.
	 *
	 * @param io The IO streams to read from
	 * @throws IOException If reading fails
	 */
	void load(IOStreams io) throws IOException;

	/**
	 * Loads resource data from a byte array segment.
	 *
	 * @param data   The source byte array
	 * @param offset The starting byte offset within the array
	 * @param len    The number of bytes to read
	 */
	void load(byte[] data, long offset, int len);

	/**
	 * Loads resource data by resolving and reading from the resource's URI.
	 *
	 * @throws IOException If reading from the URI fails
	 */
	void loadFromURI() throws IOException;

	/**
	 * Sends the resource data to the given IO streams.
	 *
	 * @param io The IO streams to write to
	 * @throws IOException If writing fails
	 */
	void send(IOStreams io) throws IOException;

	/**
	 * Saves the resource data to a local file at the given path.
	 *
	 * @param file The local file path to write to
	 * @throws IOException If writing fails
	 */
	void saveLocal(String file) throws IOException;

	/**
	 * Returns the URI that identifies this resource.
	 *
	 * @return The resource URI string
	 */
	String getURI();

	/**
	 * Sets the URI that identifies this resource.
	 *
	 * @param uri The new URI string
	 */
	void setURI(String uri);

	/**
	 * Returns the raw data object held by this resource.
	 *
	 * @return The data object, or {@code null} if not yet loaded
	 */
	T getData();

	/**
	 * Returns an {@link InputStream} for reading the resource data.
	 *
	 * @return An input stream over the resource data
	 */
	InputStream getInputStream();

	/**
	 * Returns the permissions governing access to this resource.
	 *
	 * @return The resource's permissions
	 */
	Permissions getPermissions();
}
