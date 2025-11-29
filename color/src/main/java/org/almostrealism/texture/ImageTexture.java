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

package org.almostrealism.texture;

import io.almostrealism.relation.Editable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.color.computations.GeneratedColorProducer;

import java.awt.*;
import java.awt.image.PixelGrabber;
import java.net.MalformedURLException;
import java.net.URL;


/**
 * Provides image-based texture mapping for surfaces.
 *
 * <p>An {@code ImageTexture} loads a 2D image and maps it onto 3D surfaces using
 * various projection methods. This enables surfaces to display photographic textures,
 * patterns, or any image-based detail.</p>
 *
 * <h2>Projection Modes</h2>
 * <ul>
 *   <li>{@link #SPHERICAL_PROJECTION}: Wraps the image around a sphere using
 *       latitude/longitude mapping. Best for globe-like objects.</li>
 *   <li>{@link #XY_PLANAR_PROJECTION}: Projects the image onto the XY plane.
 *       Uses X for U and Y for V coordinates.</li>
 *   <li>{@link #XZ_PLANAR_PROJECTION}: Projects the image onto the XZ plane.
 *       Uses X for U and Z for V coordinates.</li>
 *   <li>{@link #YZ_PLANAR_PROJECTION}: Projects the image onto the YZ plane.
 *       Uses Y for U and Z for V coordinates.</li>
 * </ul>
 *
 * <h2>UV Coordinate System</h2>
 * <p>Textures use UV coordinates in the range [0.0, 1.0]:</p>
 * <ul>
 *   <li>U (horizontal): 0.0 = left edge, 1.0 = right edge</li>
 *   <li>V (vertical): 0.0 = top edge, 1.0 = bottom edge</li>
 * </ul>
 *
 * <h2>Scaling and Offset</h2>
 * <p>The texture can be tiled and offset using scale/offset parameters:</p>
 * <ul>
 *   <li>{@code xScale/yScale}: Number of texture repetitions (2.0 = tile twice)</li>
 *   <li>{@code xOff/yOff}: Texture offset (0.5 = shift by half the texture)</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Spherical texture for a planet
 * ImageTexture earth = new ImageTexture(
 *     ImageTexture.SPHERICAL_PROJECTION,
 *     new URL("http://example.com/earth.jpg"),
 *     1.0, 1.0,  // No tiling
 *     0.0, 0.0   // No offset
 * );
 *
 * // Tiled floor texture
 * ImageTexture floor = new ImageTexture(
 *     ImageTexture.XZ_PLANAR_PROJECTION,
 *     new URL("http://example.com/tiles.jpg"),
 *     10.0, 10.0,  // Tile 10x10
 *     0.0, 0.0
 * );
 *
 * // Get color at a surface point
 * RGB color = texture.operate(surfacePoint);
 * }</pre>
 *
 * @see Texture
 * @see GraphicsConverter
 * @author Mike Murray
 */
public class ImageTexture implements Texture, Editable {
  private static final String[] propNames = {"Image Source", "X Scale", "Y Scale",
  											"X Offset", "Y Offset"};
  private static final String[] propDesc = {"URL to load image data from", "X scale factor", "Y scale factor",
  											"X offset factor", "Y offset factor"};
  private static final Class[] propTypes = {URL.class, Double.class, Double.class};
  
  public static final int SPHERICAL_PROJECTION = 1;
  public static final int XY_PLANAR_PROJECTION = 2;
  public static final int XZ_PLANAR_PROJECTION = 3;
  public static final int YZ_PLANAR_PROJECTION = 4;
  
  private final Vector northP = new Vector(0.0, 1.0, 0.0);
  private final Vector equatorP = new Vector(1.0, 0.0, 0.0);
  private final Vector crossP = this.northP.crossProduct(this.equatorP);
  
  private final int type;
  
  private URL url;
  private int width, height;
  private double xScale, yScale;
  private double xOff, yOff;
  private int[] pixels;

  	/**
  	 * Constructs a new ImageTexture object.
  	 */
  	public ImageTexture() {
  	    this.xScale = 1.0;
  	    this.yScale = 1.0;
  	    this.xOff = 0.0;
  	    this.yOff = 0.0;
  	    
  	    this.type = ImageTexture.SPHERICAL_PROJECTION;
  	    
  	    try {
  	        this.url = new URL("http://j3d.sf.net/texture.jpeg");
  	    } catch (MalformedURLException murl) {}
  	}
  	
  	/**
  	 * Constructs a new ImageTexture object of unit square size.
  	 * 
  	 * @param type  Integer code that specifies the method to use for "wrapping" the image on a surface.
  	 * @param url  URL object pointing to image.
  	 * 
  	 * @throws IllegalArgumentException  If the value for type is not valid.
  	 * @throws RuntimeException  If image fails to load properly.
  	 */
  	public ImageTexture(int type, URL url) {
  		this(type, url, 1.0, 1.0, 0.0, 0.0);
  	}
  	
  	/**
  	 * Constrcts a new ImageTexture object with the specified scaling factors.
  	 * 
  	 * @param type  Integer code that specified the method to use for "wrapping" the image on a surface.
  	 * @param url  URL object pointing to image.
  	 * @param xScale  X scale factor.
  	 * @param yScale  Y scale factor.
  	 * @param xOff  X offset.
  	 * @param yOff  Y offset.
  	 * 
  	 * @throws IllegalArgumentException  If the value for type is not valid.
  	 * @throws RuntimeException  If image fails to load properly.
  	 */
  	public ImageTexture(int type, URL url, double xScale, double yScale, double xOff, double yOff) {
  		this.type = type;
  		
  		if (this.type < 1 || this.type > 4) throw new IllegalArgumentException("Invalid type code: " + type);
  		
  		this.xScale = xScale;
  		this.yScale = yScale;
  		this.xOff = xOff;
  		this.yOff = yOff;
  		
  		this.url = url;
  		
  		this.update();
  	}

  	public URL getURL() { return url; }
  	
  	protected void update() {
  		Image image = Toolkit.getDefaultToolkit().getImage(this.url);
  		MediaTracker m = new MediaTracker(new Panel());
  		m.addImage(image, 0);
  		
  		try {
  			m.waitForAll();
  		} catch (InterruptedException e) {
  			System.err.println("ImageTexture: Wait for image loading was interrupted.");
  		}
  		
  		if (m.isErrorAny()) throw new RuntimeException("ImageTexture: Error loading image.");
  		
  		this.width = image.getWidth(null);
  		this.height = image.getHeight(null);
  		this.pixels = new int[width * height];
  		
  		PixelGrabber p = new PixelGrabber(image, 0, 0, this.width, this.height, this.pixels, 0, this.width);
  		
  		try {
  			p.grabPixels();
  		} catch (InterruptedException e) {
  			System.err.println("ImageTexture: Pixel grabbing interrupted.");
  		}
  	}

	/**
	 * This method does nothing, as an {@link ImageTexture} depends only on
	 * static pixel data.
	 */
	public void compact() { }
  	
  	/**
  	 * Returns the an RGB object representing the color of this ImageTexture object
  	 * at the specified u, v coordinates.
  	 * 
  	 * @param u  u coordinate between 0.0 and 1.0
  	 * @param v  v coordinate between 0.0 and 1.0
  	 * @return  The color at the specified location (black if the pixel data is not loaded).
  	 */
  	public RGB getColorAt(double u, double v) {
  	    if (this.pixels == null) return new RGB(0.0, 0.0, 0.0);
  	    
  		return GraphicsConverter.convertToRGB(new Color(this.pixels[
											(int)((((u + this.xOff)* this.xScale) % 1) * (this.width - 1) +
  											(int)((((v + this.yOff)* this.yScale) % 1) * (this.height - 1)) * width)]));
  	}
  	
  	/**
  	 * Returns the an RGB object representing the color of this ImageTexture object
  	 * at the specified u, v coordinates.
  	 * 
  	 * @param u  u coordinate between 0.0 and 1.0
  	 * @param v  v coordinate between 0.0 and 1.0
  	 * @param xScale  X scale factor
  	 * @param yScale  Y scale factor
  	 * @param xOff  X offset
  	 * @param yOff  Y offset
  	 * @throws NullPointerException  If pixel data is not loaded.
  	 * @return The color at the specified location
  	 */
  	public RGB getColorAt(double u, double v, double xScale, double yScale, double xOff, double yOff) {
  	    if (this.pixels == null) throw new NullPointerException("ImageTexture: Pixel data not loaded.");
  	    
  	    return GraphicsConverter.convertToRGB(new Color(this.pixels[
  	        									(int)((((u + xOff) * xScale) % 1) * (this.width - 1) +
  	          								(int)((((v + yOff) * yScale) % 1) * (this.height - 1)) * width)]));
  	}
  	
	/**
	 * @see org.almostrealism.texture.Texture#operate(Vector)
	 * 
	 * @throws NullPointerException  If pixel data is not loaded.
	 */
	public RGB operate(Vector t) {
		Vector point = new Vector(t.getX(), t.getY(), t.getZ());

		if (this.type == ImageTexture.SPHERICAL_PROJECTION) {
			Vector p = point.divide(point.length());
			
			double north = Math.acos(-this.northP.dotProduct(p));
			double equator = Math.acos(p.dotProduct(this.equatorP) / Math.sin(north)) / (2 * Math.PI);
			
			double u = (this.crossP.dotProduct(p) < 0) ? equator : 1 - equator;
			double v = north / Math.PI;
			
			return this.getColorAt(u, v);
		} else if (this.type == ImageTexture.XY_PLANAR_PROJECTION) {
		    return this.getColorAt(point.getX(), point.getY());
		} else if (this.type == ImageTexture.XZ_PLANAR_PROJECTION) {
		    return this.getColorAt(point.getX(), point.getZ());
		} else if (this.type == ImageTexture.YZ_PLANAR_PROJECTION) {
		    return this.getColorAt(point.getY(), point.getZ());
		} else {
			return null;
		}
	}

	/**
	 * @param params {Double, Double, Double, Double}  X scale factor, Y scale factor, X offset, Y offset.
	 * @throws IllegalArgumentException  If args does not contain the correct object types.
	 * @throws NullPointerException  If pixel data is not loaded.
	 * 
	 * @see org.almostrealism.texture.Texture#getColorAt(java.lang.Object[])
	 */
	public Evaluable<PackedCollection> getColorAt(Object[] params) {
		return GeneratedColorProducer.fromProducer(this, () -> args -> {
			Vector l = args.length > 0 ? (Vector) args[0] : new Vector(1.0, 1.0, 1.0);
			Vector point = new Vector(l.getX(), l.getY(), l.getZ());

			if (!(args[0] instanceof Double)) throw new IllegalArgumentException("Illegal argument: " + args[0]);
			if (!(args[1] instanceof Double)) throw new IllegalArgumentException("Illegal argument: " + args[1]);
			if (!(args[2] instanceof Double)) throw new IllegalArgumentException("Illegal argument: " + args[2]);
			if (!(args[3] instanceof Double)) throw new IllegalArgumentException("Illegal argument: " + args[3]);


			if (ImageTexture.this.type == ImageTexture.SPHERICAL_PROJECTION) {
				Vector p = point.divide(point.length());

				double north = Math.acos(-ImageTexture.this.northP.dotProduct(p));
				double equator = Math.acos(p.dotProduct(ImageTexture.this.equatorP) / Math.sin(north)) / (2 * Math.PI);

				double u = (ImageTexture.this.crossP.dotProduct(p) < 0) ? equator : 1 - equator;
				double v = north / Math.PI;

				return ImageTexture.this.getColorAt(u, v, ((Double)args[0]).doubleValue(), ((Double)args[1]).doubleValue(),
						((Double)args[2]).doubleValue(), ((Double)args[3]).doubleValue());
			} else if (ImageTexture.this.type == ImageTexture.XY_PLANAR_PROJECTION) {
				return ImageTexture.this.getColorAt(point.getX(), point.getY(),
						((Double)args[0]).doubleValue(), ((Double)args[1]).doubleValue(),
						((Double)args[2]).doubleValue(), ((Double)args[3]).doubleValue());
			} else if (ImageTexture.this.type == ImageTexture.XZ_PLANAR_PROJECTION) {
				return ImageTexture.this.getColorAt(point.getX(), point.getZ(),
						((Double)args[0]).doubleValue(), ((Double)args[1]).doubleValue(),
						((Double)args[2]).doubleValue(), ((Double)args[3]).doubleValue());
			} else if (ImageTexture.this.type == ImageTexture.YZ_PLANAR_PROJECTION) {
				return ImageTexture.this.getColorAt(point.getY(), point.getZ(),
						((Double)args[0]).doubleValue(), ((Double)args[1]).doubleValue(),
						((Double)args[2]).doubleValue(), ((Double)args[3]).doubleValue());
			} else {
				return null;
			}
		}).get();
	}

    /** @see io.almostrealism.relation.Editable#getPropertyNames() */
	@Override
    public String[] getPropertyNames() { return ImageTexture.propNames; }

    /** @see io.almostrealism.relation.Editable#getPropertyDescriptions() */
	@Override
    public String[] getPropertyDescriptions() { return ImageTexture.propDesc; }

    /** @see io.almostrealism.relation.Editable#getPropertyTypes() */
	@Override
    public Class[] getPropertyTypes() { return ImageTexture.propTypes; }

    /** @see io.almostrealism.relation.Editable#getPropertyValues() */
	@Override
    public Object[] getPropertyValues() { return new Object[] {this.url, Double.valueOf(this.xScale), Double.valueOf(this.yScale)}; }

    /** @see io.almostrealism.relation.Editable#setPropertyValue(java.lang.Object, int) */
	@Override
    public void setPropertyValue(Object value, int index) {
    		if (index >= ImageTexture.propTypes.length) {
    			throw new IndexOutOfBoundsException("Index out of bounds: " + index);
    		} else if (!ImageTexture.propTypes[index].isInstance(value)) {
    			throw new IllegalArgumentException("Illegal argument: " + value.toString());
    		} else {
  	 		if (index == 0) this.url = (URL)value;
  	 		else if (index == 1) this.xScale = ((Double)value).doubleValue();
  	 		else if (index == 2) this.yScale = ((Double)value).doubleValue();
  	 		else if (index == 3) this.xOff = ((Double)value).doubleValue();
  	 		else if (index == 4) this.yOff = ((Double)value).doubleValue();
			
			this.update();
    		}
    }

    /**
     * @see io.almostrealism.relation.Editable#setPropertyValues(java.lang.Object[])
     */
	@Override
    public void setPropertyValues(Object[] values) {
		for (int i = 0; i < values.length; i++) {
			this.setPropertyValue(values[i], i);
		}
    }
    
	/**
	 * @return  An empty array.
	 */
	@Override
	public Producer[] getInputPropertyValues() { return new Producer[0]; }
	
	/**
	 * Does nothing.
	 */
	@Override
	public void setInputPropertyValue(int index, Producer p) { }
    
    /**
     * @return  "Image Texture".
     */
    @Override
    public String toString() { return "Image Texture"; }
}
