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

package org.almostrealism.primitives;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.MemoryBank;

import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorMath;
import org.almostrealism.color.Colorable;
import org.almostrealism.color.RGB;
import org.almostrealism.geometry.Ray;
import org.almostrealism.hardware.DynamicProducerForMemoryData;
import org.almostrealism.physics.Absorber;
import io.almostrealism.relation.Producer;
import org.almostrealism.space.Volume;
import org.almostrealism.physics.Clock;

import org.almostrealism.utils.PriorityQueue;

import org.almostrealism.projection.PinholeCamera;

/**
 * @author  Michael Murray
 */
public class PinholeCameraAbsorber extends PinholeCamera implements Absorber, Volume<Ray> {
	private Clock clock;
	
	private Pinhole pinhole;
	private AbsorptionPlane plane;
	private double planePos[];
	
	private Colorable colorable;
	private Vector location;

	/**
	 * This method exists to support file decoding, but it will not result in a usable
	 * {@link PinholeCameraAbsorber}. The {@link #init(Pinhole, AbsorptionPlane, double)}
	 * method must be called to setup the {@link AbsorptionPlane}.
	 */
	public PinholeCameraAbsorber() { }

	public PinholeCameraAbsorber(double fNum, double focalLength, Vector norm, Vector orient) {
		this(fNum, focalLength, norm.getData(), orient.getData());
	}

	protected PinholeCameraAbsorber(double fNum, double focalLength,
								double norm[], double orient[]) {
		initPlane(norm, orient);
		
		this.pinhole = new Pinhole();
		this.pinhole.setRadius(focalLength / (2.0 * fNum));
		this.pinhole.setSurfaceNormal((Producer) vector(norm[0], norm[1], norm[2]));
		this.pinhole.setOrientation(orient);
		
		this.planePos = VectorMath.multiply(norm, -focalLength, true);
	}
	
	public PinholeCameraAbsorber(Pinhole pinhole, AbsorptionPlane plane, double focalLength) {
		this.init(pinhole, plane, focalLength);
	}
	
	public void init(Pinhole pinhole, AbsorptionPlane plane, double focalLength) {
		this.pinhole = pinhole;
		this.plane = plane;
		
		double norm[] = pinhole.getSurfaceNormal().get().evaluate().toArray();
		this.planePos = VectorMath.multiply(norm, -focalLength, true);
	}

	protected void initPlane(double norm[], double orient[]) {
		if (this.plane == null) {
			this.plane = new AbsorptionPlane();
			this.plane.setSurfaceNormal((Producer) vector(norm[0], norm[1], norm[2]));
			this.plane.setOrientation(VectorMath.clone(orient));
		}
	}
	
	public void setWidth(int w) { this.plane.setWidth(w); }
	public void setHeight(int h) { this.plane.setHeight(h); }
	public int getWidth() { return (int) this.plane.getWidth(); }
	public int getHeight() { return (int) this.plane.getHeight(); }
	public void setPixelSize(double p) { this.plane.setPixelSize(p); }
	public double getPixelSize() { return this.plane.getPixelSize(); }
	
	public AbsorptionPlane getAbsorptionPlane() { return this.plane; }
	public void setAbsorptionPlane(AbsorptionPlane plane) { this.plane = plane; }
	public Pinhole getPinhole() { return pinhole; }
	public void setPinhole(Pinhole pinhole) { this.pinhole = pinhole; }
	public double[] getPlanePosition() { return planePos; }
	public void setPlanePosition(double[] planePos) { this.planePos = planePos; }
	public Colorable getColorable() { return colorable; }
	public void setColorable(Colorable c) { this.colorable = c; }

	@Override
	public void setLocation(Vector p) { this.location = p; }

	@Override
	public Vector getLocation() { return this.location; }

	@Override
	public void setViewingDirection(Vector v) {
		initPlane(v.toArray(), new double[3]);
		this.plane.setSurfaceNormal((Producer) value(v));
	}

	@Override
	public Vector getViewingDirection() {
		return new Vector(plane.getSurfaceNormal().get().evaluate(), 0);
	}

	@Override
	public void setUpDirection(Vector v) {
		initPlane(new double[3], v.toArray());
		this.plane.setOrientation(v.toArray());
	}

	@Override
	public Vector getUpDirection() {
		return new Vector(this.plane.getOrientation());
	}

	public double getFNumber() { return getFocalLength() / (2.0 * this.pinhole.getRadius()); }

	@Override
	public Producer<Ray> rayAt(Producer<Pair> pos, Producer<Pair> sd) {
		return new DynamicProducerForMemoryData<Ray>(args -> {
				Pair ij = pos.get().evaluate(args);
				Pair screenDim = sd.get().evaluate(args);

				double u = ij.getX() / (screenDim.getX());
				double v = (ij.getY() / screenDim.getY());
				// v = 1.0 - v;

				if (colorable != null) {
					int tot = 6;
					RGB c = new RGB(0.0, 0.0, 0.0);

					if (plane.imageAvailable()) {
						RGB im[][] = plane.getImage();
						int a = (int) (u * im.length);
						if (a == im.length) a = im.length -1;
						int b = (int) (v * im[a].length);

						int x0 = a - 4;
						int y0 = b - 4;
						int x1 = a + 4;
						int y1 = b + 4;

						PriorityQueue q = new PriorityQueue();

						i: for (int ai = x0; ai < x1; ai++) {
							if (ai < 0 || ai >= im.length) continue i;

							j: for (int aj = y0; aj < y1; aj++) {
								if (aj < 0 || aj >= im[ai].length) continue j;
								if (c.equals(im[ai][aj])) continue j;

								double d = (ai - a) * (ai - a) + (aj - b) * (aj - b);
								if (d == 0)
									d = 1.0;
								else
									d = 1 / Math.sqrt(d);

								if (q.peek() < d || q.size() < tot) q.put(im[ai][aj], d);
								if (q.size() > tot) q.next();
							}
						}

						while (q.size() > 0) {
							double p = q.peek();
							RGB cl = (RGB) q.next();
							c.addTo(cl.multiply(p / tot));
						}

						// c = im[a][b];
					}

					colorable.setColor(c.getRed(), c.getGreen(), c.getBlue());
				}

				double x[] = plane.getSpatialCoords(new double[] {u, v});
				VectorMath.addTo(x, planePos);
				double d[] = VectorMath.multiply(x, -1.0 / VectorMath.length(x), true);

				Vector vx = new Vector(x[0], x[1], x[2]);
				Vector vd = new Vector(d[0], d[1], d[2]);
				vx.addTo(location);

				return new Ray(vx, vd);
			}, size -> (MemoryBank) Ray.bank(size));
	}

	@Override
	public void setClock(Clock c) { this.clock = c; }

	@Override
	public Clock getClock() { return this.clock; }

	@Override
	public boolean inside(Producer<PackedCollection> x) { return pinhole.inside(x) || plane.inside(x); }

	@Override
	public Producer getValueAt(Producer point) { return null; }

	@Override
	public Producer<PackedCollection> getNormalAt(Producer<PackedCollection> x) { return plane.getNormalAt(x); }

	@Override
	public double intersect(Vector x, Vector p) {
		return Math.min(pinhole.intersect(x, p), plane.intersect(x, p));
	}

	@Override
	public double[] getSurfaceCoords(Producer<PackedCollection> xyz) { return plane.getSurfaceCoords(xyz); }

	@Override
	public double[] getSpatialCoords(double uv[]) { return plane.getSpatialCoords(uv); }

	@Override
	public boolean absorb(Vector x, Vector p, double energy) {
		if (this.pinhole.absorb(x, p, energy))
			return true;
		else if (this.plane.absorb(x.subtract(new Vector(planePos)), p, energy))
			return true;
		else
			return false;
	}

	@Override
	public Producer<PackedCollection> emit() { return null; }

	@Override
	public double getEmitEnergy() { return 0.0; }

	@Override
	public Producer<PackedCollection> getEmitPosition() { return null; }

	@Override
	public double getNextEmit() { return Integer.MAX_VALUE; }
}
