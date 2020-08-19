/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.graph.mesh;

import org.almostrealism.algebra.*;
import org.almostrealism.algebra.computations.RayMatrixTransform;
import org.almostrealism.color.computations.GeneratedColorProducer;
import org.almostrealism.color.RGB;
import org.almostrealism.color.computations.RGBProducer;
import org.almostrealism.geometry.Positioned;
import org.almostrealism.geometry.Ray;
import org.almostrealism.math.AcceleratedProducer;
import org.almostrealism.relation.Constant;
import org.almostrealism.relation.Operator;
import org.almostrealism.space.AbstractSurface;
import org.almostrealism.space.BoundingSolid;
import org.almostrealism.space.ShadableIntersection;
import org.almostrealism.util.Producer;
import org.almostrealism.util.StaticProducer;
import org.almostrealism.util.VectorPassThroughProducer;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A Triangle object represents a triangle in 3d space.
 * 
 * @author  Michael Murray
 */
public class Triangle extends AbstractSurface implements ParticleGroup {
	public static boolean enableHardwareOperator = true;

	private Mesh.VertexData vertexData;
	private int ind1, ind2, ind3;
	
	private Vector p1, p2, p3;
	private Vector normal;
	private boolean smooth, intcolor, useT = true;
	private Vector abc = new Vector(), def = new Vector(), jkl = new Vector();

	private static VectorProducer normalProducer;
	private static VectorProducer abcProducer;
	private static VectorProducer defProducer;
	private static VectorProducer jklProducer;
	
	static {
		VectorPassThroughProducer p1 = new VectorPassThroughProducer(0);
		VectorPassThroughProducer p2 = new VectorPassThroughProducer(1);
		VectorPassThroughProducer p3 = new VectorPassThroughProducer(2);

		VectorProducer a = p2.subtract(p1);
		VectorProducer b = p3.subtract(p1);

		normalProducer = a.crossProduct(b);
		normalProducer = normalProducer.normalize();

		abcProducer = p1.subtract(p2);
		defProducer = p1.subtract(p3);
		jklProducer = p1;

		normalProducer.compact();
		abcProducer.compact();
		defProducer.compact();
		jklProducer.compact();
	}

	/**
	 * Constructs a new {@link Triangle} with all vertices at the origin that is black.
	 */	
	public Triangle() {
		super(null, 1.0, new RGB(0.0, 0.0, 0.0), false);
		this.setVertices(new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, 0.0));
	}
	
	/**
	 * Constructs a new Triangle object with the specified vertices that is black.
	 */
	public Triangle(Vector p1, Vector p2, Vector p3) {
		this.setVertices(p1, p2, p3);
	}
	
	/**
	 * Constructs a new {@link Triangle} object with the specified vertices
	 * with the color represented by the specified {@link RGB} object.
	 */
	public Triangle(Vector p1, Vector p2, Vector p3, RGB color) {
		super(null, 1.0, color, false);
		this.setVertices(p1, p2, p3);
	}
	
	public Triangle(int p1, int p2, int p3, RGB color, Mesh.VertexData data) {
		super(null, 1.0, color, false);
		
		this.ind1 = p1;
		this.ind2 = p2;
		this.ind3 = p3;
		this.vertexData = data;
		
		this.loadVertexData();
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
		
		this.abc.setX(p1x - p2x);
		this.abc.setY(p1y - p2y);
		this.abc.setZ(p1z - p2z);
		this.def.setX(p1x - p3x);
		this.def.setY(p1y - p3y);
		this.def.setZ(p1z - p3z);
		this.jkl.setX(p1x);
		this.jkl.setY(p1y);
		this.jkl.setZ(p1z);
	}
	
	/**
	 * Sets the vertices of this Triangle object to those specified.
	 * The Vector objects passed to this method WILL be stored by the Triangle object,
	 * but changes made to the Vector objects WILL NOT be reflected in the calculation
	 * of smooth surface normals and of intersections. To change the Vector coordinates
	 * you must call the setVertices method again.
	 */	
	public void setVertices(Vector p1, Vector p2, Vector p3) {
		if (enableHardwareOperator) {
			this.normal = normalProducer.evaluate(new Object[] { p1, p2, p3 });
			this.abc = abcProducer.evaluate(new Object[] { p1, p2, p3 });
			this.def = defProducer.evaluate(new Object[] { p1, p2, p3 });
			this.jkl = jklProducer.evaluate(new Object[] { p1, p2, p3 });
		} else {
			this.p1 = p1;
			this.p2 = p2;
			this.p3 = p3;

			Vector a = this.p2.subtract(this.p1);
			Vector b = this.p3.subtract(this.p1);

			this.normal = a.crossProduct(b);
			this.normal.normalize();

			this.abc = this.p1.subtract(this.p2);
			this.def = this.p1.subtract(this.p3);
			this.jkl.setTo(this.p1);
		}
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

	@Override
	public Producer<RGB> getValueAt(Producer<Vector> point) {
		RGBProducer dcp = getColorAt(point, useT);

		return GeneratedColorProducer.fromProducer(this, new Producer<RGB>() {
			@Override
			public RGB evaluate(Object[] args) {
				RGB dc = dcp.evaluate(args);

				Vector triple = point.evaluate(args);
				if (dc.length() < (Intersection.e * 100)) return new RGB(0.0, 0.0, 0.0);

				if (intcolor) {
					double g = triple.getA();
					double h = triple.getB();
					double i = triple.getC();

					double m = abc.getX() * (def.getY() * i - h * def.getZ()) +
							abc.getY() * (g * def.getZ() - def.getX() * i) +
							abc.getZ() * (def.getX() * h - def.getY() * g);

					double u = jkl.getX() * (def.getY() * i - h * def.getZ()) +
							jkl.getY() * (g * def.getZ() - def.getX() * i) +
							jkl.getZ() * (def.getX() * h - def.getY() * g);
					u = u / m;

					double v = i * (abc.getX() * jkl.getY() - jkl.getX() * abc.getY()) +
							h * (jkl.getX() * abc.getZ() - abc.getX() * jkl.getZ()) +
							g * (abc.getY() * jkl.getZ() - jkl.getY() * abc.getZ());
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
			public void compact() {
				dcp.compact();
				point.compact();
			}
		});
	}
	
	/**
	 * Returns a {@link Vector} {@link Producer} that represents the vector normal to this sphere
	 * at the point represented by the specified {@link Vector} {@link Producer}.
	 */
	@Override
	public Producer<Vector> getNormalAt(Producer<Vector> p) {
		if (smooth && vertexData == null) {
			return new VectorProducer() {
				@Override
				public Vector evaluate(Object[] args) {
					Vector point = p.evaluate(args);

					double g = point.getX();
					double h = point.getY();
					double i = point.getZ();

					double m = abc.getX() * (def.getY() * i - h * def.getZ()) +
							abc.getY() * (g * def.getZ() - def.getX() * i) +
							abc.getZ() * (def.getX() * h - def.getY() * g);

					double u = jkl.getX() * (def.getY() * i - h * def.getZ()) +
							jkl.getY() * (g * def.getZ() - def.getX() * i) +
							jkl.getZ() * (def.getX() * h - def.getY() * g);
					u = u / m;

					double v = i * (abc.getX() * jkl.getY() - jkl.getX() * abc.getY()) +
							h * (jkl.getX() * abc.getZ() - abc.getX() * jkl.getZ()) +
							g * (abc.getY() * jkl.getZ() - jkl.getY() * abc.getZ());
					v = v / m;

					double w = 1.0 - u - v;

					Vector n = new Vector(0.0, 0.0, 0.0);
					n.addTo(((Mesh.Vertex) p1).getNormal(w));
					n.addTo(((Mesh.Vertex) p2).getNormal(u));
					n.addTo(((Mesh.Vertex) p3).getNormal(v));

					if (useT)
						n = getTransform(true).getInverse().transformAsNormal(n);


					n.divideBy(n.length());

					return n;
				}

				@Override
				public void compact() {
					p.compact();
				}
			};
		} else {
			if (useT && getTransform(true) != null) {
				return getTransform(true).getInverse().transform(
						StaticProducer.of(normal),
						TransformMatrix.TRANSFORM_AS_NORMAL);
			} else {
				return StaticProducer.of((Vector) normal.clone());
			}
		}
	}
	
	/**
	 * Returns a {@link ShadableIntersection} representing the points along the specified
	 * {@link Ray} that intersection between the ray and the triangle represented by this
	 * {@link Triangle} occurs.
	 */
	@Override
	public ContinuousField intersectAt(Producer ray) {
		TransformMatrix t = getTransform(true);
		boolean ut = useT && t != null;
		if (ut) ray = new RayMatrixTransform(t.getInverse(), ray);

		if (enableHardwareOperator) {
			return new ShadableIntersection(this, ray,
									new AcceleratedProducer<>(
											"triangleIntersectAt",
											false,
											new Producer[] {
												new StaticProducer(new Scalar()), ray
											},
											new Object[] { abc, def, jkl }));
		} else {
			final Producer<Ray> fray = ray;

			Producer<Scalar> s = new Producer<Scalar>() {
				@Override
				public Scalar evaluate(Object[] args) {
					Ray r = fray.evaluate(args);

					Vector jkl = Triangle.this.jkl.subtract(r.getOrigin());

					double m = abc.getX() * (def.getY() * r.getDirection().getZ() - r.getDirection().getY() * def.getZ()) +
							abc.getY() * (r.getDirection().getX() * def.getZ() - def.getX() * r.getDirection().getZ()) +
							abc.getZ() * (def.getX() * r.getDirection().getY() - def.getY() * r.getDirection().getX());

					if (m == 0)
						return null;

					double u = jkl.getX() * (def.getY() * r.getDirection().getZ() - r.getDirection().getY() * def.getZ()) +
							jkl.getY() * (r.getDirection().getX() * def.getZ() - def.getX() * r.getDirection().getZ()) +
							jkl.getZ() * (def.getX() * r.getDirection().getY() - def.getY() * r.getDirection().getX());
					u = u / m;

					if (u <= 0.0)
						return null;

					double v = r.getDirection().getZ() * (abc.getX() * jkl.getY() - jkl.getX() * abc.getY()) +
							r.getDirection().getY() * (jkl.getX() * abc.getZ() - abc.getX() * jkl.getZ()) +
							r.getDirection().getX() * (abc.getY() * jkl.getZ() - jkl.getY() * abc.getZ());
					v = v / m;

					if (v <= 0.0 || u + v >= 1.0)
						return null;

					double t = def.getZ() * (abc.getX() * jkl.getY() - jkl.getX() * abc.getY()) +
							def.getY() * (jkl.getX() * abc.getZ() - abc.getX() * jkl.getZ()) +
							def.getX() * (abc.getY() * jkl.getZ() - jkl.getY() * abc.getZ());
					t = -1.0 * t / m;

					return new Scalar(t);
				}

				@Override
				public void compact() {
					// TODO
				}
			};

			return new ShadableIntersection(this, ray, s);
		}
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
