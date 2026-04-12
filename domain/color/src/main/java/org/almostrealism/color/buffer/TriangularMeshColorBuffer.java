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

package org.almostrealism.color.buffer;

import org.almostrealism.color.RGB;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link ColorBuffer} that stores color data in a triangular-mesh binary tree,
 * enabling interpolation of colors across an irregular triangulated UV grid.
 *
 * <p>The UV domain is subdivided into triangles represented by {@link ByteNode}s.
 * When a new color sample is added, the tree is traversed to find the enclosing
 * triangle, and the triangle may be subdivided if the new sample is sufficiently
 * different. Color retrieval uses barycentric interpolation along the tree edges.</p>
 *
 * <p>Color components are quantised to the range of {@link Short#MAX_VALUE} for
 * compact storage.</p>
 *
 * @see ColorBuffer
 * @author Michael Murray
 */
public class TriangularMeshColorBuffer implements ColorBuffer {
	/** The maximum value of a {@link Byte}, used for color gradient scaling. */
	private static final double bytemax = Byte.MAX_VALUE;

	/** The maximum value of a {@link Short}, used for fixed-point UV and color scaling. */
	private static final double shortmax = Short.MAX_VALUE;

	/**
	 * Abstract binary tree node representing a triangular region of UV space.
	 *
	 * <p>Each node spans a triangle defined by three vertex indices ({@code start},
	 * {@code end}, {@code third}) into the coordinate and color lists of the enclosing
	 * {@link TriangularMeshColorBuffer}. Subtrees cover sub-triangles after refinement.</p>
	 */
	protected static abstract class Node {
		/** Index of the first (start) vertex of this triangle in the coordinate list. */
		int start;

		/** Index of the second (end) vertex of this triangle in the coordinate list. */
		int end;

		/** Index of the third vertex of this triangle in the coordinate list. */
		int third;

		/** The left child node covering one half of this triangle after refinement. */
		Node left;

		/** The right child node covering the other half of this triangle after refinement. */
		Node right;

		/** Inserts a new vertex sample into the subtree. */
		abstract void add(int index, short[] uv);

		/** Finds the leaf node(s) containing the given UV coordinates. */
		abstract Node[] get(short[] uv);

		/** Finds the leaf node(s) containing the given UV coordinates relative to ancestor {@code n}. */
		abstract Node[] get(short[] uv, Node n);

		/** Interpolates the color at the given UV from this node and neighbor {@code n}. */
		abstract short[] interpolate(Node n, short[] uv);

		/** Returns the U component of the edge vector (scaled). */
		abstract double u();

		/** Returns the V component of the edge vector (scaled). */
		abstract double v();

		/** Returns the R (red) color gradient along the edge. */
		abstract double r();

		/** Returns the G (green) color gradient along the edge. */
		abstract double g();

		/** Returns the B (blue) color gradient along the edge. */
		abstract double b();
	}

	/**
	 * A concrete {@link Node} that stores its UV and color data using short-integer fixed-point encoding.
	 *
	 * <p>The node represents a directed edge from vertex {@code start} to vertex {@code end}. The
	 * UV coordinates of the endpoints are read from the parent buffer's coordinate list, and color
	 * data is read from the color list. On refinement, child nodes cover the sub-triangles formed
	 * by inserting a new vertex.</p>
	 */
	protected class ByteNode extends Node {
		/** Maximum allowed color difference between adjacent nodes before a new vertex is inserted. */
		public short colorThreshold = 255 * 3;
		
		/** UV coordinates of the start vertex (quantised to short range). */
		private final short[] pa;

		/** UV coordinates of the end vertex (quantised to short range). */
		private final short[] pb;

		/** Color of the start vertex (quantised to short range per channel). */
		private final short[] ca;

		/** Color of the end vertex (quantised to short range per channel). */
		private final short[] cb;

		/** U component of the edge vector ({@code pb.u - pa.u}) in short units. */
		private short u;

		/** V component of the edge vector ({@code pb.v - pa.v}) in short units. */
		private short v;

		/** Interpolation delta in U computed during the most recent interpolation call. */
		private short du;

		/** Interpolation delta in V computed during the most recent interpolation call. */
		private short dv;

		/** Indicates which side of the edge the last traversal step followed. */
		private boolean wasLeft;
		
		/**
		 * Constructs a {@link ByteNode} representing the directed edge from vertex
		 * {@code start} to vertex {@code end}, with {@code third} as the opposite vertex.
		 *
		 * @param start the index of the first (start) vertex
		 * @param end   the index of the second (end) vertex
		 * @param third the index of the opposite triangle vertex
		 */
		ByteNode(int start, int end, int third) {
			this.start = start;
			this.end = end;
			this.third = third;
			
			this.pa = (short[]) TriangularMeshColorBuffer.this.coords.get(start);
			this.pb = (short[]) TriangularMeshColorBuffer.this.coords.get(end);
			this.ca = (short[]) TriangularMeshColorBuffer.this.colors.get(start);
			this.cb = (short[]) TriangularMeshColorBuffer.this.colors.get(end);
			
			this.u = (short) (pb[0] - pa[0]);
			this.v = (short) (pb[1] - pa[1]);
			
//			this.r((cb[0] - ca[0]) / shortmax);
//			this.g((cb[1] - ca[1]) / shortmax);
//			this.b((cb[2] - ca[2]) / shortmax);
			
		}
		
		/**
		 * Inserts a new vertex at the given index into the subtree, optionally merging
		 * with an existing edge if the new sample is within the color and UV tolerances.
		 *
		 * @param index the index into the coordinate and color lists for the new vertex
		 * @param uv    the UV coordinates of the new vertex in fixed-point short units
		 */
		@Override
		public void add(int index, short[] uv) {
			Node[] ns = this.get(uv);
			ByteNode n = (ByteNode) ns[1];
			
			if (n == null) {
				n = (ByteNode) ns[0];
			} else if (n instanceof ByteNode &&
					ns[0] instanceof ByteNode) {
				ByteNode bns = (ByteNode) ns[0];
				short[] rgb = bns.interpolate(n, uv);
				short[] nrgb = (short[]) TriangularMeshColorBuffer.this.colors.get(index);
				
				if (bns.du * bns.du +
						bns.dv * bns.du < rDelta ||
						Math.abs(nrgb[0] - rgb[0] +
						nrgb[1] - rgb[1] +
						nrgb[2] - rgb[2]) < this.colorThreshold) {
					bns.cb[0] = (short) ((rgb[0] + nrgb[0]) / 2);
					bns.cb[1] = (short) ((rgb[1] + nrgb[1]) / 2);
					bns.cb[2] = (short) ((rgb[2] + nrgb[2]) / 2);
					
					TriangularMeshColorBuffer.this.coords.remove(index);
					TriangularMeshColorBuffer.this.colors.remove(index);
					
					return;
				}
			}
			
			if (n.wasLeft) {
				n.left = new ByteNode(this.start, index, this.third);
				n.left.left = new ByteNode(index, this.third, this.end);
				n.left.right = new ByteNode(index, this.end, this.third);
			} else {
				n.right = new ByteNode(this.start, index, this.third);
				n.right.right = new ByteNode(index, this.third, this.end);
				n.right.left = new ByteNode(index, this.end, this.third);
			}
		}
		
		/**
		 * Finds the leaf node(s) enclosing the given UV coordinates, starting the traversal from this node.
		 *
		 * @param uv the UV coordinates to locate, in fixed-point short units
		 * @return an array of two nodes: the deepest ancestor reached and the leaf node, or {@code null} for the leaf
		 */
		@Override
		public Node[] get(short[] uv) {
			this.wasLeft = v * (uv[0] - pa[0]) / u > uv[1];
			
			if (this.wasLeft && this.left == null) {
				return new Node[] {this, null};
			} else if (!this.wasLeft && this.right == null) {
				return new Node[] {this, null};
			} else if (this.wasLeft) {
				return this.left.get(uv, this);
			} else {
				return this.right.get(uv, this);
			}
		}
		
		/**
		 * Continues the traversal to locate the leaf node(s) enclosing the given UV coordinates,
		 * with {@code n} as the most recently visited ancestor.
		 *
		 * @param uv the UV coordinates to locate, in fixed-point short units
		 * @param n  the most recently visited ancestor node
		 * @return an array of two nodes: the updated ancestor and the leaf node
		 */
		@Override
		public Node[] get(short[] uv, Node n) {
			if (this.u == 0)
				this.wasLeft = pa[0] > uv[0];
			else
				this.wasLeft = pa[1] + v * (uv[0] - pa[0]) / u > uv[1];
			
			if (this.wasLeft && this.left == null) {
				return new Node[] {n, this};
			} else if (!this.wasLeft && this.right == null) {
				return new Node[] {n, this};
			} else if (this.wasLeft) {
				return this.left.get(uv, this);
			} else {
				return this.right.get(uv, this);
			}
		}
		
		/**
		 * Interpolates the color at the given UV position using this node and its neighbor {@code n}.
		 *
		 * @param n  the neighboring node used for gradient computation
		 * @param uv the UV position at which to interpolate, in fixed-point short units
		 * @return the interpolated color as a {@code short[3]} in fixed-point short units per channel
		 */
		@Override
		public short[] interpolate(Node n, short[] uv) {
			return this.interpolate((ByteNode) n, uv);
		}

		/**
		 * Interpolates the color at the given UV position using the color gradients of this node and {@code n}.
		 *
		 * @param n  the neighboring {@link ByteNode}
		 * @param uv the UV position at which to interpolate, in fixed-point short units
		 * @return the interpolated color as a {@code short[3]} in fixed-point short units per channel
		 */
		public short[] interpolate(ByteNode n, short[] uv) {
			short[] d = {(short) (uv[0] - this.pb[0]),
							(short) (uv[1] - this.pb[1])};
			this.du = (short) ((d[0] * this.u + d[1] * this.v) / -Short.MAX_VALUE);
			this.dv = (short) ((d[1] * n.u + d[1] * n.v) / Short.MAX_VALUE);
			
			short[] rgb = {(short) (cb[0] + (du * this.r() + dv * n.r()) / Byte.MAX_VALUE),
							(short) (cb[1] + (du * this.g() + dv * n.g()) / Byte.MAX_VALUE),
							(short) (cb[2] + (du * this.b() + dv * n.b()) / Byte.MAX_VALUE)};
			
			return rgb;
		}
		
		void u(double u) { this.u = (short) (u * shortmax); }
		void v(double v) { this.v = (short) (v * shortmax); }
//		void r(double r) { this.r = (byte) (r * bytemax); }
//		void g(double g) { this.g = (byte) (g * bytemax); }
//		void b(double b) { this.b = (byte) (b * bytemax); }
		
		@Override
		double u() { return this.u / shortmax; }
		@Override
		double v() { return this.v / shortmax; }
		@Override
		double r() { return (cb[0] - ca[0]) / (shortmax * bytemax); }
		@Override
		double g() { return (cb[1] - ca[1]) / (shortmax * bytemax); }
		@Override
		double b() { return (cb[2] - ca[2]) / (shortmax * bytemax); }
	}
	
	/** List of UV coordinate pairs (each a {@code short[2]}) for all vertices. */
	private final List coords;

	/** List of color triples (each a {@code short[3]}) for all vertices. */
	private final List colors;

	/** Root node of the front-surface binary tree. */
	private Node front;

	/** Root node of the back-surface binary tree. */
	private Node back;


	/** The angular resolution used to determine when adjacent samples are close enough to merge. */
	private final int resolution = 128;

	/** The squared UV distance threshold below which adjacent samples are merged rather than subdivided. */
	private final short rDelta = (short) (Math.pow(Short.MAX_VALUE / resolution, 2));
	
	/**
	 * Constructs an empty {@link TriangularMeshColorBuffer}, initialising the four corner
	 * vertices of the UV domain and the front and back root nodes.
	 */
	public TriangularMeshColorBuffer() {
		this.coords = new ArrayList();
		this.colors = new ArrayList();
		this.clear();
	}
	
	/**
	 * Adds a color sample at the given UV coordinates, inserting a new vertex into the mesh tree.
	 *
	 * @param u     the horizontal texture coordinate in [0, 1)
	 * @param v     the vertical texture coordinate in [0, 1)
	 * @param front {@code true} to insert into the front tree, {@code false} for the back tree
	 * @param c     the color to store at the new vertex
	 */
	@Override
	public void addColor(double u, double v, boolean front, RGB c) {
		short[] uv = {(short) (u * shortmax), (short) (v * shortmax)};
		short[] rgb = {(short) (c.getRed() * shortmax),
						(short) (c.getGreen() * shortmax),
						(short) (c.getBlue() * shortmax)};
		
		this.coords.add(uv);
		this.colors.add(rgb);
		
		if (front)
			this.front.add(this.coords.size() - 1, uv);
		else
			this.back.add(this.coords.size() - 1, uv);
	}
	
	/**
	 * Returns the interpolated color at the given UV coordinates.
	 *
	 * @param u     the horizontal texture coordinate in [0, 1)
	 * @param v     the vertical texture coordinate in [0, 1)
	 * @param front {@code true} to query the front tree, {@code false} for the back tree
	 * @return the interpolated {@link RGB} at the given UV position
	 */
	@Override
	public RGB getColorAt(double u, double v, boolean front) {
		short[] uv = {(short) (u * shortmax), (short) (v * shortmax)};
		Node[] n;
		
		if (front)
			n = this.front.get(uv);
		else
			n = this.back.get(uv);
		
		short[] rgb = n[0].interpolate(n[1], uv);
		return new RGB(48, rgb[0] / shortmax, rgb[1] / shortmax, rgb[2] / shortmax);
	}
	
	/**
	 * Resets the buffer to its initial state, clearing all stored vertices and reinitialising
	 * the four UV-domain corner vertices and the front and back root nodes.
	 */
	@Override
	public void clear() {
		this.coords.clear();
		this.colors.clear();
		
		short[] one = {0, 0};
		short[] two = {Short.MAX_VALUE, 0};
		short[] three = {Short.MAX_VALUE, Short.MAX_VALUE};
		short[] four = {0, Short.MAX_VALUE};
		
		this.coords.add(one);
		this.coords.add(two);
		this.coords.add(three);
		this.coords.add(four);
		for (int i = 0; i < 4; i++) this.colors.add(new short[] {0, 0, 0});
		
		this.front = new ByteNode(3, 1, 0);
		this.back = new ByteNode(3, 1, 0);
	}

	/**
	 * Returns the scale factor; always {@code 1.0} as scaling is not supported by this buffer.
	 *
	 * @return {@code 1.0}
	 */
	@Override
	public double getScale() { return 1.0; }

	/**
	 * Scale is not supported by this buffer; this method is a no-op.
	 *
	 * @param m the scale value (ignored)
	 */
	@Override
	public void setScale(double m) { }
}
