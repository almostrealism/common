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

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.image.MemoryImageSource;
import java.awt.image.RenderedImage;

import javax.swing.ImageIcon;

import org.almostrealism.color.ColorProducer;
import org.almostrealism.color.RGB;

/**
 * The GraphicsConverter class provides static methods that allow conversion between colors
 * and images stored as RGB objects and those stored as AWT colors.
 * 
 * @author Mike Murray
 */
public class GraphicsConverter {
	public static final int image32Bit = 2;
	public static final int image8Bit = 4;
	
	/**
	 * Converts the specified AWT Color object to an RGB object.
	 */
	public static RGB convertToRGB(Color color) {
		double r = color.getRed() / 255d;
		double g = color.getGreen() / 255d;
		double b = color.getBlue() / 255d;
		
		return new RGB(r, g, b);
	}
	
	/**
	 * Converts the specified RGB object to an AWT Color object.
	 */
	public static Color convertToAWTColor(RGB color) {
		return new Color((float)Math.min(1.0, Math.abs(color.getRed())),
		        		(float)Math.min(1.0, Math.abs(color.getGreen())),
		        		(float)Math.min(1.0, Math.abs(color.getBlue())));
	}
	
	/**
	 * Converts the specified AWT Image object to an array of RGB objects.
	 * The array locations map to pixels in the image. The image will be
	 * converted to the standard RGB color model if it is not already
	 * and the alpha channel will be ignored.
	 */
	public static RGB[][] convertToRGBArray(Image image) {
		image = new ImageIcon(image).getImage();
		BufferedImage bufferedImage = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_RGB);
		
		Graphics gr = bufferedImage.createGraphics();
		gr.drawImage(image, 0, 0, null);
		gr.dispose();
		
		return GraphicsConverter.convertToRGBArray(bufferedImage);
	}
	
	public static int[] extract32BitImage(RenderedImage im) {
		int w = im.getWidth();
		int h = im.getHeight();
		
		BufferedImage bim = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		bim.setData(im.copyData(null));
		
		int index = 2;
		int rgb[] = new int[2 + w * h];
		rgb[0] = w;
		rgb[1] = h;
		
		for(int j = 0; j < h; j++) {
			for(int i = 0; i < w; i++) {
				rgb[index++] = bim.getRGB(i, j);
			}
		}
		
		return rgb;
	}
	
	public static byte[] extract8BitImage(RenderedImage im) {
		int w = im.getWidth();
		int h = im.getHeight();
		
		BufferedImage bim = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		bim.setData(im.copyData(null));
		
		int index = 8;
		byte rgb[] = new byte[8 + w * h];
		
		rgb[0] = (byte) ((w >> 24) & 255);
		rgb[1] = (byte) ((w >> 16) & 255);
		rgb[2] = (byte) ((w >> 8) & 255);
		rgb[3] = (byte) (w & 255);
		rgb[4] = (byte) ((h >> 24) & 255);
		rgb[5] = (byte) ((h >> 16) & 255);
		rgb[6] = (byte) ((h >> 8) & 255);
		rgb[7] = (byte) (h & 255);
		
		for(int j = 0; j < h; j++) {
			for(int i = 0; i < w; i++) {
				int c = bim.getRGB(i, j);
				int r = (c >> 16) & 255;
				int g = (c >> 8) & 255;
				int b = c & 255;
				
				r = r / 4;
				g = g / 4;
				b = b / 4;
				
				c = (r << 4) + (g << 2) + b;
				
				rgb[index++] = (byte) c;
			}
		}
		
		return rgb;
	}
	
	public static RGB[][] convertToRGBArray(BufferedImage bufferedImage) {
		return GraphicsConverter.convertToRGBArray(bufferedImage, 0, 0,
													bufferedImage.getWidth(),
													bufferedImage.getHeight());
	}
	
	public static RGB[][] convertToRGBArray(int pixel[], int off, int x, int y, int w, int h, int imageW) {
		RGB rgb[][] = new RGB[w][h];
		
		for(int j = 0; j < h; j++) {
			for(int i = 0; i < w; i++) {
				int color = pixel[off + (j + y) * imageW + (i + x)];
				
				int rChannel = (color >> 16) & 255;
				int gChannel = (color >> 8) & 255;
				int bChannel = color & 255;
				
				double r = rChannel / 255d;
				double g = gChannel / 255d;
				double b = bChannel / 255d;
				
				rgb[i][j] = new RGB(r, g, b);
			}
		}
		
		return rgb;
	}
	
	public static RGB[][] convertToRGBArray(BufferedImage bufferedImage,
											int xoff, int yoff, int w, int h) {
		RGB rgbArray[][] = new RGB[w][h];
		
		for(int i = 0; i < rgbArray.length; i++) {
			for(int j = 0; j < rgbArray[i].length; j++) {
				int color = bufferedImage.getRGB(xoff + i, yoff + j);
				
				int rChannel = (color >> 16) & 255;
				int gChannel = (color >> 8) & 255;
				int bChannel = color & 255;
				
				double r = rChannel / 255d;
				double g = gChannel / 255d;
				double b = bChannel / 255d;
				
				rgbArray[i][j] = new RGB(r, g, b);
			}
		}
		
		return rgbArray;
	}
	
	/**
	 * Converts the specified array of RGB objects to an AWT Image object.
	 * The array locations map to pixels in the image. The image produced
	 * uses the RGB color model with no alpha channel.
	 */
	public static Image convertToAWTImage(ColorProducer image[][]) {
		int data[] = new int[image.length * image[0].length];
		
		int index = 0;
		boolean wasNull = false;
		
		for (int j = 0; j < image[0].length; j++) {
			i: for (int i = 0; i < image.length; i++) {
				if (image[i][j] == null) {
					wasNull = true;
					index++;
					continue i;
				}
				
				RGB c = image[i][j].evaluate(null);
				
				int r = (int)(Math.min(1.0, Math.abs(c.getRed())) * 255);
				int g = (int)(Math.min(1.0, Math.abs(c.getGreen())) * 255);
				int b = (int)(Math.min(1.0, Math.abs(c.getBlue())) * 255);
				
				data[index++] = 255 << 24 | r << 16 | g << 8 | b;
			}
		}
		
		if (wasNull)
			System.out.println("GraphicsConverter: Some image data was null.");
		
		return Toolkit.getDefaultToolkit().createImage(new MemoryImageSource(image.length, image[0].length, data, 0, image.length));
	}
}
