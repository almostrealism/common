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

package org.almostrealism.graph;

import io.almostrealism.code.Scope;
import io.almostrealism.code.Variable;
import org.almostrealism.algebra.*;
import org.almostrealism.color.GeneratedColorProducer;
import org.almostrealism.color.RGB;
import org.almostrealism.geometry.Positioned;
import org.almostrealism.geometry.Ray;
import org.almostrealism.relation.Constant;
import org.almostrealism.relation.Operator;
import org.almostrealism.relation.TripleFunction;
import org.almostrealism.space.AbstractSurface;
import org.almostrealism.space.BoundingSolid;
import org.almostrealism.space.ShadableIntersection;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A Triangle object represents a triangle in 3d space.
 * 
 * @author  Michael Murray
 */
public class Triangle extends AbstractSurface implements ParticleGroup {
	private Mesh.VertexData vertexData;
	private int ind1, ind2, ind3;
	
	private Vector p1, p2, p3;
	private Vector normal;
	private boolean smooth, intcolor, useT = true;
	private double a, b, c, d, e, f, j, k, l;

	/**
	 * Constructs a new Triangle object with all vertices at the origin that is black.
	 */	
	public Triangle() {
		super(null, 1.0, new RGB(0.0, 0.0, 0.0), false);
		initColorProducer();
		this.setVertices(new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, 0.0));
	}
	
	/**
	 * Constructs a new Triangle object with the specified vertices that is black.
	 */
	public Triangle(Vector p1, Vector p2, Vector p3) {
		super(null, 1.0, new RGB(0.0, 0.0, 0.0), false);
		initColorProducer();
		this.setVertices(p1, p2, p3);
	}
	
	/**
	 * Constructs a new {@link Triangle} object with the specified vertices
	 * with the color represented by the specified {@link RGB} object.
	 */
	public Triangle(Vector p1, Vector p2, Vector p3, RGB color) {
		super(null, 1.0, color, false);
		initColorProducer();
		this.setVertices(p1, p2, p3);
	}
	
	public Triangle(int p1, int p2, int p3, RGB color, Mesh.VertexData data) {
		super(null, 1.0, color, false);
		initColorProducer();
		
		this.ind1 = p1;
		this.ind2 = p2;
		this.ind3 = p3;
		this.vertexData = data;
		
		this.loadVertexData();
	}

	private void initColorProducer() {
		colorProducer = GeneratedColorProducer.fromFunction(this, new TripleFunction<RGB>() {
					@Override
					public RGB operate(Triple triple) {
						RGB dc = getColorAt((Vector) triple, useT);
						if (dc.length() < (Intersection.e * 100)) return new RGB(0.0, 0.0, 0.0);

						if (intcolor) {
							double g = triple.getA();
							double h = triple.getB();
							double i = triple.getC();

							double m = a * (e * i - h * f) + b * (g * f - d * i) + c * (d * h - e * g);

							double u = j * (e * i - h * f) + k * (g * f - d * i) + l * (d * h - e * g);
							u = u / m;

							double v = i * (a * k - j * b) + h * (j * c - a * l) + g * (b * l - k * c);
							v = v / m;

							double w = 1.0 - u - v;

							RGB color = null;

							if (vertexData == null) {
								color = new RGB(0.0, 0.0, 0.0);
								color.addTo(((Mesh.Vertex) p1).getColor(w));
								color.addTo(((Mesh.Vertex) p2).getColor(u));
								color.addTo(((Mesh.Vertex) p3).getColor(v));
							} else {
								double cr = vertexData.getRed(ind1) +
										vertexData.getRed(ind2) +
										vertexData.getRed(ind3);
								double cg = vertexData.getGreen(ind1) +
										vertexData.getGreen(ind2) +
										vertexData.getGreen(ind3);
								double cb = vertexData.getBlue(ind1) +
										vertexData.getBlue(ind2) +
										vertexData.getBlue(ind3);

								color = new RGB(cr, cg, cb);
							}

							color.multiplyBy(dc);

							return color;
						} else {
							return dc;
						}
					}

					@Override
					public Scope<? extends Variable> getScope(String prefix) {
						throw new RuntimeException("getScope is not implemented");
					}
				});
	}
	
	private void loadVertexData() {
		double p1x = this.vertexData.getX(this.ind1);
		double p1y = this.vertexData.getY(this.ind1);
		double p1z = this.vertexData.getZ(this.ind1);
		double p2x = this.vertexData.getX(this.ind2);
		double p2y = this.vertexData.getY(this.ind2);
		double p2z = this.vertexData.getZ(this.ind2);
		double p3x = this.vertexData.getX(this.ind3);
		double p3y = this.vertexData.getY(this.ind3);
		double p3z = this.vertexData.getZ(this.ind3);
		
		Vector a = new Vector(p2x - p1x, p2y - p1y, p2z - p1z);
		Vector b = new Vector(p3x - p1x, p3y - p1y, p3z - p1z);
		
		this.normal = a.crossProduct(b);
		this.normal.divideBy(this.normal.length());
		
		this.a = p1x - p2x;
		this.b = p1y - p2y;
		this.c = p1z - p2z;
		this.d = p1x - p3x;
		this.e = p1y - p3y;
		this.f = p1z - p3z;
		this.j = p1x;
		this.k = p1y;
		this.l = p1z;
	}
	
	/**
	 * Sets the vertices of this Triangle object to those specified.
	 * The Vector objects passed to this method WILL be stored by the Triangle object,
	 * but changes made to the Vector objects WILL NOT be reflected in the calculation
	 * of smooth surface normals and of intersections. To change the Vector coordinates
	 * you must call the setVertices method again.
	 */	
	public void setVertices(Vector p1, Vector p2, Vector p3) {
		this.p1 = p1;
		this.p2 = p2;
		this.p3 = p3;
		
		Vector a = this.p2.subtract(this.p1);
		Vector b = this.p3.subtract(this.p1);
		
		this.normal = a.crossProduct(b);
		this.normal.divideBy(this.normal.length());
		
		this.a = this.p1.getX() - this.p2.getX();
		this.b = this.p1.getY() - this.p2.getY();
		this.c = this.p1.getZ() - this.p2.getZ();
		this.d = this.p1.getX() - this.p3.getX();
		this.e = this.p1.getY() - this.p3.getY();
		this.f = this.p1.getZ() - this.p3.getZ();
		this.j = this.p1.getX();
		this.k = this.p1.getY();
		this.l = this.p1.getZ();
	}

	public void setVertices(Vector v[]) {
		setVertices(v[0], v[1], v[2]);
	}
	
	/**
	 * @return  An array of Vector objects representing the vertices of this Triangle object.
	 */	
	public Vector[] getVertices() {
		if (this.vertexData == null) {
			return new Vector[] {this.p1, this.p2, this.p3};
		} else {
			return new Vector[] {new Vector(this.vertexData.getX(ind1),
									this.vertexData.getY(ind1),
									this.vertexData.getZ(ind1)),
								new Vector(this.vertexData.getX(ind2),
									this.vertexData.getY(ind2),
									this.vertexData.getZ(ind2)),
								new Vector(this.vertexData.getX(ind3),
									this.vertexData.getY(ind3),
									this.vertexData.getZ(ind3))};
		}
	}
	
	public float[][] getTextureCoordinates() {
		if (vertexData == null) {
			return new float[][] {
					{0f, 0f},
					{0f, 0f},
					{0f, 0f}
			};
		}
		
		return new float[][] {
				{(float) vertexData.getTextureU(ind1), (float) vertexData.getTextureV(ind1)},
				{(float) vertexData.getTextureU(ind2), (float) vertexData.getTextureV(ind2)},
				{(float) vertexData.getTextureU(ind3), (float) vertexData.getTextureV(ind3)}
		};
	}
	
	/**
	 * @param use  If set to true, the intersection methods will apply the transformations stored by this
	 *             Triangle object. Otherwise, transformation will not be used. Setting to false is useful
	 *             if the Triangle vertices are absolute coordinates and/or if the Triangle is part of a Mesh
	 *             and the Mesh will apply all needed transformation.
	 */
	public void setUseTransform(boolean use) { this.useT = use; }
	
	/**
	 * @return  True if the intersection methods will apply the transformations stored by this
	 *          Triangle object, false otherwise.
	 */
	public boolean getUseTransform() { return this.useT; }
	
	/**
	 * Controls if vertex colors will be used and color will be interpolated across the triangle.
	 * 
	 * @param vcolor  If true, color will be interpolated across the triangle based on vertex colors
	 *                and then mixed with the color of the triangle. If false, the color of the triangle
	 *                will be used all across the surface.
	 */
	public void setInterpolateVertexColor(boolean vcolor) { this.intcolor = vcolor; }
	
	/**
	 * @return  True if color will be interpolated across the triangle based on vertex colors
	 *          and then mixed with the color of the triangle. False if the color of the triangle
	 *          will be used all across the surface.
	 */
	public boolean getInterpolateVertexColor() { return this.intcolor; }
	
	/**
	 * Sets the smooth flag which indicates if normal vectors should be interpolated.
	 * 
	 * @param s  Value to use.
	 */
	public void setSmooth(boolean s) { this.smooth = s; }
	
	/**
	 * @return  The smooth flag which indicates if normal vectors should be interpolated.
	 */
	public boolean getSmooth() { return this.smooth; }
	
	/**
	 * @see  ParticleGroup#getParticleVertices()
	 */
	@Override
	public double[][] getParticleVertices() {
		if (this.vertexData == null) {
		    return new double[][] {{this.p1.getX(), this.p1.getY(), this.p1.getZ()},
		            				{this.p2.getX(), this.p2.getY(), this.p2.getZ()},
		            				{this.p3.getX(), this.p3.getY(), this.p3.getZ()}};
		} else {
			return new double[][] {{this.vertexData.getX(ind1),
									this.vertexData.getY(ind1),
									this.vertexData.getZ(ind1)},
								{this.vertexData.getX(ind2),
									this.vertexData.getY(ind2),
									this.vertexData.getZ(ind2)},
								{this.vertexData.getX(ind3),
									this.vertexData.getY(ind3),
									this.vertexData.getZ(ind3)}};
		}
	}
	
	/**
	 * Returns a Vector object that represents the vector normal to this sphere at the point
	 * represented by the specified Vector object.
	 */
	@Override
	public VectorProducer getNormalAt(Vector point) {
		// TODO  Perform computation inside VectorProducer

		if (this.smooth && this.vertexData == null) {
			double g = point.getX();
			double h = point.getY();
			double i = point.getZ();
			
			double m = a * (e * i - h * f) + b * (g * f - d * i) + c * (d * h - e * g);

			double u = j * (e * i - h * f) + k * (g * f - d * i) + l * (d * h - e * g);
			u = u / m;
			
			double v = i * (a * k - j * b) + h * (j * c - a * l) + g * (b * l - k * c);
			v = v / m;
			
			double w = 1.0 - u - v;
			
			Vector n = new Vector(0.0, 0.0, 0.0);
			n.addTo(((Mesh.Vertex)this.p1).getNormal(w));
			n.addTo(((Mesh.Vertex)this.p2).getNormal(u));
			n.addTo(((Mesh.Vertex)this.p3).getNormal(v));
			
			if (this.useT)
				n = super.getTransform(true).getInverse().transformAsNormal(n);
			
			
			n.divideBy(n.length());
			
			return new ImmutableVector(n);
		} else {
			if (this.useT) {
				return new ImmutableVector(super.getTransform(true).getInverse().transformAsNormal(this.normal));
			} else {
				return new ImmutableVector((Vector) this.normal.clone());
			}
		}
	}

	/**
	 * @return  True if the ray represented by the specified Ray object intersects the triangle
	 *          represented by this Triangle object.
	 */
	@Override
	public boolean intersect(Ray ray) {
		double r[];
		
		if (useT)
			r = ray.transform(this.getTransform(true).getInverse()).toArray();
		else
			r = ray.toArray();
		
		double j = this.j - r[0];
		double k = this.k - r[1];
		double l = this.l - r[2];
		
		double m = a * (e * r[5] - r[4] * f) + b * (r[3] * f - d * r[5]) + c * (d * r[4] - e * r[3]);
		
		if (m == 0)
			return false;

		double u = j * (e * r[5] - r[4] * f) + k * (r[3] * f - d * r[5]) + l * (d * r[4] - e * r[3]);
		u = u / m;
		
		if (u <= 0.0)
			return false;
		
		double v = r[5] * (a * k - j * b) + r[4] * (j * c - a * l) + r[3] * (b * l - k * c);
		v = v / m;
		
		if (v <= 0.0 || u + v >= 1.0)
			return false;
		
		return true;
	}
	
	/**
	 * Returns an Intersection object representing the points along the ray represented by the specified Ray object that intersection
	 * between the ray and the triangle represented by this Triangle object occurs.
	 */
	@Override
	public ShadableIntersection intersectAt(Ray ray) {
		double r[];
		
		if (useT)
			r = ray.transform(this.getTransform(true).getInverse()).toArray();
		else
			r = ray.toArray();
		
		double j = this.j - r[0];
		double k = this.k - r[1];
		double l = this.l - r[2];
		
		double m = a * (e * r[5] - r[4] * f) + b * (r[3] * f - d * r[5]) + c * (d * r[4] - e * r[3]);
		
		if (m == 0)
			return null;
		
		double u = j * (e * r[5] - r[4] * f) + k * (r[3] * f - d * r[5]) + l * (d * r[4] - e * r[3]);
		u = u / m;
		
		if (u <= 0.0)
			return null;
		
		double v = r[5] * (a * k - j * b) + r[4] * (j * c - a * l) + r[3] * (b * l - k * c);
		v = v / m;
		
		if (v <= 0.0 || u + v >= 1.0)
			return null;
		
		double t = f * (a * k - j * b) + e * (j * c - a * l) + d * (b * l - k * c);
		t = -1.0 * t / m;
		
		return new ShadableIntersection(ray, this, new double[] {t});
	}

	@Override
	public Operator<Scalar> get() {
		return null;
	}

	@Override
	public Operator<Scalar> get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
		return get();
	}

	@Override
	public Operator<Scalar> expect() {
		return new Constant<>(new Scalar(0));
	}

	public String toString() {
		return "Triangle: " + this.p1 + " " + this.p2 + " " + this.p3;
	}

	@Override
	public BoundingSolid calculateBoundingSolid() {
		return BoundingSolid.getBounds(new Positioned[]{p1,p2,p3});
	}
}
