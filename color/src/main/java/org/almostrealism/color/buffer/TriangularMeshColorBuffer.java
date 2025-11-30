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
 * @author  Michael Murray
 */
public class TriangularMeshColorBuffer implements ColorBuffer {
	private static final double bytemax = Byte.MAX_VALUE;
	private static final double shortmax = Short.MAX_VALUE;
	
	protected abstract class Node {
		int start, end, third;
		Node left, right;
		abstract void add(int index, short[] uv);
		abstract Node[] get(short[] uv);
		abstract Node[] get(short[] uv, Node n);
		abstract short[] interpolate(Node n, short[] uv);
		abstract double u();
		abstract double v();
		abstract double r();
		abstract double g();
		abstract double b();
	}
	
	protected class ByteNode extends Node {
		public short colorThreshold = 255 * 3;
		
		private final short[] pa;
		private final short[] pb;
		private final short[] ca;
		private final short[] cb;
		private short u, v, du, dv;
		// private byte r, g, b;
		private boolean wasLeft;
		
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
			
			TriangularMeshColorBuffer.this.size++;
		}
		
		public void add(int index, short[] uv) {
			Node[] ns = this.get(uv);
			ByteNode n = (ByteNode) ns[1];
			
			if (n == null) {
				n = (ByteNode) ns[0];
			} else if (n instanceof ByteNode &&
					ns[0] instanceof ByteNode bns) {
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
		
		public short[] interpolate(Node n, short[] uv) {
			return this.interpolate((ByteNode) n, uv);
		}
		
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
		
		double u() { return this.u / shortmax; }
		double v() { return this.v / shortmax; }
		double r() { return (cb[0] - ca[0]) / (shortmax * bytemax); }
		double g() { return (cb[1] - ca[1]) / (shortmax * bytemax); }
		double b() { return (cb[2] - ca[2]) / (shortmax * bytemax); }
	}
	
	private final List coords;
	private final List colors;
	private Node front, back;
	private int size;
	private final int resolution = 128;
	private final short rDelta = (short) (Math.pow(Short.MAX_VALUE / resolution, 2));
	
	public TriangularMeshColorBuffer() {
		this.coords = new ArrayList();
		this.colors = new ArrayList();
		this.clear();
	}
	
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
		
		this.size = 0;
		
		this.front = new ByteNode(3, 1, 0);
		this.back = new ByteNode(3, 1, 0);
	}

	public double getScale() { return 1.0; }
	public void setScale(double m) { }
}
