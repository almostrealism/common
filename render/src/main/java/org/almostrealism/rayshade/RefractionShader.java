/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.rayshade;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.almostrealism.raytrace.LightingEngineAggregator;
import io.almostrealism.relation.Editable;
import io.almostrealism.uml.Multiple;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.DiscreteField;
import org.almostrealism.geometry.Intersection;
import org.almostrealism.algebra.Vector;
import org.almostrealism.color.*;
import org.almostrealism.color.computations.GeneratedColorProducer;
import org.almostrealism.geometry.Curve;
import org.almostrealism.geometry.Ray;
import org.almostrealism.hardware.DynamicProducerForMemoryData;
import io.almostrealism.relation.Producer;
import org.almostrealism.space.AbstractSurface;
import org.almostrealism.space.Scene;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.CodeFeatures;
import io.almostrealism.relation.Evaluable;

/**
 * {@link RefractionShader} provides physically-based refraction rendering for transparent
 * and translucent materials such as glass, water, and crystals.
 *
 * <p>Refraction occurs when light passes through the boundary between two media with
 * different indices of refraction (IOR), causing the light ray to bend according to
 * Snell's law:</p>
 * <pre>
 * n1 * sin(theta1) = n2 * sin(theta2)
 * </pre>
 * <p>where n1 and n2 are the indices of refraction of the two media, and theta1/theta2
 * are the angles of incidence and refraction.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li><b>Snell's Law refraction:</b> Physically accurate ray bending at material boundaries</li>
 *   <li><b>Total internal reflection:</b> Handled when rays exceed the critical angle</li>
 *   <li><b>Color attenuation:</b> Absorption effects for tinted glass</li>
 *   <li><b>Density sampling:</b> Supports variable IOR within a volume</li>
 *   <li><b>Recursive tracing:</b> Follows refracted rays through multiple surfaces</li>
 * </ul>
 *
 * <h2>Common Index of Refraction Values</h2>
 * <ul>
 *   <li>Air: 1.0</li>
 *   <li>Water: 1.33</li>
 *   <li>Glass: 1.5 - 1.9</li>
 *   <li>Diamond: 2.42</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@link #setIndexOfRefraction(double)} - IOR of the material (default 1.0)</li>
 *   <li>{@link #setAttenuationFactors(double, double, double)} - RGB attenuation for colored glass</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * RefractionShader glass = new RefractionShader();
 * glass.setIndexOfRefraction(1.5);                    // Standard glass
 * glass.setAttenuationFactors(0.95, 0.95, 0.98);     // Slight blue-green tint
 *
 * sphere.addShader(glass);
 * sphere.setRefractedPercentage(0.9);                 // 90% transparent
 * }</pre>
 *
 * <h2>Known Issues</h2>
 * <p><b>Note:</b> The refraction algorithm has some known issues (see TODO at class level).
 * Results may not be perfectly physically accurate in all edge cases.</p>
 *
 * @see org.almostrealism.color.Shader
 * @see ReflectionShader
 * @see org.almostrealism.raytrace.LightingEngineAggregator
 * @author Michael Murray
 */
// TODO  Fix refraction algorithm.
public class RefractionShader implements Shader<ShaderContext>, Editable, RGBFeatures, CodeFeatures {

	/**
	 * The last refracted ray direction (for debugging purposes).
	 */
	public static Vector lastRay;

	/**
	 * Enables debug output during refraction calculations.
	 */
	public static boolean produceOutput = false;
  
	private static final String propNames[] = {"Index of refraction", "Red attenuation", "Green attenuation", "Blue attenuation"};
	private static final String propDesc[] = {"The index of refraction of the medium",
						"The attenuation factor for the red channel",
						"The attenuation factor for the green channel",
						"The attenuation factor for the blue channel"};
	private static final Class propTypes[] = {Double.class, Double.class, Double.class, Double.class};
  
	private double indexOfRefraction;
	private double ra, ga, ba;
	private double sampleDistance = 0.01;
	private int sampleCount = 1;
	private double lra, lga, lba;
  
	private int entered, exited;

	/**
	 * Constructs a new {@link RefractionShader} with default settings.
	 * The index of refraction defaults to 0.0 (should be set before use).
	 */
	public RefractionShader() { }

	/**
	 * Computes the refracted color at a surface intersection point.
	 *
	 * <p>This method traces a refracted ray through the surface based on the index of
	 * refraction and returns the color seen through the transparent material. It handles
	 * both front-face and back-face refractions for proper entry/exit behavior.</p>
	 *
	 * @param p       The shader context containing intersection and lighting information
	 * @param normals The surface normals at the intersection point
	 * @return A Producer that computes the refracted color
	 */
	public Producer<RGB> shade(ShaderContext p, DiscreteField normals) {
		Producer pr = new Producer<RGB>() {
			@Override
			public Evaluable<RGB> get() {
				return new Evaluable<>() {
					@Override
					public Multiple<RGB> createDestination(int size) {
						return RGB.bank(size);
					}

					@Override
					public RGB evaluate(Object... args) {
						p.addReflection();

						Producer<RGB> color = null;

						Vector po = p.getIntersection().get(0).get().evaluate(args).getOrigin();

						if (Math.random() < 0.01 &&
								po.getX() * po.getX() + po.getY() * po.getY() + po.getZ() * po.getZ() - 1.0 > 0.01)
							System.out.println("RefractionShader: " + po);

						Vector n = normals.iterator().next().get().evaluate(args).getDirection();

						n = n.divide(n.length());

						if (p.getSurface() instanceof ShadableSurface == false || ((ShadableSurface) p.getSurface()).getShadeFront()) {
							Vector point;

							try {
								point = p.getIntersection().get(0).get().evaluate(args).getOrigin();
							} catch (Exception e) {
								e.printStackTrace();
								return null;
							}

							Producer<RGB> c = RefractionShader.this.shade(point, p.getIntersection().getNormalAt(v(point)).get().evaluate(args),
									p.getLightDirection(), p.getLight(), p.getOtherLights(), p.getSurface(),
									p.getOtherSurfaces(), n, p);

							c = multiply(rgb(10, 10, 10), c);

							if (Math.random() < 0.01)
								System.out.println("RefractionShader.shadeFront: " + c.get().evaluate(args));

							if (c != null) {
								if (color == null) {
									color = c;
								} else {
									color = add(color, c);
								}
							}
						}

						if (p.getSurface() instanceof ShadableSurface == false || ((ShadableSurface) p.getSurface()).getShadeBack()) {
							Vector point = p.getIntersection().get(0).get().evaluate(args).getOrigin();

							Producer<RGB> c = RefractionShader.this.shade(point, p.getIntersection().getNormalAt(v(point)).get().evaluate(args),
									p.getLightDirection(), p.getLight(), p.getOtherLights(), p.getSurface(),
									p.getOtherSurfaces(), n.minus(), p);

							if (Math.random() < 0.01)
								System.out.println("RefractionShader.shadeBack: " + c.get().evaluate(args));

							if (c != null) {
								if (color == null) {
									color = c;
								} else {
									color = add(color, c);
								}
							}
						}

						return color.get().evaluate(args);
					}
				};
			}
		};
		
		return GeneratedColorProducer.fromProducer(this, pr);
	}
	
	protected Producer<RGB> shade(Vector point, Vector viewerDirection, Supplier<Evaluable<? extends Vector>> lightDirection,
								  Light light, Iterable<Light> otherLights, Curve<RGB> surface,
								  Curve<RGB> otherSurfaces[], Vector n, ShaderContext p) {
		if (p.getReflectionCount() > ReflectionShader.maxReflections) {
			lastRay = null;
			return black();
		}
		
		boolean entering = this.checkEntering(viewerDirection, n);
		double currentR = 0.0, nextR = 0.0;
		
		if (entering)
			p.addEntrance();
		else
			p.addExit();
		
		if (surface instanceof AbstractSurface) {
			currentR =
				this.sampleDensity((AbstractSurface) surface, point, n,
						this.sampleDistance, this.sampleCount, !entering);
			nextR =
				this.sampleDensity((AbstractSurface) surface, point, n,
						this.sampleDistance, this.sampleCount, entering);
		} else if (entering) {
			currentR = 1.0;
			nextR = this.indexOfRefraction;
		} else {
			currentR = this.indexOfRefraction;
			nextR = 1.0;
		}
		
//		if (Math.random() < 0.0001)
//			System.out.println(viewerDirection.length() +
//								" " + n.length() + " " + 
//								currentR + " " + nextR);
		
		// if (!entering) n = n.minus();
		
		Vector dv = viewerDirection;
		dv = dv.minus();
		
		Vector d = RefractionShader.refract(dv, n, currentR, nextR, (Math.random() < 0.0000));
		d.divideBy(d.length());
		
		// if (d.dotProduct(dv) > 0) d.multiplyBy(-1.0);
		
		// d = dv.minus();
		
		// if (entering) d.multiplyBy(-1.0);
		Producer<Ray> r = new DynamicProducerForMemoryData<>(args -> new Ray(point, d));
		
		List<Curve<RGB>> allSurfaces = Scene.combineSurfaces(surface, Arrays.asList(otherSurfaces));
		
		List<Light> allLights = new ArrayList<>();
		allLights.add(p.getLight());
		for (Light l : p.getOtherLights()) { allLights.add(l); }
		
//		if (Math.random() < 0.00001 && !entering) System.out.println(r.getDirection() + " " + lastRay);
		RefractionShader.lastRay = d;

		Producer<RGB> color = () -> new LightingEngineAggregator(r, allSurfaces, allLights, p);
		
//		if (color.equals(new RGB()) && Math.random() < 0.01) System.out.println(d.dotProduct(dv));
		
//		if (color == null) {
////			color = surface.getColorAt(point).multiply(new RGB(this.lra, this.lga, this.lba));
//		} else {
//			// if (!color.equals(new RGB()) && Math.random() < 0.001) System.out.println(color);
//			if (entering) color.multiplyBy(new RGB(this.ra, this.ga, this.ba));
//			
//		}
		
		return color;
	}
	
	/**
	 * Samples the index of refraction at a point near the surface.
	 *
	 * <p>This method supports materials with spatially varying IOR by sampling
	 * multiple points and averaging. For simple materials with uniform IOR,
	 * use a single sample.</p>
	 *
	 * @param s        The surface to sample
	 * @param p        The surface intersection point
	 * @param n        The surface normal at the intersection
	 * @param sd       The sampling distance (how far to step from surface)
	 * @param samples  Number of samples to take (1 for uniform materials)
	 * @param entering True if ray is entering the material, false if exiting
	 * @return The averaged index of refraction at the sampled location
	 */
	public double sampleDensity(AbstractSurface s, Vector p, Vector n,
								double sd, int samples, boolean entering) {
		double totalR = 0.0;
		
		if (entering) {
			Vector v = n.multiply(-sd);
			v.addTo(p);
			totalR = s.getIndexOfRefraction(v);
		} else {
			Vector v = n.multiply(sd);
			v.addTo(p);
			totalR = s.getIndexOfRefraction(v);
		}
		
		for (int i = 1; i < samples; i++) {
			Vector d = Vector.uniformSphericalRandom();
			double dot = d.dotProduct(n);
			
			if ((entering && dot > 0.0) ||
					(!entering && dot < 0.0)) {
				d.multiplyBy(-1.0);
			}
			
			Producer<Ray> r = new DynamicProducerForMemoryData<>(args -> new Ray(p, d));

			Intersection inter = (Intersection) s.intersectAt(r);
			PackedCollection<?> id = inter.getDistance().get().evaluate();
			
			if (inter == null || id.toDouble() < 0) {
				totalR += 1.0;
			} else {
				totalR += s.getIndexOfRefraction(r.get().evaluate().pointAt(v(id)).get().evaluate());
			}
		}
		
		if (samples > 1)
			return totalR / samples;
		else
			return totalR;
	}
	
	/**
	 * Determines whether a ray is entering or exiting a surface based on the
	 * ray direction and surface normal.
	 *
	 * @param d The ray direction vector
	 * @param n The surface normal vector (pointing outward)
	 * @return True if the ray is entering the surface, false if exiting
	 */
	public boolean checkEntering(Vector d, Vector n) {
		double dot = d.dotProduct(n);
		
		if (dot > 0.0) {
			return true;
		} else {
			return false;
		}
	}
	
	/**
	 * Computes the refracted ray direction using Snell's law.
	 *
	 * <p>When total internal reflection occurs (ray angle exceeds critical angle),
	 * this method returns the reflected direction instead.</p>
	 *
	 * @param n      The surface normal
	 * @param d      The incident ray direction
	 * @param rindex The index of refraction of the current medium
	 * @param eindex The index of refraction of the medium being entered
	 * @return The refracted (or reflected) ray direction
	 */
	public Vector refract(Vector n, Vector d, double rindex, double eindex) {
		Vector refracted;
		
		double ndoti, tndoti, ndoti2, a, b, b2, d2;
		
		ndoti = n.dotProduct(d);
		ndoti2 = ndoti * ndoti;
		
		// if (ndoti >= 0.0) {
		b = eindex / rindex; // } else { b = eindex / rindex; }
		b2 = b * b;
		
		d2 = 1.0 - b2 * (1.0 - ndoti2);
		
		if (d2 >= 0.0) {
			if (ndoti >= 0.0)
				a = b * ndoti - Math.sqrt(d2);
			else
				a = b * ndoti + Math.sqrt(d2);
			
			refracted = n.multiply(a).subtract(d.multiply(b));
		} else {
			tndoti = ndoti + ndoti;
			
			refracted = n.multiply(tndoti).subtract(d);
		}
		
		return refracted;
	}


	/**
	 * Refracts the specified Vector object based on the specified normal vector and 2 specified indices of refraction.
	 *
	 * @param vector  A Vector object representing a unit vector in the direction of the incident ray
	 * @param normal  A Vector object representing a unit vector that is normal to the surface refracting the ray
	 * @param ni  A double value representing the index of refraction of the incident medium
	 * @param nr  A double value representing the index of refraction of the refracting medium
	 *
	 * @deprecated
	 */
	@Deprecated
	public static Vector refract(Vector vector, Vector normal, double ni, double nr, boolean v) {
		if (v) System.out.println("Vector = " + vector);

		vector = vector.minus();

		double p = -vector.dotProduct(normal);
		double r = ni / nr;

		if (v) System.out.println("p = " + p + " r = " + r);

		vector = vector.minus();
		if (vector.dotProduct(normal) < 0) {
			if (v) System.out.println("LALA");
			normal = normal.minus();
			p = -p;
		}
		vector = vector.minus();

		double s = Math.sqrt(1.0 - r * r * (1.0 - p * p));

		if (v) System.out.println("s = " + s);

		Vector refracted = vector.multiply(r);

		if (v) System.out.println(refracted);

		//	if (p >= 0.0) {
		refracted.addTo(normal.multiply((p * r) - s));
		//	} else {
		//		refracted.addTo(normal.multiply((p * r) - s));
		//	}

		if (v) System.out.println(refracted);

		// Vector refracted = ((vector.subtract(normal.multiply(p))).multiply(r)).subtract(normal.multiply(s));

//		if (refracted.subtract(vector).length() > 0.001) System.out.println("!!"); TODO

		return refracted.minus();
	}
	
	/**
	 * Sets the index of refraction value used by this RefractionShader object.
	 */
	public void setIndexOfRefraction(double n) { this.indexOfRefraction = n; }
	
	/**
	 * Sets the attenuation factors used by this RefractionShader object.
	 */
	public void setAttenuationFactors(double r, double g, double b) {
		this.ra = r;
		this.ga = g;
		this.ba = b;
		
		this.lra = Math.log(this.ra);
		this.lga = Math.log(this.ga);
		this.lba = Math.log(this.ba);
	}
	
	/**
	 * Returns the index of refraction value used by this RefractionShader object.
	 */
	public double getIndexOfRefraction() { return this.indexOfRefraction; }
	
	/**
	 * Returns the 3 attenuation factors (RGB) used by this RefractionShader object.
	 */
	public double[] getAttenuationFactors() {
		return new double[] {this.ra, this.ga, this.ba};
	}
	
	/**
	 * Returns an array of String objects with names for each editable property of this RefractionShader object.
	 */
	public String[] getPropertyNames() { return RefractionShader.propNames; }
	
	/**
	 * Returns an array of String objects with descriptions for each editable property of this RefractionShader object.
	 */
	public String[] getPropertyDescriptions() { return RefractionShader.propDesc; }
	
	/**
	 * Returns an array of Class objects representing the class types of each editable property of this RefractionShader object.
	 */
	public Class[] getPropertyTypes() { return RefractionShader.propTypes; }
	
	/**
	 * Returns the values of the properties of this ReflectionShader object as an Object array.
	 */
	public Object[] getPropertyValues() {
		return new Object[] {Double.valueOf(this.indexOfRefraction), Double.valueOf(this.ra), Double.valueOf(this.ga), Double.valueOf(this.ba)};
	}
	
	/**
	 * Sets the value of the property of this RefractionShader object at the specified index to the specified value.
	 * 
	 * @throws IllegalArgumentException  If the object specified is not of the correct type.
	 * @throws IndexOutOfBoundsException  If the index specified does not correspond to an editable property of this
	 *                                    RefractionShader object.
	 */
	public void setPropertyValue(Object value, int index) {
		if (value instanceof Double == false)
			throw new IllegalArgumentException("Illegal argument: " + value.toString());
		
		if (index == 0) {
				this.setIndexOfRefraction(((Double)value).doubleValue());
		} else if (index == 1) {
				this.setAttenuationFactors(((Double)value).doubleValue(),
								this.getAttenuationFactors()[1],
								this.getAttenuationFactors()[2]);
		} else if (index == 2) {
				this.setAttenuationFactors(this.getAttenuationFactors()[0],
								((Double)value).doubleValue(),
								this.getAttenuationFactors()[2]);
		} else if (index == 3) {
				this.setAttenuationFactors(this.getAttenuationFactors()[0],
								this.getAttenuationFactors()[1],
								((Double)value).doubleValue());
		} else {
			throw new IndexOutOfBoundsException("Index out of bounds: " + index);
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
	 * Sets the values of editable properties of this ReflectionShader object to those specified.
	 * 
	 * @throws IllegalArgumentException  If one of the objects specified is not of the correct type.
	 *                                   (Note: none of the values after the erroneous value will be set)
	 * @throws IndexOutOfBoundsException  If the length of the specified array is longer than permitted.
	 */
	public void setPropertyValues(Object values[]) {
		for (int i = 0; i < values.length; i++)
			this.setPropertyValue(values[i], i);
	}
	
	/**
	 * Returns "Refraction Shader".
	 */
	public String toString() { return "Refraction Shader"; }
}
