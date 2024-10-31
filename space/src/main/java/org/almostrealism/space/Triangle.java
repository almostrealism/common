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

package org.almostrealism.space;

import io.almostrealism.code.AdaptEvaluable;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.algebra.*;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.color.RGB;
import org.almostrealism.geometry.Intersection;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.geometry.Ray;
import org.almostrealism.hardware.Input;
import io.almostrealism.code.Operator;
import io.almostrealism.code.Constant;
import io.almostrealism.relation.Producer;
import org.almostrealism.graph.mesh.TriangleFeatures;
import org.almostrealism.graph.mesh.TriangleIntersectAt;
import org.almostrealism.geometry.ShadableIntersection;
import org.almostrealism.geometry.ContinuousField;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;

import java.util.function.Supplier;

/**
 * A {@link Triangle} represents a triangle in 3d space.
 * 
 * @author  Michael Murray
 */
public class Triangle extends AbstractSurface implements ParticleGroup, TriangleFeatures {
	public static boolean enableHardwareOperator = true;

	private static TriangleFeatures triangleFeat = TriangleFeatures.getInstance();

	private Mesh.VertexData vertexData;
	private int ind1, ind2, ind3;
	
	private Vector p1, p2, p3;
	private boolean smooth, intcolor, useT = true;
	private PackedCollection<Vector> data;

	protected static final Evaluable<PackedCollection<Vector>> dataProducer;

	public static final Evaluable<Scalar> intersectAt;
	
	static {
//		ExpressionComputation<PackedCollection<Vector>> triangle = triangleFeat.triangle(Input.value(Vector.shape(), 0));
		ExpressionComputation<PackedCollection<Vector>> triangle =
				triangleFeat.triangle(Input.value(new TraversalPolicy(3, 3), 0));
		dataProducer = triangle.get();

//		intersectAt = TriangleIntersectAt.construct(Input.value(Vector.shape(), 1),
//				Input.value(Ray.shape(), 0, -1)).get();
		intersectAt = TriangleIntersectAt.construct(Input.value(new TraversalPolicy(4, 3), 1),
				Input.value(Ray.shape(), 0)).get();
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
	 * Constructs a new {@link Triangle} with the specified vertices
	 * using the color represented by the specified {@link RGB}.
	 */
	public Triangle(Vector p1, Vector p2, Vector p3, RGB color) {
		super(null, 1.0, color, false);
		this.setVertices(p1, p2, p3);
	}
	
	public Triangle(int p1, int p2, int p3, RGB color, Mesh.VertexData data) {
		super(color, false);
		
		this.ind1 = p1;
		this.ind2 = p2;
		this.ind3 = p3;
		this.vertexData = data;
		
		this.loadVertexData();
	}
	
	private void loadVertexData() {
		setVertices(vertexData.getPosition(ind1), vertexData.getPosition(ind2), vertexData.getPosition(ind3));
	}

	public PackedCollection<Vector> getPointData() {
		PackedCollection<Vector> points = Vector.bank(3);
		points.set(0, p1);
		points.set(1, p2);
		points.set(2, p3);
		return points;
	}

	public PackedCollection<Vector> getData() { return data; }
	
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

		if (enableHardwareOperator) {
			this.data = dataProducer.evaluate(getPointData().traverse(0))
								.reshape(shape(4, 3)).traverse(1);
		} else {
			Vector a = this.p2.subtract(this.p1);
			Vector b = this.p3.subtract(this.p1);

			Vector normal = a.crossProduct(b);
			normal.normalize();

			this.data.set(0, this.p1.subtract(this.p2));
			this.data.set(1, this.p1.subtract(this.p3));
			this.data.set(2, this.p1);
			this.data.set(3, normal);
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
		Producer<RGB> dcp = getColorAt(point, useT);

		return () -> args -> {
			RGB dc = dcp.get().evaluate(args);

			Vector triple = point.get().evaluate(args);
			if (dc.length() < Intersection.e * 100) return new RGB(0.0, 0.0, 0.0);

			Vector abc = data.get(0);
			Vector def = data.get(1);
			Vector jkl = data.get(2);

			if (intcolor) {
				double g = triple.getX();
				double h = triple.getY();
				double i = triple.getZ();

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
					color.addTo(((Vertex) p1).getColor(w));
					color.addTo(((Vertex) p2).getColor(u));
					color.addTo(((Vertex) p3).getColor(v));
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
		};
	}
	
	/**
	 * Returns a {@link Vector} {@link Evaluable} that represents the vector normal to this sphere
	 * at the point represented by the specified {@link Vector} {@link Evaluable}.
	 */
	@Override
	public Producer<Vector> getNormalAt(Producer<Vector> p) {
		if (smooth && vertexData == null) {
			return () -> args -> {
				Vector point = p.get().evaluate(args);

				double g = point.getX();
				double h = point.getY();
				double i = point.getZ();

				Vector abc = data.get(0);
				Vector def = data.get(1);
				Vector jkl = data.get(2);

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
				n.addTo(((Vertex) p1).getNormal(w));
				n.addTo(((Vertex) p2).getNormal(u));
				n.addTo(((Vertex) p3).getNormal(v));

				if (useT)
					n = getTransform(true).getInverse().transformAsNormal(n);


				n.divideBy(n.length());

				return n;
			};
		} else {
			if (useT && getTransform(true) != null) {
				return getTransform(true).getInverse().transform(
						v(data.get(3)),
						TransformMatrix.TRANSFORM_AS_NORMAL);
			} else {
				return vector(c(((PackedCollection<?>) data.get(3)).clone()));
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
		Producer<Ray> r = ray;
		if (ut) r = t.getInverse().transform(ray);

		if (enableHardwareOperator) {
			Evaluable<Ray> er = r.get();
			// TODO  Perhaps r should be ray...
			return new ShadableIntersection(this, r, () -> new AdaptEvaluable<>(intersectAt, er, new Provider<>(data)));
		} else {
			final Supplier<Evaluable<? extends Ray>> fr = r;

			Producer<Scalar> s = new Producer<>() {
				@Override
				public Evaluable<Scalar> get() {
					return args -> {
						Ray r = fr.get().evaluate(args);

						Vector abc = data.get(0);
						Vector def = data.get(1);
						Vector jkl = Triangle.this.data.get(2).subtract(r.getOrigin());

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
					};
				}
			};

			return new ShadableIntersection(this, r, s);
		}
	}

	@Override
	public Operator<Scalar> get() {
		return null;
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
		throw new UnsupportedOperationException();
		// TODO
		// return BoundingSolid.getBounds(new Positioned[]{p1,p2,p3});
	}
}
