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

package org.almostrealism.texture;

import org.almostrealism.io.ConsoleFeatures;

import javax.swing.*;
import java.awt.*;
import java.awt.image.PixelGrabber;
import java.net.URL;

/**
 * An {@link ImageSource} that loads image data from a {@link URL}.
 *
 * <p>The image is loaded synchronously at construction time using AWT's {@link java.awt.MediaTracker}.
 * Pixel data is extracted lazily on the first call to {@link #getPixels()} using a
 * {@link java.awt.image.PixelGrabber}.</p>
 *
 * @see ImageSource
 * @see ImageTexture
 * @author Michael Murray
 */
public class URLImageSource implements ImageSource, ConsoleFeatures {
	/** The URL from which the image is loaded. */
	private final URL url;

	/** The AWT image loaded from the URL. */
	private final Image image;

	/** The lazily-loaded flat pixel array (packed ARGB integers). */
	private int[] pixels;

	/**
	 * Constructs a {@link URLImageSource} by loading an image from the given URL.
	 *
	 * @param url the URL from which to load the image
	 * @throws RuntimeException if the image fails to load
	 */
	public URLImageSource(URL url) {
		this.url = url;

		image = Toolkit.getDefaultToolkit().getImage(this.url);
		MediaTracker m = new MediaTracker(new Panel());
		m.addImage(image, 0);

		try {
			m.waitForAll();
		} catch (InterruptedException e) {
			warn(e.getMessage(), e);
		}

		if (m.isErrorAny()) throw new RuntimeException("ImageTexture: Error loading image.");
	}

	@Override
	public int[] getPixels() {
		if (pixels != null) return pixels;
		
		int width = image.getWidth(null);
		int height = image.getHeight(null);
		this.pixels = new int[width * height];

		PixelGrabber p = new PixelGrabber(image, 0, 0, width, height, this.pixels, 0, width);

		try {
			p.grabPixels();
		} catch (InterruptedException e) {
			warn(e.getMessage(), e);
		}
		
		return pixels;
	}

	@Override
	public int getWidth() { return image.getWidth(null); }

	@Override
	public int getHeight() { return image.getHeight(null); }
	
	@Override
	public boolean isAlpha() { return false; }
	
	public ImageIcon getIcon() { return new ImageIcon(url); }
}
