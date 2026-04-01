/*
 * Copyright 2020 Michael Murray
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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

/**
 * A simple holder for a paired {@link DataInputStream} and {@link DataOutputStream},
 * typically wrapping the streams of a {@link Socket}.
 *
 * <p>Used by {@link Resource} implementations to abstract away the underlying transport
 * when loading from or sending to a remote host.</p>
 */
public class IOStreams {
	/** The input stream for reading from the remote end. */
	public DataInputStream in;
	/** The output stream for writing to the remote end. */
	public DataOutputStream out;
	/** The host name or IP address string of the remote end. */
	public String host;

	/**
	 * Constructs an empty {@link IOStreams} with no streams or host set.
	 */
	public IOStreams() { }

	/**
	 * Constructs an {@link IOStreams} wrapping the given input stream, setting the host to
	 * {@code "localhost"}.
	 *
	 * @param in The input stream to wrap
	 */
	public IOStreams(InputStream in) {
		this.in = in instanceof DataInputStream ? (DataInputStream) in : new DataInputStream(in);
		this.host = "localhost";
	}

	/**
	 * Constructs an {@link IOStreams} wrapping the streams of the given socket.
	 *
	 * @param s The socket whose streams are to be wrapped
	 * @throws IOException If the socket streams cannot be obtained
	 */
	public IOStreams(Socket s) throws IOException {
		this.in = new DataInputStream(s.getInputStream());
		this.out = new DataOutputStream(s.getOutputStream());
		this.host = s.getInetAddress().toString();
	}

	/**
	 * Closes both the input and output streams.
	 *
	 * @throws IOException If closing either stream fails
	 */
	public void close() throws IOException {
		this.in.close();
		this.out.close();
	}
}