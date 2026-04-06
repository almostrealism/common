/*
 * Copyright 2016 Michael Murray
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

/*
 * Copyright (C) 2006  Mike Murray
 *
 *  All rights reserved.
 *  This document may not be reused without
 *  express written permission from Mike Murray.
 *
 */

package io.flowtree.fs;

import io.almostrealism.persist.ScpDownloader;
import io.almostrealism.resource.IOStreams;
import io.almostrealism.resource.Permissions;
import io.almostrealism.resource.Resource;
import io.flowtree.node.Client;
import org.apache.commons.lang3.NotImplementedException;

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

/**
 * A {@link Resource} implementation for transferring pixel data between
 * FlowTree nodes. The image is stored internally as a flat {@code int[]} array
 * where {@code data[0]} is the image width, {@code data[1]} is the image
 * height, and elements starting at index 2 are packed ARGB pixel values in
 * row-major order.
 *
 * <p>Transfer is performed over an {@link IOStreams} connection:
 * <ul>
 *   <li>{@link #load(IOStreams)} — the client sends a clipping region
 *       (x, y, width, height) and receives the matching pixel block from
 *       the server side.</li>
 *   <li>{@link #send(IOStreams)} — the server reads a clipping region from
 *       the client and writes the matching pixel block.</li>
 * </ul>
 *
 * <p>Loading from URI supports {@code scp://}, {@code resource://}, and
 * generic URL schemes. SCP loading uses {@link ScpDownloader} and requires
 * a URI of the form {@code scp://host|user|password/path}.
 *
 * @author  Mike Murray
 */
public class ImageResource implements Resource {

	/** URI that identifies this resource within the distributed file system. */
	private String uri;

	/**
	 * Packed pixel data. {@code data[0]} = width, {@code data[1]} = height,
	 * subsequent elements are packed ARGB pixel values.
	 */
	private int[] data;

	/** X origin of the region of interest within the full image. */
	private int x;

	/** Y origin of the region of interest within the full image. */
	private int y;

	/** Width of the current region of interest or the full image. */
	private int w;

	/** Height of the current region of interest or the full image. */
	private int h;

	/** Access-control permissions associated with this resource. */
	private final Permissions permissions;

	/**
	 * Constructs an empty {@link ImageResource} with default permissions and
	 * no pixel data.
	 */
	public ImageResource() {
		this.permissions = new Permissions();
	}

	/**
	 * Constructs an {@link ImageResource} with pre-loaded pixel data.
	 * The width and height are read from {@code data[0]} and {@code data[1]}.
	 *
	 * @param uri         URI for this resource
	 * @param data        packed pixel array ({@code [width, height, pixel0, …]});
	 *                    may be {@code null}
	 * @param permissions access-control permissions
	 */
	public ImageResource(String uri, int[] data, Permissions permissions) {
		this.uri = uri;
		this.data = data;

		if (this.data != null) {
			this.w = this.data[0];
			this.h = this.data[1];
		}

		this.permissions = permissions;
	}

	/**
	 * Sets the x origin of the region of interest.
	 *
	 * @param x x-coordinate in pixels
	 */
	public void setX(int x) { this.x = x; }

	/**
	 * Sets the y origin of the region of interest.
	 *
	 * @param y y-coordinate in pixels
	 */
	public void setY(int y) { this.y = y; }

	/**
	 * Sets the width of the current region of interest.
	 *
	 * @param w width in pixels
	 */
	public void setWidth(int w) { this.w = w; }

	/**
	 * Sets the height of the current region of interest.
	 *
	 * @param h height in pixels
	 */
	public void setHeight(int h) { this.h = h; }

	/**
	 * Returns the x origin of the region of interest.
	 *
	 * @return x-coordinate in pixels
	 */
	public int getX() { return this.x; }

	/**
	 * Returns the y origin of the region of interest.
	 *
	 * @return y-coordinate in pixels
	 */
	public int getY() { return this.y; }

	/**
	 * Returns the width of the current region of interest or full image.
	 *
	 * @return width in pixels
	 */
	public int getWidth() { return this.w; }

	/**
	 * Returns the height of the current region of interest or full image.
	 *
	 * @return height in pixels
	 */
	public int getHeight() { return this.h; }

	/**
	 * Returns the access-control permissions for this resource.
	 *
	 * @return the permissions object
	 */
	@Override
	public Permissions getPermissions() { return permissions; }

	/**
	 * Loads pixel data from a remote server through an {@link IOStreams}
	 * connection. The method first writes the desired clipping region (x, y,
	 * width, height) to the output stream, then reads the pixel data sent by the
	 * server: width and height followed by width×height packed ARGB pixel values.
	 * Progress milestones at 25%, 50%, 75%, and 100% are printed to standard
	 * output.
	 *
	 * @param io the paired input/output streams connected to the remote resource server
	 * @throws IOException if an I/O error occurs during the transfer
	 */
	@Override
	public void load(IOStreams io) throws IOException {
		io.out.writeInt(this.x);
		io.out.writeInt(this.y);
		io.out.writeInt(this.w);
		io.out.writeInt(this.h);

		int w = io.in.readInt();
		int h = io.in.readInt();
		this.data = new int[2 + w * h];
		this.data[0] = w;
		this.data[1] = h;

		System.out.println("ImageResource: Reading " + w + "x" + h + " image...");

		int one = data.length / 4;
		int two = data.length / 2;
		int three = 3 * one;
		int four = data.length - 1;

		for (int i = 2; i < data.length; i++) {
			this.data[i] = io.in.readInt();

			if (i == one)
				System.out.println("ImageResource: Load 25% Complete.");
			else if (i == two)
				System.out.println("ImageResource: Load 50% Complete.");
			else if (i == three)
				System.out.println("ImageResource: Load 75% Complete.");
			else if (i == four)
				System.out.println("ImageResource: Load 100% Complete.");
		}
	}

	/**
	 * Byte-range loading is not supported and always throws
	 * {@link NotImplementedException}.
	 *
	 * @param data   unused
	 * @param offset unused
	 * @param len    unused
	 */
	@Override
	public void load(byte[] data, long offset, int len) {
		throw new NotImplementedException("load");
	}

	/**
	 * Loads pixel data from the URI configured for this resource. Three URI
	 * schemes are supported:
	 * <ul>
	 *   <li>{@code scp://host|user|password/path} — downloads via SCP in a
	 *       background thread using {@link ScpDownloader}.</li>
	 *   <li>{@code resource://...} — loads from a peer node via the IOStreams
	 *       resource protocol.</li>
	 *   <li>Any other URI — fetched as a generic URL (JAI-based loading is
	 *       currently commented out and not implemented).</li>
	 * </ul>
	 */
	@Override
	public void loadFromURI() {
		try {
			RenderedImage im = null;

			if (uri.startsWith("scp://")) {
				String ur = uri.substring(6);
				int index = ur.indexOf("|");
				String host = ur.substring(0, index);
				ur = ur.substring(index + 1);
				index = ur.indexOf("|");
				String user = ur.substring(0, index);
				ur = ur.substring(index + 1);
				index = ur.indexOf("/");
				String passwd = ur.substring(0, index);
				ur = ur.substring(index);

				final String fur = ur;

				System.out.println("ImageResource: Loading image from " + user + "@" + host + ur);

				PipedInputStream in = new PipedInputStream();
				final PipedOutputStream out = new PipedOutputStream(in);

				final ScpDownloader scpd = ScpDownloader.getDownloader(host, user, passwd);

				ThreadGroup g = null;
				Client c = Client.getCurrentClient();
				if (c != null) g = c.getServer().getThreadGroup();
				Thread dt = new Thread(g, "ImageResource SCP Loader") {
					@Override
					public void run() {
						try {
							scpd.download(fur, out);
						} catch (IOException ioe) {
							System.out.println("Server: " + ioe.getMessage());
						}
					}
				};
				dt.start();
//				TODO  Must load image without JAI
//				im = JAI.create("stream", seekableInput);
			} else if (uri.startsWith("resource://")) {
				IOStreams io = Client.getCurrentClient().getServer().parseResourceUri(uri);

				if (io == null) {
					System.out.println("ImageResource: Resource unavailable.");
					return;
				}

				this.load(io);
				io.close();
			} else {
				System.out.println("ImageResource: Loading image from " + uri);
//				TODO  Must load image without JAI
//				im = JAI.create("url", new URL(uri));
			}

			if (im == null && this.data == null) throw new IOException();

			if (im != null) {
				int w = im.getWidth();
				int h = im.getHeight();

				System.out.println("ImageResource: Buffer is " + w + " x " + h);

				BufferedImage bim = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
				bim.setData(im.copyData(null));

				int index = 2;
				this.data = new int[2 + w * h];
				this.data[0] = w;
				this.data[1] = h;

				System.out.println("ImageResource: Converting...");

				for (int j = 0; j < h; j++) {
					for (int i = 0; i < w; i++) {
						this.data[index++] = bim.getRGB(i, j);
					}
				}

				System.out.println("ImageResource: Converted.");
			}

			System.out.println("ImageResource: Loaded " + this.data[0] + " x " + this.data[1]);
		} catch (IndexOutOfBoundsException oob) {
			System.out.println("Server: Index out of bounds while loading image.");
			oob.printStackTrace(System.out);
		} catch (NullPointerException np) {
			System.out.println("Server: Error loading image (null pointer).");
			np.printStackTrace(System.out);
		} catch (Exception e) {
			System.out.println("Server: Error loading image - " + e);
		}
	}

	/**
	 * Sends the pixel data stored in this resource to a remote client through
	 * the given {@link IOStreams} connection. The method first reads the
	 * clipping region (x, y, width, height) requested by the remote client,
	 * clips the in-memory pixel data accordingly, then writes the resulting
	 * width, height, and pixel values. Progress milestones at 25%, 50%, 75%,
	 * and 100% are printed to standard output.
	 *
	 * @param io the paired input/output streams connected to the remote client
	 * @throws IOException if an I/O error occurs during the transfer
	 */
	@Override
	public void send(IOStreams io) throws IOException {
		if (this.data == null) return;

		int sx = io.in.readInt();
		int sy = io.in.readInt();
		int sw = io.in.readInt();
		int sh = io.in.readInt();

		if (sw == 0) sw = this.w;
		if (sh == 0) sh = this.h;

		int[] rgb = this.clip(sx, sy, sw, sh);

		System.out.println("ImageResource: Sending " + rgb[0] + "x" + rgb[1] + " image...");

		io.out.writeInt(rgb[0]);
		io.out.writeInt(rgb[1]);

		int one = rgb.length / 4;
		int two = rgb.length / 2;
		int three = 3 * one;
		int four = rgb.length - 1;

		for (int i = 2; i < rgb.length; i++) {
			io.out.writeInt(rgb[i]);

			if (i == 2)
				System.out.println("ImageResource: Sent first pixel.");
			else if (i == one)
				System.out.println("ImageResource: Send 25% Complete.");
			else if (i == two)
				System.out.println("ImageResource: Send 50% Complete.");
			else if (i == three)
				System.out.println("ImageResource: Send 75% Complete.");
			else if (i == four)
				System.out.println("ImageResource: Send 100% Complete.");
		}

		System.out.println("ImageResource: Flushing output stream.");
		io.out.flush();
		System.out.println("ImageResource: Done.");
	}


	/**
	 * Clips the image to the specified region and returns the pixel data.
	 * The returned array has the format [width, height, pixel0, pixel1, ...].
	 *
	 * @param cx the x-offset of the clipping region
	 * @param cy the y-offset of the clipping region
	 * @param cw the width of the clipping region
	 * @param ch the height of the clipping region
	 * @return pixel data for the clipped region
	 */
	public int[] clip(int cx, int cy, int cw, int ch) {
		System.out.println("ImageResource: Clipping to "
							+ cx + ", " + cy + ", " +
							cw + ", " + ch + "...");

		if (cx == 0 && cy == 0 && cw == this.w && ch == this.h) {
			int[] out = new int[this.data.length];
			System.arraycopy(this.data, 0, out, 0, this.data.length);
			return out;
		}

		cx = cx - this.x;
		cy = cy - this.y;

		if (cw < 0) cw = this.data[0] + cw;
		if (ch < 0) ch = this.data[1] + ch;

		int[] rgb = new int[2 + cw * ch];
		rgb[0] = cw;
		rgb[1] = ch;
		int index = 2;

		for(int j = 0; j < ch; j++) {
			for(int i = 0; i < cw; i++) {
				rgb[index++] = this.data[2 + (j + cy) * this.data[0] + (i + cx)];
			}
		}

		return rgb;
	}

	/**
	 * Returns the URI identifying this resource.
	 *
	 * @return the URI string
	 */
	@Override
	public String getURI() { return this.uri; }

	/**
	 * Sets the URI identifying this resource.
	 *
	 * @param uri the new URI string
	 */
	@Override
	public void setURI(String uri) { this.uri = uri; }

	/**
	 * Returns the raw pixel data array. The first two elements are width and
	 * height; subsequent elements are packed ARGB values.
	 *
	 * @return the {@code int[]} pixel data, or {@code null} if not loaded
	 */
	@Override
	public Object getData() { return this.data; }

	/**
	 * Local file saving is not implemented for this resource type.
	 *
	 * @param file unused
	 * @throws IOException never; this implementation is a no-op
	 */
	@Override
	public void saveLocal(String file) throws IOException {
	}

	/**
	 * Returns {@code true} if {@code o} is an {@link ImageResource} with the
	 * same dimensions, position, and URI.
	 *
	 * @param o the object to compare
	 * @return {@code true} if equal
	 */
	@Override
	public boolean equals(Object o) {
		if (!(o instanceof ImageResource)) return false;

		ImageResource res = (ImageResource) o;
		return this.w == res.w &&
				this.h == res.h &&
				this.x == res.x &&
				this.y == res.y &&
				this.uri.equals(res.uri);
	}

	/**
	 * Returns a hash code based solely on the resource URI.
	 *
	 * @return hash code
	 */
	@Override
	public int hashCode() { return this.uri.hashCode(); }

	/**
	 * {@link InputStream}-based access is not implemented for
	 * {@link ImageResource} and always throws {@link RuntimeException}.
	 *
	 * @return never returns normally
	 * @throws RuntimeException always
	 */
	@Override
	public InputStream getInputStream() {
		throw new RuntimeException("Not implemented");
//		return null;
	}
}
