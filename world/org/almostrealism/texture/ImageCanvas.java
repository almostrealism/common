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
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

import javax.imageio.ImageIO;
import javax.swing.JPanel;

import org.almostrealism.color.ColorProducer;
import org.almostrealism.color.RGB;


/**
 * An {@link ImageCanvas} object stores image data and paints the parent
 * {@link JPanel} using the image.
 * 
 * @author Mike Murray
 */
public class ImageCanvas extends JPanel {
  private int screenX, screenY;
  private double xScale, yScale;
  private double xOff, yOff;
  
  private RGB image[][];
  private RGB color;
  private int next;
  
  /** The integer code for an RGB list image encoding. */
  public static final int RGBListEncoding = 7;
  /** The integer code for a PIX image encoding. */
  public static final int PIXEncoding = 5;
  /** The integer code for a PPM image encoding. */
  public static final int PPMEncoding = 4;
  /** The integer code for a JPEG image encoding. */
  public static final int JPEGEncoding = 6;

  	
  
	public ImageCanvas(int w, int h) {
		this(w, h, 1.0, 1.0, 0.0, 0.0);
	}
	
	/**
	 * Constructs a new ImageCanvas object.
	 * 
	 * @param w  Image width.
	 * @param h  Image height.
	 * @param xScale  X scale factor.
	 * @param yScale  Y scale factor.
	 * @param xOff  X offset.
	 * @param yOff  Y offset.
	 */
	public ImageCanvas(int w, int h, double xScale, double yScale, double xOff, double yOff) {
		this.image = new RGB[w][h];
		this.color = new RGB(0.0, 0.0, 0.0);
		
		this.screenX = w;
		this.screenY = h;
		this.xScale = xScale;
		this.yScale = yScale;
		this.xOff = xOff;
		this.yOff = yOff;
		
		this.clear();
	}
	
	public void setXScale(double xScale) { this.xScale = xScale; }
	
	public void setYScale(double yScale) { this.yScale = yScale; }
	
	public void setXOffset(double xOff) { this.xOff = xOff; }
	
	public void setYOffset(double yOff) { this.yOff = yOff; }
	
	public double getXScale() { return this.xScale; }
	
	public double getYScale() { return this.yScale; }
	
	public double getXOffset() { return this.xOff; }
	
	public double getYOffset() { return this.yOff; }
	
	/**
	 * Plots a point on this ImageCanvas object.
	 * 
	 * @param x  X coordinate.
	 * @param y  Y coordinate.
	 * @param c  Color to use for point.
	 */
	public void plot(double x, double y, RGB c) {
		int sx = (int)(((x + this.xOff) * this.xScale) + (this.screenX / 2.0));
		int sy = (int)(-((y + this.yOff) * this.yScale) + (this.screenY / 2.0));
		
		this.next++;
		
		if (sx >= 0 && sx < this.image.length && sy >= 0 && sy < this.image[sx].length) {
			this.image[sx][sy] = c;
			this.color = this.image[sx][sy];
		}
		
		this.repaint();
	}
	
	/**
	 * Sets the color at a pixel in the image data stored by this ImageCanvas object.
	 * 
	 * @param i  Index into image array.
	 * @param j  Index into image array.
	 * @param rgb  RGB object to use for pixel color.
	 */
	public void setImageData(int i, int j, RGB rgb) { this.image[i][j] = rgb; }
	
	/**
	 * Sets the image data stored by this ImageCanvas object.
	 * 
	 * @param image  RGB array to use for image data.
	 */
	public void setImageData(RGB image[][]) { this.image = image; }
	
	/**
	 * @return  The image data stored by this ImageCanvas object.
	 */
	public RGB[][] getImageData() { return this.image; }
	
	/**
	 * Clears this ImageCanvas object.
	 */
	public void clear() {
		for (int i = 0; i < this.image.length; i++) {
			for (int j = 0; j < this.image[i].length; j++) {
				this.image[i][j] = new RGB(0.0, 0.0, 0.0);
			}
		}
	}
	
	/**
	 * Writes the image data stored by this ImageCanvas object out to the specified file.
	 * 
	 * @param file  File name.
	 */
	public void writeImage(String file) {
		try {
			ImageCanvas.encodeImageFile(this.image,
							new File(file),
							ImageCanvas.JPEGEncoding);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Overrides normal JPanel paint method.
	 */
	public void paint(Graphics g) {
		Image img = GraphicsConverter.convertToAWTImage(this.image);
		g.drawImage(img, 0, 0, Color.black, this);
	}

	public static void writeImage(ColorProducer image[][], OutputStream o, int encoding)
						throws IOException {
		if (encoding == ImageCanvas.RGBListEncoding) {
			ObjectOutputStream out = new ObjectOutputStream(o);
			
			for (int i = 0; i < image.length; i++) {
				for (int j = 0; j < image[i].length; j++) {
					if (image[i][j] == null) {
						new RGB(0.0, 0.0, 0.0).writeExternal(out);
					} else {
						image[i][j].evaluate(null).writeExternal(out);
					}
				}
			}
			
			out.flush();
		} if (encoding == ImageCanvas.PPMEncoding) {
			java.io.PrintWriter out = new java.io.PrintWriter(o);
			
			out.println("P3");
			
			out.println("255");
			
			for (int j = 0; j < image[0].length; j++) {
				for (int i = 0; i < image.length; i++) {
					if (image[i][j] == null) {
						out.println("0 0 0");
					} else {
						RGB c = image[i][j].evaluate(null);
						int r = (int)(255 * c.getRed());
						int g = (int)(255 * c.getGreen());
						int b = (int)(255 * c.getBlue());
						out.println(r + " " + g + " " + b);
					}
				}
			}
			
			out.flush();
			
			if (out.checkError() == true)
				throw new IOException("IO error while writing image data");
		} else if (encoding == ImageCanvas.PIXEncoding) {
			int w = image.length;
			int h = image[0].length;
			
			byte b[] = new byte[4 * w * h + 10];
			
			b[0] = (byte)(w >> 8);
			b[1] = (byte)w;
			b[2] = (byte)(h >> 8);
			b[3] = (byte)h;
			b[9] = 24;
			
			int index = 10;
			
			for (int j = 0; j < h; j++) {
				for (int i = 0; i < w; i++) {
					RGB c = image[i][j].evaluate(null);
					b[index++] = 1;
					b[index++] = (byte)(255 * c.getBlue());
					b[index++] = (byte)(255 * c.getGreen());
					b[index++] = (byte)(255 * c.getRed());
				}
			}
			
			o.write(b);
		} else if (encoding == JPEGEncoding) {
		    BufferedImage bimg = new BufferedImage(image.length, image[0].length, BufferedImage.TYPE_INT_ARGB);
		    Graphics g = bimg.createGraphics();
		    
		    g.drawImage(GraphicsConverter.convertToAWTImage(image), 0, 0, null);
		    
		    ImageIO.write(bimg, "png", o);
		}
	}

	/**
	 * Encodes the image represented by the specified RGB array using the encoding
	 * specified by the integer encoding code and saves the encoded data in the
	 * file represented by the specified File object. If the encoding code is not
	 * recognized, the method returns.
	 */
	public static void encodeImageFile(ColorProducer image[][], File file, int encoding) throws IOException {
		try (OutputStream o = new FileOutputStream(file)) {
			ImageCanvas.writeImage(image, o, encoding);
			o.flush();
		}
	}
}