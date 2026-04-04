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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorMath;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.color.Colorable;
import org.almostrealism.color.RGB;
import org.almostrealism.geometry.Ray;
import org.almostrealism.physics.Absorber;
import org.almostrealism.physics.Clock;
import org.almostrealism.projection.PinholeCamera;
import org.almostrealism.physics.Volume;
import org.almostrealism.utils.PriorityQueue;

/**
 * A simulated pinhole camera that combines a {@link Pinhole} aperture with an
 * {@link AbsorptionPlane} film plane to model photon absorption.
 *
 * <p>Incoming photons are first tested against the pinhole: those that pass through
 * the hole travel on to the film plane where they are absorbed and recorded.  The
 * class also implements {@link org.almostrealism.projection.PinholeCamera} so that it
 * can be used to generate rays for a ray-tracing renderer.</p>
 *
 * @author  Michael Murray
 */
public class PinholeCameraAbsorber extends PinholeCamera implements Absorber, Volume<Ray> {
	/** The physics clock used to track simulation time. */
	private Clock clock;

	/** The aperture of this camera. */
	private Pinhole pinhole;

	/** The film plane that records absorbed photons. */
	private AbsorptionPlane plane;

	/** Offset of the film plane relative to the camera origin (in world units). */
	private double[] planePos;

	/** Optional receiver that is updated with the current image colour on each ray query. */
	private Colorable colorable;

	/** World-space location of the camera body. */
	private Vector location;

	/**
	 * This method exists to support file decoding, but it will not result in a usable
	 * {@link PinholeCameraAbsorber}. The {@link #init(Pinhole, AbsorptionPlane, double)}
	 * method must be called to setup the {@link AbsorptionPlane}.
	 */
	public PinholeCameraAbsorber() { }

	/**
	 * Constructs a camera with the specified f-number, focal length, viewing normal, and up-orientation.
	 *
	 * @param fNum        the f-number (focal length / aperture diameter)
	 * @param focalLength the focal length in world units
	 * @param norm        the direction the camera is pointing
	 * @param orient      the "up" orientation vector
	 */
	public PinholeCameraAbsorber(double fNum, double focalLength, Vector norm, Vector orient) {
		this(fNum, focalLength, norm.getData(), orient.getData());
	}

	/**
	 * Protected constructor used by subclasses or internal factory code.
	 *
	 * @param fNum        the f-number
	 * @param focalLength the focal length in world units
	 * @param norm        the normal direction as a raw double array
	 * @param orient      the up-orientation as a raw double array
	 */
	protected PinholeCameraAbsorber(double fNum, double focalLength,
									double[] norm, double[] orient) {
		initPlane(norm, orient);
		
		this.pinhole = new Pinhole();
		this.pinhole.setRadius(focalLength / (2.0 * fNum));
		this.pinhole.setSurfaceNormal(vector(norm[0], norm[1], norm[2]));
		this.pinhole.setOrientation(orient);
		
		this.planePos = VectorMath.multiply(norm, -focalLength, true);
	}
	
	/**
	 * Constructs a camera from pre-built pinhole and absorption-plane components.
	 *
	 * @param pinhole     the aperture component
	 * @param plane       the film-plane component
	 * @param focalLength the focal length in world units
	 */
	public PinholeCameraAbsorber(Pinhole pinhole, AbsorptionPlane plane, double focalLength) {
		this.init(pinhole, plane, focalLength);
	}
	
	/**
	 * Initialises this camera with pre-built pinhole and absorption-plane components.
	 *
	 * @param pinhole     the aperture component
	 * @param plane       the film-plane component
	 * @param focalLength the focal length used to compute the plane offset
	 */
	public void init(Pinhole pinhole, AbsorptionPlane plane, double focalLength) {
		this.pinhole = pinhole;
		this.plane = plane;
		
		double[] norm = pinhole.getSurfaceNormal().get().evaluate().toArray();
		this.planePos = VectorMath.multiply(norm, -focalLength, true);
	}

	/**
	 * Creates the internal {@link AbsorptionPlane} if it has not already been set.
	 *
	 * @param norm   the surface normal direction
	 * @param orient the up-orientation vector
	 */
	protected void initPlane(double[] norm, double[] orient) {
		if (this.plane == null) {
			this.plane = new AbsorptionPlane();
			this.plane.setSurfaceNormal(vector(norm[0], norm[1], norm[2]));
			this.plane.setOrientation(VectorMath.clone(orient));
		}
	}
	
	/** Sets the sensor grid width in pixels. */
	public void setWidth(int w) { this.plane.setWidth(w); }

	/** Sets the sensor grid height in pixels. */
	public void setHeight(int h) { this.plane.setHeight(h); }

	/** Returns the sensor grid width in pixels. */
	public int getWidth() { return (int) this.plane.getWidth(); }

	/** Returns the sensor grid height in pixels. */
	public int getHeight() { return (int) this.plane.getHeight(); }

	/** Sets the physical size of each pixel cell (typically in micrometres). */
	public void setPixelSize(double p) { this.plane.setPixelSize(p); }

	/** Returns the physical size of each pixel cell (typically in micrometres). */
	public double getPixelSize() { return this.plane.getPixelSize(); }

	/** Returns the absorption plane (film) component of this camera. */
	public AbsorptionPlane getAbsorptionPlane() { return this.plane; }

	/** Sets the absorption plane (film) component of this camera. */
	public void setAbsorptionPlane(AbsorptionPlane plane) { this.plane = plane; }

	/** Returns the pinhole aperture component of this camera. */
	public Pinhole getPinhole() { return pinhole; }

	/** Sets the pinhole aperture component of this camera. */
	public void setPinhole(Pinhole pinhole) { this.pinhole = pinhole; }

	/** Returns the offset of the film plane relative to the camera origin. */
	public double[] getPlanePosition() { return planePos; }

	/** Sets the offset of the film plane relative to the camera origin. */
	public void setPlanePosition(double[] planePos) { this.planePos = planePos; }

	/** Returns the optional {@link Colorable} that receives pixel colour on each ray query. */
	public Colorable getColorable() { return colorable; }

	/** Sets the optional {@link Colorable} that receives pixel colour on each ray query. */
	public void setColorable(Colorable c) { this.colorable = c; }

	@Override
	public void setLocation(Vector p) { this.location = p; }

	@Override
	public Vector getLocation() { return this.location; }

	@Override
	public void setViewingDirection(Vector v) {
		initPlane(v.toArray(), new double[3]);
		this.plane.setSurfaceNormal(value(v));
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

	/** Returns the f-number of this camera (focal length divided by aperture diameter). */
	public double getFNumber() { return getFocalLength() / (2.0 * this.pinhole.getRadius()); }

	@Override
	public CollectionProducer rayAt(Producer<?> pos, Producer<?> sd) {
		return new DynamicCollectionProducer(new TraversalPolicy(6), args -> {
				Pair ij = new Pair((PackedCollection) pos.get().evaluate(args), 0);
				Pair screenDim = new Pair((PackedCollection) sd.get().evaluate(args), 0);

				double u = ij.getX() / (screenDim.getX());
				double v = (ij.getY() / screenDim.getY());
				// v = 1.0 - v;

				if (colorable != null) {
					int tot = 6;
					RGB c = new RGB(0.0, 0.0, 0.0);

					if (plane.imageAvailable()) {
						RGB[][] im = plane.getImage();
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

				double[] x = plane.getSpatialCoords(new double[] {u, v});
				VectorMath.addTo(x, planePos);
				double[] d = VectorMath.multiply(x, -1.0 / VectorMath.length(x), true);

				Vector vx = new Vector(x[0], x[1], x[2]);
				Vector vd = new Vector(d[0], d[1], d[2]);
				vx.addTo(location);

				return new Ray(vx, vd);
			});
	}

	/** Sets the physics clock used by this absorber. */
	@Override
	public void setClock(Clock c) { this.clock = c; }

	/** Returns the physics clock used by this absorber. */
	@Override
	public Clock getClock() { return this.clock; }

	/** Returns {@code true} if the point is inside either the pinhole or the film plane volume. */
	@Override
	public boolean inside(Producer<PackedCollection> x) { return pinhole.inside(x) || plane.inside(x); }

	/** Returns {@code null}: direct value evaluation is not supported. */
	@Override
	public Producer getValueAt(Producer point) { return null; }

	/** Returns the surface normal of the film plane at the given point. */
	@Override
	public Producer<PackedCollection> getNormalAt(Producer<PackedCollection> x) { return plane.getNormalAt(x); }

	/** Returns the minimum intersection distance with either the pinhole or the film plane. */
	@Override
	public double intersect(Vector x, Vector p) {
		return Math.min(pinhole.intersect(x, p), plane.intersect(x, p));
	}

	/** Delegates to the film plane's surface-coordinate mapping. */
	@Override
	public double[] getSurfaceCoords(Producer<PackedCollection> xyz) { return plane.getSurfaceCoords(xyz); }

	/** Delegates to the film plane's spatial-coordinate mapping. */
	@Override
	public double[] getSpatialCoords(double[] uv) { return plane.getSpatialCoords(uv); }

	/**
	 * Attempts to absorb the photon: first at the pinhole, then (if not at pinhole) at the film plane.
	 *
	 * @param x      the position of the photon
	 * @param p      the direction of the photon
	 * @param energy the energy of the photon
	 * @return {@code true} if the photon was absorbed by either component
	 */
	@Override
	public boolean absorb(Vector x, Vector p, double energy) {
		if (this.pinhole.absorb(x, p, energy))
			return true;
		else return this.plane.absorb(x.subtract(new Vector(planePos)), p, energy);
	}

	/** Returns {@code null}: this camera does not emit photons. */
	@Override
	public Producer<PackedCollection> emit() { return null; }

	/** Returns {@code 0.0}: this camera does not emit energy. */
	@Override
	public double getEmitEnergy() { return 0.0; }

	/** Returns {@code null}: this camera has no emission position. */
	@Override
	public Producer<PackedCollection> getEmitPosition() { return null; }

	/** Returns {@link Integer#MAX_VALUE}: this camera never emits. */
	@Override
	public double getNextEmit() { return Integer.MAX_VALUE; }
}
