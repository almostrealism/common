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

package io.flowtree.test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;

/**
 * A {@link Socket} that lets a {@link io.flowtree.msg.NodeProxy} be constructed in a unit test
 * without a real peer. Its output stream silently discards everything written, and its input
 * stream carries only a valid object-stream header, so the proxy's constructor completes and its
 * reader thread finds nothing to read rather than blocking on a live connection. The reported
 * address is the loopback address, which the proxy adopts as its label.
 *
 * @author  Michael Murray
 * @see io.flowtree.msg.NodeProxy
 */
public class HeaderOnlySocket extends Socket {

	/** {@inheritDoc} Reports the loopback address, which the proxy uses as its label. */
	@Override
	public InetAddress getInetAddress() { return InetAddress.getLoopbackAddress(); }

	/**
	 * @return  an output stream that silently discards everything written to it.
	 */
	@Override
	public OutputStream getOutputStream() {
		return new OutputStream() {
			@Override
			public void write(int b) { }

			@Override
			public void write(byte[] b, int off, int len) { }
		};
	}

	/**
	 * @return  an input stream holding just a valid object-stream header, so the proxy's
	 *          {@link java.io.ObjectInputStream} completes construction and then reads nothing.
	 * @throws IOException  never, but declared by the superclass.
	 */
	@Override
	public InputStream getInputStream() throws IOException {
		ByteArrayOutputStream header = new ByteArrayOutputStream();
		new ObjectOutputStream(header).close();
		return new ByteArrayInputStream(header.toByteArray());
	}
}
