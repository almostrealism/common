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

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.ZeroVector;
import org.almostrealism.color.RGB;

public class Vertex extends Vector {
	private Vector n = new Vector();  // Vertex normals
	private double r, g, b;  // Vertex color
	private double tu, tv;  // TODO  Texture coordinates

	public Vertex() { }

	public Vertex(Vector p) {
		super(p.getX(), p.getY(), p.getZ());
		this.setNormal(ZeroVector.getProducer().evaluate());
	}

	public void setColor(RGB c) {
		this.r = c.getRed();
		this.g = c.getGreen();
		this.b = c.getBlue();
	}

	public RGB getColor() { return new RGB(this.r, this.g, this.b); }
	public RGB getColor(double d) { return new RGB(d * this.r, d * this.g, d * this.b); }

	public void setNormal(Vector norm) {
		this.n.setTo(norm);
	}

	public Vector getNormal() { return n; }
	public Vector getNormal(double d) {
		Vector norm = (Vector) n.clone();
		norm.multiplyBy(d);
		return norm;
	}

	public void addNormal(Vector norm) {
		this.n.addTo(norm);
	}

	public void removeNormal(Vector norm) {
		this.n.subtractFrom(norm);
	}

	// public boolean equals(Object obj) { return (obj instanceof Vertex && super.equals(obj)); }
}
