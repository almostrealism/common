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

package io.almostrealism.persist;

import io.almostrealism.resource.IOStreams;
import io.almostrealism.resource.Permissions;
import io.almostrealism.resource.Resource;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A {@link Resource} backed by a local file system path.
 *
 * <p>The resource URI is treated as a file path. Reading is performed through a
 * {@link java.io.FileInputStream} opened on demand; writing is performed by copying
 * bytes to a {@link java.io.FileOutputStream} at the specified local path.</p>
 */
public class LocalResource implements Resource {
	/** The original URI (file path) as set via {@link #setURI(String)}. */
	private String uri;
	/** The {@link File} resolved from the current URI. */
	private File file;
	/** Optional pre-supplied {@link InputStream} to use instead of opening the file. */
	private InputStream in;

	/** The permissions governing access to this resource. */
	private final Permissions permissions;

	/**
	 * Constructs a {@link LocalResource} with default (empty) permissions and no URI set.
	 */
	public LocalResource() {
		this.permissions = new Permissions();
	}

	/**
	 * Constructs a {@link LocalResource} for the given URI with default permissions.
	 *
	 * @param uri The file path URI for this resource
	 */
	public LocalResource(String uri) {
		this.setURI(uri);
		this.permissions = new Permissions();
	}

	/**
	 * Constructs a {@link LocalResource} for the given URI with the specified permissions.
	 *
	 * @param uri         The file path URI for this resource
	 * @param permissions The permissions to associate with this resource
	 */
	public LocalResource(String uri, Permissions permissions) {
		this.setURI(uri);
		this.permissions = permissions;
	}

	/** {@inheritDoc} Returns the underlying {@link File} object. */
	@Override
	public Object getData() { return this.file; }

	/** {@inheritDoc} Returns the pre-supplied stream if set, otherwise opens a new {@link java.io.FileInputStream}. */
	@Override
	public InputStream getInputStream() {
		if (this.in != null) return in;
		if (this.file == null) return null;
		
		try {
			return new FileInputStream(file);
		} catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		}
	}

	/** {@inheritDoc} Returns the absolute path of the underlying file. */
	@Override
	public String getURI() { return this.file.getAbsolutePath(); }

	/** {@inheritDoc} */
	@Override
	public Permissions getPermissions() { return permissions; }

	/**
	 * Stores the supplied input stream for use by subsequent reads.
	 *
	 * @param io The IO streams; the input stream is retained for later reading
	 * @throws IOException If an I/O error occurs
	 */
	@Override
	public void load(IOStreams io) throws IOException {
		this.in = io.in;
	}

	@Override
	public void load(byte[] bytes, long l, int i) {
		// TODO
	}

	/**
	 * Resolves the current URI to a local {@link File} object.
	 *
	 * @throws IOException If the URI cannot be resolved (not thrown currently)
	 */
	@Override
	public void loadFromURI() throws IOException {
		this.file = new File(this.uri);
	}

	/**
	 * Saves the resource contents to a local file path by copying bytes one at a time.
	 *
	 * @param file The destination file path
	 * @throws IOException If reading or writing fails
	 */
	@Override
	public void saveLocal(String file) throws IOException {
		InputStream in = this.getInputStream();
		
		try (OutputStream out = new FileOutputStream(file)) {
			byte[] b = new byte[1];
			in.read(b);
			
			while (in.read(b) >= 0) { out.write(b); }
			
			out.flush();
		}
	}

	/**
	 * Writes the resource contents to the output stream of the given {@link IOStreams},
	 * then flushes and closes it.
	 *
	 * @param io The IO streams whose output stream receives the resource data
	 * @throws IOException If reading or writing fails
	 */
	@Override
	public void send(IOStreams io) throws IOException {
		InputStream in = this.getInputStream();
		
		byte[] b = new byte[1];
		in.read(b);
		
		while (in.read(b) >= 0) { io.out.write(b); }
		
		io.out.flush();
		io.out.close();
	}

	/**
	 * Sets the URI for this resource and resolves it to a {@link File}, clearing any
	 * pre-supplied input stream.
	 *
	 * @param uri The new file path URI
	 */
	@Override
	public void setURI(String uri) {
		this.uri = uri;
		this.file = new File(this.uri);
		this.in = null;
	}
}
