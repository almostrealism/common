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

import java.awt.Image;
import java.awt.MediaTracker;
import java.awt.Panel;
import java.awt.Toolkit;
import java.awt.image.PixelGrabber;
import java.net.URL;

import javax.swing.ImageIcon;

public class URLImageSource implements ImageSource {
	private URL url;
	
	private Image image;
	private int pixels[];

	public URLImageSource(URL url) {
		this.url = url;

		image = Toolkit.getDefaultToolkit().getImage(this.url);
		MediaTracker m = new MediaTracker(new Panel());
		m.addImage(image, 0);

		try {
			m.waitForAll();
		} catch (InterruptedException e) {
			System.err.println("ImageTexture: Wait for image loading was interrupted.");
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
			System.err.println("ImageTexture: Pixel grabbing interrupted.");
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
