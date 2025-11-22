/*
 * Copyright 2025 Michael Murray
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
import org.almostrealism.algebra.ParticleGroup;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.geometry.BoundingSolid;
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

/**
 * A {@link Triangle} represents a triangle in 3D space, serving as the fundamental
 * polygon primitive for mesh-based geometry in ray tracing and rendering.
 *
 * <p>Triangles can be constructed in two ways:
 * <ul>
 *   <li>Directly from three {@link Vector} vertices (standalone triangle)</li>
 *   <li>From vertex indices referencing a {@link Mesh.VertexData} provider (mesh triangle)</li>
 * </ul>
 *
 * <p>The class supports several rendering features:
 * <ul>
 *   <li><b>Smooth shading</b>: Interpolates vertex normals for Phong-style smooth surfaces</li>
 *   <li><b>Vertex color interpolation</b>: Blends colors across the triangle face</li>
 *   <li><b>Texture coordinates</b>: UV mapping for texture sampling</li>
 *   <li><b>Hardware acceleration</b>: Precomputed data structures for efficient ray intersection</li>
 * </ul>
 *
 * <p>The triangle stores precomputed data in a packed format for efficient ray-triangle
 * intersection using the Moller-Trumbore algorithm variant.
 *
 * @author Michael Murray
 * @see Mesh
 * @see Vertex
 * @see AbstractSurface
 */
public class Triangle extends AbstractSurface implements ParticleGroup, TriangleFeatures {

	private static TriangleFeatures triangleFeat = TriangleFeatures.getInstance();

	private Mesh.VertexData vertexData;
	private int ind1, ind2, ind3;
	
	/** First vertex of the triangle. */
	private Vector p1, p2, p3;

	/** Smooth shading flag - when true, normals are interpolated across the triangle. */
	private boolean smooth;

	/** Vertex color interpolation flag - when true, colors are interpolated across the triangle. */
	private boolean intcolor;

	/** Transform usage flag - when true, transformations are applied during intersection. */
	private boolean useT = true;

	/** Precomputed triangle data for hardware-accelerated intersection. */
	private PackedCollection<Vector> data;

	/**
	 * Evaluable that computes precomputed triangle data from vertex positions.
	 * The output format is a 4x3 matrix containing edge vectors and face normal.
	 */
	protected static final Evaluable<PackedCollection<Vector>> dataProducer;

	/**
	 * Evaluable for computing ray-triangle intersections.
	 * Takes ray data and triangle data as inputs, returns intersection distance.
	 */
	public static final Evaluable<PackedCollection<?>> intersectAt;

	static {
		CollectionProducer<PackedCollection<Vector>> triangle =
				triangleFeat.triangle(Input.value(new TraversalPolicy(3, 3), 0));
		dataProducer = triangle.get();

		intersectAt = TriangleIntersectAt.construct(Input.value(new TraversalPolicy(4, 3), 1),
				Input.value(new TraversalPolicy(false, false, 6), 0)).get();
	}

	/**
	 * Constructs a new {@link Triangle} with all vertices at the origin that is black.
	 */	
	public Triangle() {
		super(null, 1.0, new RGB(0.0, 0.0, 0.0), false);
		this.setVertices(new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, 0.0));
	}
	
	/**
	 * Constructs a new {@link Triangle} with the specified vertices and black color.
	 *
	 * @param p1 the first vertex position
	 * @param p2 the second vertex position
	 * @param p3 the third vertex position
	 */
	public Triangle(Vector p1, Vector p2, Vector p3) {
		this.setVertices(p1, p2, p3);
	}
	
	/**
	 * Constructs a new {@link Triangle} with the specified vertices and color.
	 *
	 * @param p1    the first vertex position
	 * @param p2    the second vertex position
	 * @param p3    the third vertex position
	 * @param color the surface color of the triangle
	 */
	public Triangle(Vector p1, Vector p2, Vector p3, RGB color) {
		super(null, 1.0, color, false);
		this.setVertices(p1, p2, p3);
	}

	/**
	 * Constructs a new {@link Triangle} using vertex indices from a {@link Mesh.VertexData} provider.
	 *
	 * <p>This constructor is used when the triangle is part of a mesh and shares vertex data
	 * with other triangles. The actual vertex positions are looked up from the data provider.
	 *
	 * @param p1    the index of the first vertex in the data provider
	 * @param p2    the index of the second vertex in the data provider
	 * @param p3    the index of the third vertex in the data provider
	 * @param color the surface color of the triangle
	 * @param data  the vertex data provider containing position, color, and normal information
	 */
	public Triangle(int p1, int p2, int p3, RGB color, Mesh.VertexData data) {
		super(color, false);

		this.ind1 = p1;
		this.ind2 = p2;
		this.ind3 = p3;
		this.vertexData = data;

		this.loadVertexData();
	}
	
	/**
	 * Loads vertex positions from the vertex data provider.
	 */
	private void loadVertexData() {
		setVertices(vertexData.getPosition(ind1), vertexData.getPosition(ind2), vertexData.getPosition(ind3));
	}

	/**
	 * Returns the raw vertex positions as a packed collection.
	 *
	 * @return a bank of three vectors containing the vertex positions
	 */
	public PackedCollection<Vector> getPointData() {
		PackedCollection<Vector> points = Vector.bank(3);
		points.set(0, p1);
		points.set(1, p2);
		points.set(2, p3);
		return points;
	}

	/**
	 * Returns the precomputed triangle data used for hardware-accelerated intersection.
	 *
	 * <p>The data is a 4x3 matrix containing:
	 * <ul>
	 *   <li>Row 0: Edge vector (p2 - p1)</li>
	 *   <li>Row 1: Edge vector (p3 - p1)</li>
	 *   <li>Row 2: Origin offset data</li>
	 *   <li>Row 3: Face normal vector</li>
	 * </ul>
	 *
	 * @return the precomputed triangle data
	 */
	public PackedCollection<Vector> getData() { return data; }
	
	/**
	 * Sets the vertices of this triangle to the specified positions.
	 *
	 * <p>The Vector objects passed to this method are stored by the triangle,
	 * but subsequent changes to those Vector objects will NOT be reflected in
	 * intersection calculations. To update vertex positions, call this method again.
	 *
	 * <p>This method also recomputes the precomputed triangle data used for
	 * hardware-accelerated intersection.
	 *
	 * @param p1 the first vertex position
	 * @param p2 the second vertex position
	 * @param p3 the third vertex position
	 */
	public void setVertices(Vector p1, Vector p2, Vector p3) {
		this.p1 = p1;
		this.p2 = p2;
		this.p3 = p3;

		this.data = dataProducer.evaluate(getPointData().traverse(0))
				.reshape(shape(4, 3)).traverse(1);
	}

	/**
	 * Sets the vertices of this triangle from an array.
	 *
	 * @param v an array of exactly three vectors
	 */
	public void setVertices(Vector v[]) {
		setVertices(v[0], v[1], v[2]);
	}

	/**
	 * Returns the vertices of this triangle as an array of three vectors.
	 *
	 * <p>If this triangle uses a {@link Mesh.VertexData} provider, the positions
	 * are retrieved from the provider. Otherwise, the stored vertex positions are returned.
	 *
	 * @return an array containing the three vertex positions
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
	
	/**
	 * Returns the texture coordinates for all three vertices.
	 *
	 * <p>If no vertex data provider is configured, returns zero coordinates for all vertices.
	 *
	 * @return a 3x2 array of texture coordinates (u, v) for each vertex
	 */
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

		return func(shape(3), args -> {
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
		});
	}
	
	/**
	 * Returns a {@link Vector} {@link Evaluable} that represents the vector normal to this sphere
	 * at the point represented by the specified {@link Vector} {@link Evaluable}.
	 */
	@Override
	public Producer<Vector> getNormalAt(Producer<Vector> p) {
		if (smooth && vertexData == null) {
			return func(shape(3), args -> {
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
			});
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

		Evaluable<Ray> er = r.get();
		// TODO  Perhaps r should be ray...
		return new ShadableIntersection(this, r, func(shape(1),
				args ->
						new AdaptEvaluable<>(intersectAt, er, new Provider<>(data.traverse(0))).evaluate(args)));
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
