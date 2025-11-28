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

package org.almostrealism.space;

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.ZeroVector;
import org.almostrealism.color.RGB;

/**
 * A {@link Vertex} represents a vertex in 3D space that extends {@link Vector} with
 * additional properties commonly used in mesh representation: surface normal, color,
 * and texture coordinates.
 *
 * <p>Vertices are the fundamental building blocks of meshes and are used to define
 * the corners of triangles. Each vertex stores:
 * <ul>
 *   <li>Position coordinates (inherited from {@link Vector})</li>
 *   <li>A surface normal vector for lighting calculations</li>
 *   <li>RGB color values for per-vertex coloring</li>
 *   <li>Texture coordinates (u, v) for texture mapping</li>
 * </ul>
 *
 * <p>When a {@link Vertex} is used as part of a {@link Mesh}, its normal is typically
 * computed as the average of the normals of all triangles that share this vertex,
 * enabling smooth shading across triangle boundaries.
 *
 * @author Michael Murray
 * @see Mesh
 * @see Triangle
 * @see Vector
 */
public class Vertex extends Vector {
	private Vector n = new Vector();  // Vertex normals
	private double r, g, b;  // Vertex color
	private double tu, tv;  // TODO  Texture coordinates

	/**
	 * Constructs a new {@link Vertex} at the origin with a zero normal and black color.
	 */
	public Vertex() { }

	/**
	 * Constructs a new {@link Vertex} at the position specified by the given {@link Vector}.
	 * The normal is initialized to zero.
	 *
	 * @param p the position vector for this vertex
	 */
	public Vertex(Vector p) {
		super(p.getX(), p.getY(), p.getZ());
		this.setNormal(new Vector(ZeroVector.getEvaluable().evaluate(), 0));
	}

	/**
	 * Sets the color of this vertex to the specified {@link RGB} color.
	 *
	 * @param c the color to assign to this vertex
	 */
	public void setColor(RGB c) {
		this.r = c.getRed();
		this.g = c.getGreen();
		this.b = c.getBlue();
	}

	/**
	 * Returns the color of this vertex as an {@link RGB} object.
	 *
	 * @return the vertex color
	 */
	public RGB getColor() { return new RGB(this.r, this.g, this.b); }

	/**
	 * Returns the color of this vertex scaled by the specified factor.
	 * This is useful for interpolating colors across a triangle surface.
	 *
	 * @param d the scale factor to apply to each color component
	 * @return a new {@link RGB} with scaled color values
	 */
	public RGB getColor(double d) { return new RGB(d * this.r, d * this.g, d * this.b); }

	/**
	 * Sets the surface normal for this vertex.
	 *
	 * @param norm the normal vector to assign to this vertex
	 */
	public void setNormal(Vector norm) {
		this.n.setTo(norm);
	}

	/**
	 * Returns the surface normal of this vertex.
	 *
	 * @return the normal vector
	 */
	public Vector getNormal() { return n; }

	/**
	 * Returns the surface normal of this vertex scaled by the specified factor.
	 * This is useful for interpolating normals across a triangle surface.
	 *
	 * @param d the scale factor to apply to each normal component
	 * @return a new {@link Vector} with scaled normal values
	 */
	public Vector getNormal(double d) {
		Vector norm = (Vector) n.clone();
		norm.multiplyBy(d);
		return norm;
	}

	/**
	 * Adds the specified normal vector to this vertex's accumulated normal.
	 * This method is typically called when building a mesh to accumulate
	 * the normals of all triangles that share this vertex for smooth shading.
	 *
	 * @param norm the normal vector to add
	 */
	public void addNormal(Vector norm) {
		this.n.addTo(norm);
	}

	/**
	 * Subtracts the specified normal vector from this vertex's accumulated normal.
	 * This method is typically called when removing a triangle from a mesh.
	 *
	 * @param norm the normal vector to subtract
	 */
	public void removeNormal(Vector norm) {
		this.n.subtractFrom(norm);
	}

	// public boolean equals(Object obj) { return (obj instanceof Vertex && super.equals(obj)); }
}
