/*
 * Copyright 2023 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.primitives;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.physics.Absorber;
import org.almostrealism.physics.Clock;
import org.almostrealism.physics.Fast;
import org.almostrealism.physics.PhysicalConstants;
import org.almostrealism.texture.GraphicsConverter;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.OutputStream;

/**
 * An {@link AbsorptionPlane} represents a plane in space that absorbs photons
 * that are within a certain (somewhat small) distance from the surface of the
 * plane. This object can be used as a sort of simulated camera film, as it keeps
 * track of where it photon is absorbed and can be used to construct an image.
 * 
 * @author  Michael Murray
 */
public class AbsorptionPlane extends Plane implements Absorber, Fast {
	public static double displayCoords = Math.pow(10.0, 4.0);
	
	private Clock clock;
	
	private int w, h;
	private final double max = Math.pow(10.0, 0.0);
	private double pixel;
	private double[][] energy;
	private RGB[][] image;
	
	private boolean noDisplay;
	private int displayTicks;
	private final int displaySleep = 1000;
	private JPanel display;
	
	/**
	 * @param p  The pixel size of the absorption plane (usually measured in micrometers).
	 */
	public void setPixelSize(double p) {
		this.pixel = p;
		super.setWidth(this.w * this.pixel);
		super.setHeight(this.h * this.pixel);
	}
	
	/**
	 * @return  The pixel size of the absorption plane (usually measured in micrometers).
	 */
	public double getPixelSize() { return this.pixel; }
	
	/**
	 * @param w  The width of the absorption plane measured as a number of cells. The size
	 *           of a cell is given by the getPixelSize method.
	 */
	@Override
	public void setWidth(double w) { this.w = (int) w; super.setWidth(this.w * this.pixel); }
	
	/**
	 * @return  The width of the absorption plane measured as a number of cells. The size
	 *          of a cell is given by the getPixelSize method.
	 */
	@Override
	public double getWidth() { return this.w; }
	
	/**
	 * @param h  The height of the absorption plane measured as a number of cells. The size
	 *           of a cell is given by the getPixelSize method.
	 */
	@Override
	public void setHeight(double h) { this.h = (int) h; super.setHeight(this.h * this.pixel); }
	
	/**
	 * @return  The height of the absorption plane measured as a number of cells. The size
	 *          of a cell is given by the getPixelSize method.
	 */
	@Override
	public double getHeight() { return this.h; }

	/**
	 * @param p  {x, y, z} - The vector pointing upwards across the surface of this
	 *           absorption plane. This vector must be orthagonal to the surface normal.
	 */
	public void setOrientation(double[] p) { this.up = p; this.across = null; }

	@Override
	public void setAbsorbDelay(double t) { }

	@Override
	public void setOrigPosition(double[] x) { }

	@Override
	public boolean absorb(Vector x, Vector p, double energy) {
		double d = Math.abs(x.dotProduct(new Vector(normal.get().evaluate(), 0)));
		double r = 1.0;
//		if (AbsorptionPlane.verbose > 0.0) r = Math.random();
//
//		if (r < AbsorptionPlane.verbose)
//			System.out.println("AbsorptionPlane: " + d);

		if (d > this.thick) return false;

		if (this.energy == null)
			this.energy = new double[this.w][this.h];

		if (this.across == null)
			this.across = new Vector(this.up).crossProduct(new Vector(normal.get().evaluate(), 0)).toArray();
		
		if (this.image == null) {
			this.image = new RGB[this.w][this.h];
			
			if (!noDisplay)
				for (int i = 0; i < this.w; i++)
				for (int j = 0; j < this.h; j++)
					this.image[i][j] = new RGB(0.0, 0.0, 0.0);
		}
		
		double a = x.dotProduct(new Vector(this.across)) / this.pixel;
		double b = x.dotProduct(new Vector(this.up)) / this.pixel;
		a = (this.h / 2.0) - a;
		b = (this.w / 2.0) + b;
		
//		if (r < AbsorptionPlane.displayCoords * AbsorptionPlane.verbose)
//			System.out.println("AbsorptionPlane: " + a + ", " + b);
		
		if (a > 0.0 && b > 0.0 && a < this.w && b < this.h) {
			int i = (int) a;
			int j = (int) b;
			this.energy[i][j] += energy;
			
			double n = 1000 * PhysicalConstants.HC / energy;
			
//			if (r < AbsorptionPlane.verbose)
//				System.out.println("AbsorptionPlane: " + n + " nanometers.");
			
			if (this.image[i][j] == null)
				this.image[i][j] = new RGB(n);
			else
				this.image[i][j].addTo(new RGB(n));
		} else {
			return false;
		}
		
		if (!noDisplay && displayTicks % displaySleep == 0) {
			if (this.display != null && this.display.getGraphics() != null) {
				Graphics g = this.display.getGraphics();
				this.drawImage(g);
				displayTicks = 1;
			}
		} else {
			displayTicks++;
		}
		
		return true;
	}

	@Override
	public Producer<PackedCollection> emit() { return null; }

	@Override
	public double getEmitEnergy() { return 0; }

	@Override
	public Producer<PackedCollection> getEmitPosition() { return null; }

	@Override
	public double getNextEmit() { return Double.MAX_VALUE; }

	@Override
	public void setClock(Clock c) { this.clock = c; }

	@Override
	public Clock getClock() { return this.clock; }
	
	public void drawImage(Graphics g) {
		System.out.println("AbsorptionPlane.drawImage");
		g.drawImage(GraphicsConverter.convertToAWTImage(getImage()), 0, 0, display);
	}
	
	public void writeImage(OutputStream out) throws IOException {
		if (this.energy == null) {}
//		TODO  Need to write image
//		ImageCanvas.writeImage(this.getImage(), out, ImageCanvas.PPMEncoding);
	}
	
	public void saveImage(String file) throws IOException {
		if (this.energy == null) {}
		
//		TODO  Need to write image
		/*
		if (file.endsWith("ppm"))
			ImageCanvas.encodeImageFile(this.getImage(), new File(file),
										ImageCanvas.PPMEncoding);
		else
			ImageCanvas.encodeImageFile(this.getImage(), new File(file),
										ImageCanvas.JPEGEncoding);
		*/
	}
	
	public void enableDisplay() { this.noDisplay = false; }
	public void disableDisplay() { this.noDisplay = true; }
	
	public boolean imageAvailable() { return this.image != null; }
	
	public RGB[][] getImage() {
		if (this.image == null) return new RGB[1][0];
		return this.image;
	}
	
	public RGB[][] getEnergyMap() {
		if (this.energy == null) return new RGB[1][0];
		
		RGB[][] image = new RGB[this.energy.length][this.energy[0].length];
		
		for (int i = 0; i < image.length; i++) {
			for (int j = 0; j < image[i].length; j++) {
				double value = this.energy[i][j] / this.max;
				image[i][j] = new RGB(value, value, value);
			}
		}
		
		return image;
	}
	
	public JPanel getDisplay() {
		if (this.display != null) return this.display;
		
		this.display = new JPanel() {
			public void paint(Graphics g) {
				AbsorptionPlane.this.drawImage(g);
			}
		};
		
		return this.display;
	}
}
