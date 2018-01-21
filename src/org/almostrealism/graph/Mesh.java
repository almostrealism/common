/*
 * Copyright 2017 Michael Murray
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

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import org.almostrealism.algebra.Intersection;
import org.almostrealism.algebra.Ray;
import org.almostrealism.algebra.Triple;
import org.almostrealism.algebra.Vector;
import org.almostrealism.color.ColorProducer;
import org.almostrealism.color.RGB;
import org.almostrealism.color.ShaderContext;
import org.almostrealism.io.FileDecoder;
import org.almostrealism.io.SpatialData;
import org.almostrealism.space.ShadableIntersection;
import org.almostrealism.space.ShadableSurface;
import org.almostrealism.space.ShadableSurfaceWrapper;
import org.almostrealism.space.SpacePartition;

// TODO  Add bounding solid to make intersection calc faster.

/**
 * A {@link Mesh} object stores a set of points (Vector objects)
 * and allows triangles to be specified using those points.
 * 
 * @author  Michael Murray
 */
public class Mesh extends SpacePartition<Triangle> implements Automata<Vector, Triangle> {
	private static RGB white = new RGB(1.0, 1.0, 1.0);
	
	public static class MeshFile implements ShadableSurfaceWrapper, ShadableSurface {
		private String name;
		private int format;
		private String url;
		private Mesh mesh;
		private ShadableSurface s;
		
		public void setFile(String f) { this.name = f; }
		public String getFile() { return this.name; }
		public void setFormat(int f) { this.format = f; }
		public int getFormat() { return this.format; }
		public void setURL(String url) { this.url = url; }
		public String getURL() { return this.url; }
		
		public void setSurface(ShadableSurface s) { this.s = s; }
		
		public ShadableSurface getSurface() {
			if (this.mesh == null) {
				try {
					if (this.url != null) {
						URL url = new URL(this.url + this.name);
						
						this.mesh = (Mesh) SpatialData.decodeScene(url.openStream(),
								this.format, false, null, this.s).getSurfaces()[0];
					} else {
						this.mesh = (Mesh) FileDecoder.decodeSurfaceFile(
									new File(this.name), this.format, false, null, this.s);
					}
				} catch (IOException ioe) {
					System.out.println("Mesh.MeshFile: IO error loading mesh data - " +
										ioe.getMessage());
				}
				
				if (this.mesh == null) {
					System.out.println("Mesh.MeshFile: Unexpected failure loading mesh data.");
					return null;
				}
				
				this.mesh.setMeshFile(this);
				this.mesh.loadTree();
			}
			
			return this.mesh;
		}
		
		@Override public boolean getShadeFront() { return this.getSurface().getShadeFront(); }
		@Override public boolean getShadeBack() { return this.getSurface().getShadeBack(); }
		@Override public ColorProducer getColorAt(Vector point) { return this.getSurface().getColorAt(point); }
		@Override public Vector getNormalAt(Vector point) { return this.getSurface().getNormalAt(point); }
		@Override public boolean intersect(Ray ray) { return this.getSurface().intersect(ray); }
		@Override public ShadableIntersection intersectAt(Ray ray) { return this.getSurface().intersectAt(ray); }
		@Override public ColorProducer shade(ShaderContext p) { return this.getSurface().shade(p); }
		@Override
		public Vector operate(Triple in) { return getSurface().operate(in); }
		@Override
		public ColorProducer call() throws Exception { return getSurface().call(); }
	}
	
	public static class Vertex extends Vector {
		private double nx, ny, nz;  // Vertex normals
		private double r, g, b;  // Vertex color
		private double tu, tv;  // TODO  Texture coordinates
		
		public Vertex() { }
		
		public Vertex(Vector p) {
			super(p.getX(), p.getY(), p.getZ());
			this.setNormal(0.0, 0.0, 0.0);
		}
		
		public void setColor(RGB c) {
			this.r = c.getRed();
			this.g = c.getGreen();
			this.b = c.getBlue();
		}
		
		public RGB getColor() { return new RGB(this.r, this.g, this.b); }
		public RGB getColor(double d) { return new RGB(d * this.r, d * this.g, d * this.b); }
		
		public void setNormal(double x, double y, double z) {
			this.nx = x;
			this.ny = y;
			this.nz = z;
		}
		
		public void setNormal(Vector n) { this.setNormal(n.getX(), n.getY(), n.getZ()); }
		public Vector getNormal() { return new Vector(this.nx, this.ny, this.nz); }
		public Vector getNormal(double d) { return new Vector(d * this.nx, d * this.ny, d * this.nz); }
		
		public void addNormal(Vector n) {
			this.nx += n.getX();
			this.ny += n.getY();
			this.nz += n.getZ();
		}
		
		public void removeNormal(Vector n) {
			this.nx -= n.getX();
			this.ny -= n.getY();
			this.nz -= n.getZ();
		}
		
		// public boolean equals(Object obj) { return (obj instanceof Vertex && super.equals(obj)); }
	}
	
	public static interface VertexData {
		public double getRed(int index);
		public double getGreen(int index);
		public double getBlue(int index);
		
		public double getX(int index);
		public double getY(int index);
		public double getZ(int index);
		
		public double getTextureU(int index);
		public double getTextureV(int index);
		
		public int[] getTriangle(int index);
		public int getTriangleCount();
		
		public int getVertexCount();
	}
	
  private List points, triangles;
  private Triangle tcache[];
  private ShadableIntersection inter[];
  private boolean ignore[];
  private boolean smooth, removeBackFaces, intcolor;
  
  private MeshFile file;
  private VertexData vertexData;

  	/**
  	 * Constructs a new Mesh object.
  	 */
  	public Mesh() {
  		this.points = new ArrayList();
  		this.triangles = new ArrayList();
  		this.clearIntersectionCache();
  		this.clearIgnoreCache();
  		this.clearTriangleCache();
  	}
  	
  	/**
  	 * Constructs a new Mesh object.
  	 * 
  	 * @param points  Array of points to use.
  	 * @param triangles  {{x0, y0, z0}, {x1, y1, z1},...} Where the int values
  	 * 					are indices in the points array.
  	 */
  	public Mesh(Vector points[], int triangles[][]) {
  		this.points = new ArrayList();
  		this.triangles = new ArrayList();
  		this.clearIntersectionCache();
  		this.clearIgnoreCache();
  		this.clearTriangleCache();
  		
  		for (int i = 0; i < points.length; i++) this.addVector(points[i]);
  		
  		for (int i = 0; i < triangles.length; i++)
  			this.addTriangle(triangles[i][0], triangles[i][1], triangles[i][2]);
  	}
  	
  	public Mesh(VertexData data) {
  		this.vertexData = data;
  		
  		this.clearIntersectionCache();
	  	this.clearIgnoreCache();
	  	this.clearTriangleCache();
  	}
  	
  	private void setMeshFile(MeshFile f) { this.file = f; }
  	
  	/**
  	 * Adds the point defined by the specified Vector object to the mesh
  	 * represented by this Mesh object.
  	 * 
  	 * @param p  Vector object to add.
  	 * @return  The unique index of the point to be used when adding triangles.
  	 */
  	public int addVector(Vector p) {
  		if (this.points.add(new Vertex(p)))
  			return (this.points.size() - 1);
  		else
  			return -1;
  	}
  	
  	/**
  	 * Adds the triangle described by the specified points to the mesh
  	 * represented by this Mesh object.
  	 * 
  	 * @param p1  Index of first point.
  	 * @param p2  Index of second point.
  	 * @param p3  Index of third point.
  	 * @throws IllegalArgumentException if any of the indicies specified are not valid.
  	 * @return  The unique index of the triangle added or -1 if it is not added (if any point indicies are the same).
  	 */
  	public int addTriangle(int p1, int p2, int p3) {
  		if (p1 == p2 || p2 == p3 || p3 == p1) return -1;
  		
  		Vertex v1 = (Vertex) this.points.get(p1);
  		Vertex v2 = (Vertex) this.points.get(p2);
  		Vertex v3 = (Vertex) this.points.get(p3);
  		
  		Triangle t = new Triangle(v1, v2, v3);
  		
  		Vector tn = t.getNormalAt(new Vector());
  		
  		if (this.triangles.add(new int[] {p1, p2, p3})) {
  	  		v1.addNormal(tn);
  			v2.addNormal(tn);
  			v3.addNormal(tn);
  			
  			this.clearIgnoreCache();
  			this.clearTriangleCache();
  			this.clearIntersectionCache();
  			
  			return (this.triangles.size() - 1);
  		} else {
  			return -1;
  		}
  	}
	
	public void clearIgnoreCache() {
		if (this.vertexData == null)
			this.ignore = new boolean[this.triangles.size()];
		else
			this.ignore = new boolean[this.vertexData.getTriangleCount()];
	}
	
	public void loadTree() {
		this.loadTriangles();
		super.loadTree(this.tcache.length);
	}
	
	public void loadTriangles() {
		this.clearTriangleCache();
		
		for (int i = 0; i < tcache.length; i++)
			this.tcache[i] = this.getTriangle(i);
	}
	
	public void clearTriangleCache() {
		if (this.vertexData == null)
			this.tcache = new Triangle[this.triangles.size()];
		else
			this.tcache = new Triangle[this.vertexData.getTriangleCount()];
	}
	
	public synchronized void clearIntersectionCache() {
		if (this.vertexData == null)
			this.inter = new ShadableIntersection[this.triangles.size()];
		else
			this.inter = new ShadableIntersection[this.vertexData.getTriangleCount()];
	}
  	
  	/**
  	 * Removes all points stored by this Mesh and adds those stored in the specified array.
  	 * 
  	 * @param p  Array of Vertex objects to add.
  	 */
  	public void setVectors(Vertex p[]) {
  		this.points.clear();
  		for (int i = 0; i < p.length; i++) this.points.add(p[i]);
  	}
  	
  	/**
  	 * @return  An array of Mesh.Vertex objects stored by this Mesh object.
  	 */
  	public Vertex[] getVectors() { return this.getVectors(this.file == null); }
  	
  	public Vertex[] getVectors(boolean b) {
  		if (this.points == null) return null;
  		
  		if (b)
  			return (Vertex[]) this.points.toArray(new Vertex[0]);
  		else
  			return null;
  	}
  	
  	public Iterator iterateVectors() { return this.points.iterator(); }
  	
  	public void setTriangleData(int data[][]) {
  		this.triangles.clear();
  		for (int i = 0; i < data.length; i++) this.triangles.add(data[i]);
  		
  		this.clearIgnoreCache();
  		this.clearIntersectionCache();
  		this.clearTriangleCache();
  	}
  	
  	public int[][] getTriangleData() {
  		return this.getTriangleData(this.file == null);
  	}
  	
  	public int[][] getTriangleData(boolean b) {
  		if (b)
  			return (int[][]) this.triangles.toArray(new int[0][0]);
  		else
  			return null;
  	}
  	
	public Iterable<Triangle> triangles() {
		return () -> {
			if (vertexData == null) {
				return Arrays.asList(getTriangles()).iterator();
			} else {
				return new Iterator<Triangle>() {
					int i = 0;
					
					@Override
					public boolean hasNext() {
						return i < vertexData.getTriangleCount();
					}
					
					@Override
					public Triangle next() {
						int t[] = vertexData.getTriangle(i);
						i++;
						return new Triangle(t[0], t[1], t[2],
								(RGB) Mesh.white.clone(), vertexData);
					}
				};
			}
		};
	}
	
	/**
	 * Returns the {@link VertexData} which provides the mesh data,
	 * if this {@link Mesh} was initialized with a {@link VertexData}
	 * implementation.
	 */
	public VertexData getVertexData() { return vertexData; }
	
	/** @return  An array of Triangle objects stored by this {@link Mesh} object. */
	public Triangle[] getTriangles() {
		Triangle t[] = new Triangle[this.triangles.size()];
		for (int i = 0; i < t.length; i++) t[i] = getTriangle(i);

		return t;
	}

	/**
	 * Checks triangle cache for the specified face index, and constructs a {@link Triangle}
	 * object if it is not present.
	 * 
	 * @param face  Index of face.
	 * @return  The Triangle requested.
	 */
	public Triangle getTriangle(int face) { return this.getTriangle(face, false); }
	
	/**
	 * Checks triangle cache for the specified face index, and constructs a Triangle
	 * object if it is not present. This Mesh object makes no guarantee that Triangle
	 * objects stored in the triangle cache will reflect changes to the state of the
	 * Mesh made after the cache is made. See the clearTriangleCache method...
	 * 
	 * 
	 * @param face  Index of face.
	 * @param cache  True if Triangle should be cached if it is created, false otherwise.
	 * @return  The Triangle object requested.
	 */
	public Triangle getTriangle(int face, boolean cache) {
		Triangle t = null;
		
		if (this.tcache[face] != null) return this.tcache[face];
		
		if (this.vertexData == null) {
			int v[] = (int[]) this.triangles.get(face);
			
			Vertex v1 = (Vertex) this.points.get(v[0]);
			Vertex v2 = (Vertex) this.points.get(v[1]);
			Vertex v3 = (Vertex) this.points.get(v[2]);
			
			t = new Triangle(v1, v2, v3, (RGB) Mesh.white.clone());
		} else {
			int d[] = this.vertexData.getTriangle(face);
			t = new Triangle(d[0], d[1], d[2], (RGB) Mesh.white.clone(), this.vertexData);
		}
		
		t.setParent(this);
		t.setColor(new RGB(1.0, 1.0, 1.0));
		t.setSmooth(this.smooth);
		t.setUseTransform(false);
		t.setInterpolateVertexColor(this.intcolor);
		
		if (cache) this.tcache[face] = t;
		
		return t;
	}
  	
  	/**
  	 * @param v  Vector to return index of.
  	 * @return  The integer index of the specified Vector object stored by this mesh,
  	 *          -1 if the it is not found.
  	 */
  	public int indexOf(Vector v) {
  		Iterator itr = this.points.iterator();
  		for (int i = 0; itr.hasNext(); i++) if (itr.next().equals(v)) return i;
  		
  		return -1;
  	}
  	
	/**
	 * @return  null.
	 */
	public Vector getNormalAt(Vector point) { return null; }
	
	/**
	 * Sets the smooth flag for this Mesh object. If the flag is true,
	 * the surface normals will be interpolated to produce a smoothing effect.
	 * 
	 * @param smooth  Boolean flag to use.
	 */
	public void setSmooth(boolean smooth) { this.smooth = smooth; }
	
	/**
	 * @return  True if this Mesh object will be smoothed when rendered, false otherwise.
	 */
	public boolean getSmooth() { return this.smooth; }
	
	/**
	 * @param b  True if bace faces should be ignored, false if they should be included.
	 */
	public void setRemoveBackFaces(boolean b) { this.removeBackFaces = b; }
	
	/**
	 * @return  True if back faces will be ignored, false if they will be included.
	 */
	public boolean getRemoveBackFaces() { return this.removeBackFaces; }
	
	/**
	 * @param c  If set to true, color will be interpolated based on vertex colors.
	 */
	public void setInterpolateColor(boolean c) { this.intcolor = c; }
	
	/**
	 * @return  True if color will be interpolated based on vertex colors, false otherwise.
	 */
	public boolean getInterpolateColor() { return this.intcolor; }
	
	public int getIndex(Vector v) { return this.points.indexOf(v); }
	
	/**
	 * Downsamples this mesh by setting the ignore flag for some triangles.
	 * Each triangle is given an 'l' value that is the sum of the side lengths.
	 * If this value is below the specified threshold, it is ignored with a
	 * with the specified probability. This can be undone by calling
	 * clearIgnoreCache. Also note that the cache must be loaded for this to work,
	 * so before using it you must call clearIgnoreCache.
	 * 
	 * @param l  Threshold perimeter value.
	 * @param p  Probability of elimination (0.0 - 1.0).
	 * @return  The number of ignored triangles.
	 */
	public int downsample(double l, double p) {
		int t[][] = this.getTriangleData(true);
		Vector v[] = this.getVectors(true);
		
		int total = 0;
		
		for (int i = 0; i < t.length; i++) {
			Vector v0 = v[t[i][0]];
			Vector v1 = v[t[i][1]];
			Vector v2 = v[t[i][2]];
			
			double d0 = v0.subtract(v1).length();
			double d1 = v1.subtract(v2).length();
			double d2 = v2.subtract(v0).length();
			
			if ((d0 + d1 + d2) < l && Math.random() < p) {
				this.ignore[i] = true;
				total++;
			}
		}
		
		return total;
	}
	
	/**
	 * Extrudes the specified face in the direction of its surface normal by the distance specified.
	 * 
	 * @param face  Index of triangle to extrude.
	 * @param l  Length to extrude.
	 * @return  The index of the triangle at the opposite end of the prisim that is created by extrusion.
	 */
	public int extrudeFace(int face, double l) {
		return this.extrudeFace(face,
				((Triangle) this.triangles.get(face)).getNormalAt(new Vector()).multiply(l));
	}
	
	/**
	 * Extrudes the specified face with an offset of the specified vector.~
	 * This method probably does not work properly.
	 * 
	 * @param face  Index of triangle to extrude.
	 * @param n  Normal to offset by.
	 * @return  The index of the triangle at the opposite end of the prisim that is created by extrusion.
	 */
	public int extrudeFace(int face, Vector n) {
		// Triangle t = (Triangle) this.triangles.get(face);
		Triangle t = this.getTriangle(face);
		
		Vector v1 = t.getVertices()[0];
		Vector v2 = t.getVertices()[1];
		Vector v3 = t.getVertices()[2];
		
		int p1 = this.indexOf(v1);
		int p2 = this.indexOf(v2);
		int p3 = this.indexOf(v3);
		
		int a = this.addVector(v1.add(n));
		int b = this.addVector(v2.add(n));
		int c = this.addVector(v3.add(n));
		
		this.addTriangle(p1, p2, a);
		this.addTriangle(p2, b, a);
		this.addTriangle(p2, p3, b);
		this.addTriangle(p2, c, b);
		this.addTriangle(p3, p1, c);
		this.addTriangle(p3, a, c);
		
		return this.addTriangle(a, b, c);
	}
	
	/**
	 * Extrudes the specified face with an offset of the specified vector.
	 * 
	 * @param p1  Index of first point of triangle to extrude.
	 * @param p1  Index of second point of triangle to extrude.
	 * @param p1  Index of third point of triangle to extrude.
	 * @param n  Normal to offset by.
	 * @return  The index of the triangle at the opposite end of the prisim that is created by extrusion.
	 */
	public int extrudeFace(int p1, int p2, int p3, Vector n) {
		Vector v1 = (Vector) this.points.get(p1);
		Vector v2 = (Vector) this.points.get(p2);
		Vector v3 = (Vector) this.points.get(p3);
		
		int a = this.addVector(v1.add(n));
		int b = this.addVector(v2.add(n));
		int c = this.addVector(v3.add(n));
		
		this.addTriangle(p1, p2, a);
		this.addTriangle(p2, b, a);
		this.addTriangle(p2, p3, b);
		this.addTriangle(p2, c, b);
		this.addTriangle(p3, p1, c);
		this.addTriangle(p3, a, c);
		
		return this.addTriangle(a, b, c);
	}
	
	/**
	 * @return  this.
	 */
	public Mesh triangulate() { return this; }
	
	/**
	 * @see com.almostrealism.raytracer.engine.ShadableSurface#intersect(org.almostrealism.algebra.Ray)
	 */
	public synchronized boolean intersect(Ray ray) {
		if (this.isTreeLoaded()) return super.intersect(ray);
		
		ray.transform(this.getTransform(true).getInverse());
		
		Iterator itr = this.triangles.iterator();
		
		Triangle tr = new Triangle();
		
		while (itr.hasNext()) {
			int v[] = (int[]) itr.next();
			
	  		Vertex v1 = (Vertex)this.points.get(v[0]);
	  		Vertex v2 = (Vertex)this.points.get(v[1]);
	  		Vertex v3 = (Vertex)this.points.get(v[2]);
	  		
			tr.setVertices(v1, v2, v3);
			
			if (tr.intersect(ray)) return true;
		}
		
		return false;
	}

	/**
	 * @see com.almostrealism.raytracer.engine.ShadableSurface#intersectAt(org.almostrealism.algebra.Ray)
	 */
	public synchronized ShadableIntersection intersectAt(Ray ray) {
		if (this.isTreeLoaded()) return super.intersectAt(ray);
		
		ray.transform(this.getTransform(true).getInverse());
		
		Triangle tr;
		
		int b = 0;
		
		i: for (int i = 0; i < inter.length; i++) {
			if (this.ignore[i]) continue i;
			
			if (this.tcache[i] != null) {
				tr = this.tcache[i];
				this.inter[i] = tr.intersectAt(ray);
				continue i;
			} else {
				tr = this.getTriangle(i);
				this.inter[i] = tr.intersectAt(ray);
				// if (inter[i] == null) continue i;
			}
			
			Vector trv = tr.getVertices()[0].subtract(ray.getOrigin());
			double dt = tr.getNormalAt(new Vector()).dotProduct(trv);
			
			if (!this.removeBackFaces) {
				this.tcache[i] = tr;
			} else if ((!this.getShadeFront() && !this.getShadeBack()) ||
					(!this.getShadeFront() && dt < 0.0) ||
					(!this.getShadeBack() && dt > 0.0) ||
					(dt == 0.0)) {
				b++;
				this.ignore[i] = true;
			} else {
				this.tcache[i] = tr;
			}
		}
		
		double closestIntersection = -1.0;
		int closestIntersectionIndex = -1;
		
		i: for (int i = 0; i < this.inter.length; i++) {
			if (this.inter[i] == null) continue i;
			
			double intersect[] = this.inter[i].getIntersections();
			
			for (int j = 0; j < intersect.length; j++) {
				if (intersect[j] >= Intersection.e) {
					if (closestIntersectionIndex == -1 || intersect[j] < closestIntersection) {
						closestIntersection = intersect[j];
						closestIntersectionIndex = i;
					}
				}
			}
		}
		
		if (b > 0) System.out.println("Mesh: Removed " + b + " back faces.");
		
		if (closestIntersectionIndex < 0)
			return null;
		else
			return this.inter[closestIntersectionIndex];
	}
	
	/**
	 * Does nothing.
	 */
	public void setSurfaces(Triangle surfaces[]) { }
	
//	/**
//	 * Adds the specified Surface object to the list of triangles stored by this Mesh object.
//	 * This method should not be used to add triangles that will share verticies, because
//	 * that can be done more efficiently with the addVector and addTriangle methods.
//	 */
	public void addSurface(Triangle s) {
//		this.triangles.add(s);
//		s.setParent(this);
	}
	
//	/**
//	 * Removes the Triangle object stored by this Mesh object at the specified index.
//	 */
	public void removeSurface(int index) {
//		Triangle t = (Triangle)this.triangles.get(index);
//		
//		Vector n = t.getNormalAt(new Vector());
//		Vector v[] = t.getVertices();
//		
//		
//		if (v[0] instanceof Vertex) {
//			((Vertex)v[0]).removeNormal(n);
//			((Vertex)v[1]).removeNormal(n);
//			((Vertex)v[2]).removeNormal(n);
//		}
//		
//		this.triangles.remove(index);
	}
	
//	/**
//	 * @return  An array of Surface objects containing the Triangle objects stored by this Mesh object.
//	 */
	public ShadableSurface[] getSurfaces() {
		return null;
//		Triangle t[] = this.getTriangles();
//		
//		return t;
//		
//		// return (Surface[])this.triangles.toArray(new Surface[0]);
	}
	
	/**
	 * @return  The Triangle object stored by this Mesh object with the specified index.
	 */
	public ShadableSurface getSurface(int index) { return this.getTriangle(index, false); }
	
	public Object encode() {
		if (this.file != null) {
			return this.file;
		} else {
			return this;
		}
	}

	@Override
	public int size() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public boolean isEmpty() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean contains(Object o) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public Object[] toArray() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <T> T[] toArray(T[] a) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean add(Triangle e) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean remove(Object o) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean addAll(Collection<? extends Triangle> c) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean addAll(int index, Collection<? extends Triangle> c) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void clear() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Triangle get(int index) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Triangle set(int index, Triangle element) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void add(int index, Triangle element) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Triangle remove(int index) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int indexOf(Object o) {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int lastIndexOf(Object o) {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public ListIterator<Triangle> listIterator() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ListIterator<Triangle> listIterator(int index) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<Triangle> subList(int fromIndex, int toIndex) {
		// TODO Auto-generated method stub
		return null;
	}
}
