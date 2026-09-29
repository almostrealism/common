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
import org.almostrealism.util.TestSuiteBase;
import org.apache.commons.lang3.NotImplementedException;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Behavioural tests for {@link ImageResource}, covering its accessors, the
 * {@link ImageResource#clip(int, int, int, int)} sub-region logic, equality,
 * and the {@link IOStreams}-based {@link ImageResource#load(IOStreams)} /
 * {@link ImageResource#send(IOStreams)} transfer protocol. The clip-specific
 * offset regression is covered separately by {@link ImageResourceClipTest};
 * this class focuses on the remaining public contract.
 */
public class ImageResourceTest extends TestSuiteBase {

	/**
	 * Builds a width×height image whose pixel values equal their linear
	 * (row-major) index. The backing array is {@code [width, height, p0, p1, …]}.
	 *
	 * @param width  image width in pixels
	 * @param height image height in pixels
	 * @return a fully populated {@link ImageResource}
	 */
	private ImageResource image(int width, int height) {
		int[] data = new int[2 + width * height];
		data[0] = width;
		data[1] = height;
		for (int i = 0; i < width * height; i++) {
			data[2 + i] = i;
		}
		return new ImageResource("img://test", data, new Permissions());
	}

	/**
	 * The data-carrying constructor must expose the width and height read from
	 * the first two array slots and retain the URI and raw data.
	 */
	@Test(timeout = 5000)
	public void constructorReadsDimensions() {
		ImageResource img = image(4, 3);
		Assert.assertEquals(4, img.getWidth());
		Assert.assertEquals(3, img.getHeight());
		Assert.assertEquals("img://test", img.getURI());
		int[] raw = (int[]) img.getData();
		Assert.assertEquals(4, raw[0]);
		Assert.assertEquals(3, raw[1]);
	}

	/**
	 * The no-argument constructor must leave the resource empty (no data) yet
	 * still supply a non-null default {@link Permissions}.
	 */
	@Test(timeout = 5000)
	public void emptyConstructorHasNoData() {
		ImageResource img = new ImageResource();
		Assert.assertNull(img.getData());
		Assert.assertEquals(0, img.getWidth());
		Assert.assertEquals(0, img.getHeight());
		Assert.assertEquals("root", img.getPermissions().getOwner());
	}

	/**
	 * The region-of-interest setters must be reflected by their getters.
	 */
	@Test(timeout = 5000)
	public void regionSettersAndGetters() {
		ImageResource img = image(4, 3);
		img.setX(7);
		img.setY(9);
		img.setWidth(2);
		img.setHeight(1);
		Assert.assertEquals(7, img.getX());
		Assert.assertEquals(9, img.getY());
		Assert.assertEquals(2, img.getWidth());
		Assert.assertEquals(1, img.getHeight());

		img.setURI("img://renamed");
		Assert.assertEquals("img://renamed", img.getURI());
	}

	/**
	 * A negative clip width or height must be interpreted relative to the full
	 * image dimensions, so {@code cw = -1} yields {@code width - 1} columns and
	 * {@code ch = -1} yields {@code height - 1} rows.
	 */
	@Test(timeout = 5000)
	public void clipWithNegativeExtentWrapsToImageSize() {
		ImageResource img = image(4, 3);

		int[] result = img.clip(0, 0, -1, -1);
		Assert.assertEquals(3, result[0]);
		Assert.assertEquals(2, result[1]);
		Assert.assertEquals(2 + 3 * 2, result.length);
		Assert.assertEquals(0, result[2]);
		Assert.assertEquals(1, result[3]);
		Assert.assertEquals(2, result[4]);
		Assert.assertEquals(4, result[5]);
		Assert.assertEquals(5, result[6]);
		Assert.assertEquals(6, result[7]);
	}

	/**
	 * {@link ImageResource#send(IOStreams)} must read the requested clip region
	 * (1, 0, 2, 2) from the input stream and write back the clipped width,
	 * height, and pixel block (1, 2, 5, 6 for the index image).
	 */
	@Test(timeout = 5000)
	public void sendWritesRequestedClip() throws IOException {
		ImageResource img = image(4, 3);

		ByteArrayOutputStream reqBytes = new ByteArrayOutputStream();
		DataOutputStream req = new DataOutputStream(reqBytes);
		req.writeInt(1);
		req.writeInt(0);
		req.writeInt(2);
		req.writeInt(2);
		req.flush();

		IOStreams io = new IOStreams();
		io.in = new DataInputStream(new ByteArrayInputStream(reqBytes.toByteArray()));
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		io.out = new DataOutputStream(sink);

		img.send(io);

		DataInputStream result = new DataInputStream(new ByteArrayInputStream(sink.toByteArray()));
		Assert.assertEquals(2, result.readInt());
		Assert.assertEquals(2, result.readInt());
		Assert.assertEquals(1, result.readInt());
		Assert.assertEquals(2, result.readInt());
		Assert.assertEquals(5, result.readInt());
		Assert.assertEquals(6, result.readInt());
		Assert.assertEquals(0, result.available());
	}

	/**
	 * A zero-sized clip request to {@link ImageResource#send(IOStreams)} must
	 * fall back to the full image extent.
	 */
	@Test(timeout = 5000)
	public void sendZeroSizeRequestSendsFullImage() throws IOException {
		ImageResource img = image(2, 2);

		ByteArrayOutputStream reqBytes = new ByteArrayOutputStream();
		DataOutputStream req = new DataOutputStream(reqBytes);
		req.writeInt(0);
		req.writeInt(0);
		req.writeInt(0);
		req.writeInt(0);
		req.flush();

		IOStreams io = new IOStreams();
		io.in = new DataInputStream(new ByteArrayInputStream(reqBytes.toByteArray()));
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		io.out = new DataOutputStream(sink);

		img.send(io);

		DataInputStream result = new DataInputStream(new ByteArrayInputStream(sink.toByteArray()));
		Assert.assertEquals(2, result.readInt());
		Assert.assertEquals(2, result.readInt());
		Assert.assertEquals(0, result.readInt());
		Assert.assertEquals(1, result.readInt());
		Assert.assertEquals(2, result.readInt());
		Assert.assertEquals(3, result.readInt());
	}

	/**
	 * A resource with no data must not write anything in
	 * {@link ImageResource#send(IOStreams)}; the method returns before touching
	 * the streams.
	 */
	@Test(timeout = 5000)
	public void sendWithoutDataWritesNothing() throws IOException {
		ImageResource img = new ImageResource();

		IOStreams io = new IOStreams();
		io.in = new DataInputStream(new ByteArrayInputStream(new byte[0]));
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		io.out = new DataOutputStream(sink);

		img.send(io);
		Assert.assertEquals(0, sink.size());
	}

	/**
	 * {@link ImageResource#load(IOStreams)} must first write its requested
	 * region (x, y, w, h) to the output stream, then read the server-supplied
	 * dimensions and pixel data into its backing array.
	 */
	@Test(timeout = 5000)
	public void loadWritesRequestThenReadsPixels() throws IOException {
		ImageResource img = new ImageResource();
		img.setX(3);
		img.setY(5);
		img.setWidth(2);
		img.setHeight(2);

		ByteArrayOutputStream respBytes = new ByteArrayOutputStream();
		DataOutputStream resp = new DataOutputStream(respBytes);
		resp.writeInt(2);
		resp.writeInt(2);
		resp.writeInt(10);
		resp.writeInt(20);
		resp.writeInt(30);
		resp.writeInt(40);
		resp.flush();

		IOStreams io = new IOStreams();
		io.in = new DataInputStream(new ByteArrayInputStream(respBytes.toByteArray()));
		ByteArrayOutputStream requestSink = new ByteArrayOutputStream();
		io.out = new DataOutputStream(requestSink);

		img.load(io);

		DataInputStream request = new DataInputStream(new ByteArrayInputStream(requestSink.toByteArray()));
		Assert.assertEquals(3, request.readInt());
		Assert.assertEquals(5, request.readInt());
		Assert.assertEquals(2, request.readInt());
		Assert.assertEquals(2, request.readInt());

		int[] loaded = (int[]) img.getData();
		Assert.assertEquals(2 + 2 * 2, loaded.length);
		Assert.assertEquals(2, loaded[0]);
		Assert.assertEquals(2, loaded[1]);
		Assert.assertEquals(10, loaded[2]);
		Assert.assertEquals(20, loaded[3]);
		Assert.assertEquals(30, loaded[4]);
		Assert.assertEquals(40, loaded[5]);
	}

	/**
	 * Sending an image and loading it back through a paired stream must
	 * reproduce the original pixel data, exercising both halves of the transfer
	 * protocol together.
	 */
	@Test(timeout = 5000)
	public void sendThenLoadRoundTrip() throws IOException {
		ImageResource server = image(3, 2);

		ByteArrayOutputStream requestBytes = new ByteArrayOutputStream();
		DataOutputStream request = new DataOutputStream(requestBytes);
		request.writeInt(0);
		request.writeInt(0);
		request.writeInt(0);
		request.writeInt(0);
		request.flush();

		IOStreams serverIo = new IOStreams();
		serverIo.in = new DataInputStream(new ByteArrayInputStream(requestBytes.toByteArray()));
		ByteArrayOutputStream dataBytes = new ByteArrayOutputStream();
		serverIo.out = new DataOutputStream(dataBytes);
		server.send(serverIo);

		ImageResource client = new ImageResource();
		IOStreams clientIo = new IOStreams();
		clientIo.in = new DataInputStream(new ByteArrayInputStream(dataBytes.toByteArray()));
		clientIo.out = new DataOutputStream(new ByteArrayOutputStream());
		client.load(clientIo);

		Assert.assertArrayEquals((int[]) server.getData(), (int[]) client.getData());
	}

	/**
	 * Two resources with identical dimensions, position, and URI are equal and
	 * share a hash code derived from the URI; a differing URI or position breaks
	 * equality.
	 */
	@Test(timeout = 5000)
	public void equalsAndHashCode() {
		ImageResource a = image(4, 3);
		ImageResource b = image(4, 3);
		Assert.assertEquals(a, b);
		Assert.assertEquals(a.hashCode(), b.hashCode());
		Assert.assertEquals("img://test".hashCode(), a.hashCode());

		b.setURI("img://other");
		Assert.assertNotEquals(a, b);

		ImageResource shifted = image(4, 3);
		shifted.setX(1);
		Assert.assertNotEquals(a, shifted);

		Assert.assertNotEquals(a, "not an image resource");
	}

	/**
	 * The byte-range {@code load} overload is unsupported and must throw
	 * {@link NotImplementedException}.
	 */
	@Test(timeout = 5000, expected = NotImplementedException.class)
	public void byteRangeLoadIsUnsupported() {
		new ImageResource().load(new byte[4], 0L, 4);
	}

	/**
	 * {@link ImageResource#getInputStream()} is unsupported and must throw.
	 */
	@Test(timeout = 5000, expected = RuntimeException.class)
	public void inputStreamIsUnsupported() {
		new ImageResource().getInputStream();
	}

	/**
	 * {@link ImageResource#saveLocal(String)} is a documented no-op and must
	 * complete without error or side effect on the data.
	 */
	@Test(timeout = 5000)
	public void saveLocalIsNoOp() throws IOException {
		ImageResource img = image(2, 2);
		int[] before = (int[]) img.getData();
		img.saveLocal("/tmp/ignored");
		Assert.assertSame(before, img.getData());
	}

	/**
	 * {@link ImageResource#loadFromURI()} for a generic (non-scp, non-resource)
	 * URI cannot decode any pixels without JAI, so the internal
	 * {@link IOException} must be swallowed and the resource left with no data
	 * rather than propagating an exception to the caller.
	 */
	@Test(timeout = 5000)
	public void loadFromGenericUriLeavesNoData() {
		ImageResource img = new ImageResource();
		img.setURI("http://example.com/photo.png");
		img.loadFromURI();
		Assert.assertNull(img.getData());
	}

	/**
	 * {@link ImageResource#loadFromURI()} for a {@code resource://} URI with no
	 * running client must handle the resulting failure internally and leave the
	 * resource empty instead of throwing.
	 */
	@Test(timeout = 5000)
	public void loadFromResourceUriWithoutClientLeavesNoData() {
		ImageResource img = new ImageResource();
		img.setURI("resource://peer/photo.png");
		img.loadFromURI();
		Assert.assertNull(img.getData());
	}
}
