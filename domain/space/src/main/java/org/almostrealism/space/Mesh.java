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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Graph;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.geometry.BoundingSolid;
import org.almostrealism.geometry.ContinuousField;
import org.almostrealism.geometry.DimensionAwareKernel;
import org.almostrealism.geometry.Positioned;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.ShadableIntersection;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.graph.KdTree;
import org.almostrealism.graph.mesh.TriangleFeatures;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

// TODO  Add bounding solid to make intersection calc faster.

/**
 * A {@link Mesh} object stores a set of points (Vector objects)
 * and allows triangles to be specified using those points.
 * 
 * @author  Michael Murray
 * @author  Dan Chivers
 */
public class Mesh extends SpacePartition<Triangle> implements Graph<Vector> {
	/** Default white color assigned to triangles that do not have an explicit color set. */
	private static final RGB white = new RGB(1.0, 1.0, 1.0);
	
	/**
	 * Interface for providing vertex and triangle data to a {@link Mesh}.
	 *
	 * <p>Implementations of this interface provide access to per-vertex attributes
	 * (position, color, texture coordinates) and triangle connectivity information.
	 * This abstraction allows meshes to work with various backing data stores,
	 * including packed collections optimized for hardware acceleration.
	 *
	 * @see DefaultVertexData
	 */
	public interface VertexData {
		/**
		 * Returns the red color component for the vertex at the specified index.
		 * @param index the vertex index
		 * @return the red component (0.0-1.0)
		 */
		double getRed(int index);

		/**
		 * Returns the green color component for the vertex at the specified index.
		 * @param index the vertex index
		 * @return the green component (0.0-1.0)
		 */
		double getGreen(int index);

		/**
		 * Returns the blue color component for the vertex at the specified index.
		 * @param index the vertex index
		 * @return the blue component (0.0-1.0)
		 */
		double getBlue(int index);

		/**
		 * Returns the color for the vertex at the specified index.
		 * @param index the vertex index
		 * @return the vertex color as an RGB object
		 */
		RGB getColor(int index);

		/**
		 * Returns the X coordinate for the vertex at the specified index.
		 * @param index the vertex index
		 * @return the X coordinate
		 */
		double getX(int index);

		/**
		 * Returns the Y coordinate for the vertex at the specified index.
		 * @param index the vertex index
		 * @return the Y coordinate
		 */
		double getY(int index);

		/**
		 * Returns the Z coordinate for the vertex at the specified index.
		 * @param index the vertex index
		 * @return the Z coordinate
		 */
		double getZ(int index);

		/**
		 * Returns the position vector for the vertex at the specified index.
		 * @param index the vertex index
		 * @return the vertex position as a Vector
		 */
		Vector getPosition(int index);

		/**
		 * Returns the U texture coordinate for the vertex at the specified index.
		 * @param index the vertex index
		 * @return the U texture coordinate
		 */
		double getTextureU(int index);

		/**
		 * Returns the V texture coordinate for the vertex at the specified index.
		 * @param index the vertex index
		 * @return the V texture coordinate
		 */
		double getTextureV(int index);

		/**
		 * Returns the texture coordinates for the vertex at the specified index.
		 * @param index the vertex index
		 * @return the texture coordinates as a Pair (u, v)
		 */
		Pair getTexturePosition(int index);

		/**
		 * Returns the vertex indices that define the triangle at the specified index.
		 * @param index the triangle index
		 * @return an array of three vertex indices
		 */
		int[] getTriangle(int index);

		/**
		 * Returns the total number of triangles in the mesh.
		 * @return the triangle count
		 */
		int getTriangleCount();

		/**
		 * Returns the total number of vertices in the mesh.
		 * @return the vertex count
		 */
		int getVertexCount();

		/**
		 * Returns the mesh vertex data as a packed collection suitable for hardware-accelerated processing.
		 * @return packed collection of vertex position data organized by triangle
		 */
		PackedCollection getMeshPointData();
	}
	
  /** The list of vertex positions (as {@link Vertex} objects) for this mesh. */
  private List points;

  /** The list of triangle connectivity data (as {@code int[3]} index arrays). */
  private List triangles;

  /** Cache of preconstructed {@link Triangle} objects indexed by triangle number. */
  private Triangle[] tcache;

  /** Flags indicating which triangles should be skipped during intersection tests. */
  private boolean[] ignore;

  /** When true, vertex normals are averaged across shared vertices for smooth shading. */
  private boolean smooth;

  /** When true, triangles whose normals face away from the ray origin are culled. */
  private boolean removeBackFaces;

  /** When true, vertex colors are interpolated across triangle surfaces. */
  private boolean intcolor;
  
  /** Source for loading mesh data from external files. */
  private MeshSource source;

  /** External vertex data provider, used as an alternative to internal storage. */
  private VertexData vertexData;

  /** KdTree for efficient spatial queries on vertices. */
  private KdTree<Positioned> spatialVertexTree;

  	/**
  	 * Constructs an empty {@link Mesh} with no vertices or triangles.
  	 */
  	public Mesh() {
  		this.points = new ArrayList();
  		this.triangles = new ArrayList();
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
  	public Mesh(Vector[] points, int[][] triangles) {
  		this.points = new ArrayList();
  		this.triangles = new ArrayList();
  		this.clearIgnoreCache();
  		this.clearTriangleCache();
  		
  		for (int i = 0; i < points.length; i++) this.addVector(points[i]);
  		
  		for (int i = 0; i < triangles.length; i++)
  			this.addTriangle(triangles[i][0], triangles[i][1], triangles[i][2]);
  	}
  	
	/**
	 * Constructs a {@link Mesh} using the specified {@link VertexData} provider.
	 *
	 * <p>This constructor allows mesh data to be provided by an external implementation
	 * of {@link VertexData}, which is useful for integrating with different data sources
	 * or optimized storage formats.
	 *
	 * @param data the vertex data provider
	 */
  	public Mesh(VertexData data) {
  		this.vertexData = data;

	  	this.clearIgnoreCache();
	  	this.clearTriangleCache();
  	}

	/**
	 * Sets the mesh source used for loading mesh data from external files.
	 *
	 * @param f the mesh source to use
	 */
  	public void setMeshSource(MeshSource f) { this.source = f; }
  	
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
  	 * represented by this Mesh object and clears the cache.
  	 * 
  	 * @param p1  Index of first point.
  	 * @param p2  Index of second point.
  	 * @param p3  Index of third point.
  	 * @throws IllegalArgumentException if any of the indicies specified are not valid.
  	 * @return  The unique index of the triangle added or -1 if it is not added (if any point indicies are the same).
  	 */
  	public int addTriangle(int p1, int p2, int p3) {
  		return addTriangle(p1, p2, p3, true);
  	}

	/**
	 * Adds the triangle described by the specified points to the mesh
	 * represented by this Mesh object. Allows manual control over whether the
	 * cache will be cleared after triangle addition.
	 * Cache clearance is expensive when a large mesh is concerned, so you may want
	 * to invalidate the cache only after adding multiple triangles.
	 *
	 * @param p1  Index of first point.
	 * @param p2  Index of second point.
	 * @param p3  Index of third point.
	 * @param clearcache Whether to clear the cache after triangle addition.
	 * @throws IllegalArgumentException if any of the indices specified are not valid.
	 * @return  The unique index of the triangle added or -1 if it is not added (if any point indicies are the same).
	 */
  	public int addTriangle(int p1, int p2, int p3, boolean clearcache) {
		if (p1 == p2 || p2 == p3 || p3 == p1) return -1;

		Vertex v1 = (Vertex) this.points.get(p1);
		Vertex v2 = (Vertex) this.points.get(p2);
		Vertex v3 = (Vertex) this.points.get(p3);

		Triangle t = new Triangle(v1, v2, v3);

		Producer<PackedCollection> tnp = t.getNormalAt(Vector.blank());
		Vector tn = new Vector(tnp.get().evaluate(), 0);

		if (this.triangles.add(new int[] {p1, p2, p3})) {
			v1.addNormal(tn);
			v2.addNormal(tn);
			v3.addNormal(tn);

			if (clearcache) {
				this.clearIgnoreCache();
				this.clearTriangleCache();
			}

			return (this.triangles.size() - 1);
		} else {
			return -1;
		}
	}
	
	/**
	 * Clears the ignore cache, resetting the array that tracks which triangles
	 * should be skipped during intersection tests (e.g., for downsampling or back-face culling).
	 */
	public void clearIgnoreCache() {
		if (this.vertexData == null)
			this.ignore = new boolean[this.triangles.size()];
		else
			this.ignore = new boolean[this.vertexData.getTriangleCount()];
	}

	/**
	 * Instructs the Mesh to build and return spatial representation of the current vertex
	 * data. Will clear any existing built representation. Use getSpatialVertexTree() to
	 * retrieve a previously built tree.
	 *
	 * @return The newly built tree.
	 */
	public KdTree<Positioned> buildSpatialVertexTree() {
		throw new UnsupportedOperationException();
		// TODO
//		spatialVertexTree = new KdTree.SqrEuclid<>(3, points.size());
//		for (Object vector : points) {
//			Vertex v = (Vertex) vector;
//			spatialVertexTree.addPoint(v.getData(), v);
//		}
//		return spatialVertexTree;
	}

	/**
	 * Returns the currently built spatial representation of the mesh vertices. The
	 * representation is built on demand via {@link #buildSpatialVertexTree()}.
	 * Returns null if no representation has yet been built.
	 *
	 * @return The previously built tree.
	 */
	public KdTree<Positioned> getSpatialVertexTree() {
		return spatialVertexTree;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>This override first loads all triangles into the cache, then builds
	 * the BSP tree for accelerated ray intersection.
	 */
	@Override
	public void loadTree() {
		this.loadTriangles();
		super.loadTree(this.tcache.length);
	}

	/**
	 * Loads all triangles into the triangle cache for faster repeated access.
	 * This is called automatically by {@link #loadTree()}.
	 */
	public void loadTriangles() {
		this.clearTriangleCache();

		for (int i = 0; i < tcache.length; i++)
			this.tcache[i] = this.getTriangle(i);
	}

	/**
	 * Clears the triangle cache, resetting it to hold the current number of triangles.
	 * The cache will be repopulated on demand as triangles are accessed.
	 */
	public void clearTriangleCache() {
		if (this.vertexData == null)
			this.tcache = new Triangle[this.triangles.size()];
		else
			this.tcache = new Triangle[this.vertexData.getTriangleCount()];
	}
  	
  	/**
  	 * Removes all points stored by this Mesh and adds those stored in the specified array.
  	 * 
  	 * @param p  Array of Vertex objects to add.
  	 */
  	public void setVectors(Vertex[] p) {
  		this.points.clear();
		Collections.addAll(this.points, p);
  	}
  	
  	/**
  	 * Returns an array of {@link Mesh.Vertex} objects stored by this Mesh object.
  	 *
  	 * @return  An array of Mesh.Vertex objects stored by this Mesh object.
  	 */
  	public Vertex[] getVectors() { return this.getVectors(this.source == null); }
  	
	/**
	 * Returns the vertices of this mesh, or null if {@code b} is false.
	 *
	 * @param b true to return the vertex array, false to return null
	 * @return the array of vertices, or null
	 */
  	public Vertex[] getVectors(boolean b) {
		if (this.points == null) return null;

		if (b)
			return (Vertex[]) this.points.toArray(new Vertex[0]);
		else
			return null;
	}

	/** {@inheritDoc} */
	@Override
	public Collection<Vector> neighbors(Vector node) {
		throw new UnsupportedOperationException();
	}

	/** {@inheritDoc} */
	@Override
	public int countNodes() {
		return points.size();
	}

	/**
	 * Returns an iterator over the vertices of this mesh.
	 *
	 * @return an iterator of vertex objects
	 */
	public Iterator iterateVectors() { return this.points.iterator(); }

	/**
	 * Replaces the triangle connectivity data of this mesh with the specified array.
	 * Each entry is an array of three vertex indices defining one triangle.
	 *
	 * @param data the triangle connectivity data (array of {@code int[3]} index triplets)
	 */
  	public void setTriangleData(int[][] data) {
  		this.triangles.clear();
  		for (int i = 0; i < data.length; i++) this.triangles.add(data[i]);
  		
  		this.clearIgnoreCache();
  		this.clearTriangleCache();
  	}

	/**
	 * Returns the mesh data in a format optimized for hardware-accelerated ray intersection.
	 *
	 * <p>This method converts the mesh triangles into a {@link MeshData} structure that
	 * stores precomputed triangle data suitable for GPU/hardware processing.
	 *
	 * @return a {@link MeshData} containing all triangle data
	 * @throws RuntimeException if vertex data is not available
	 */
  	public MeshData getMeshData() {
		MeshData tdata = new MeshData(tcache.length);
		PackedCollection points = getMeshPointData();
		TriangleFeatures tf = TriangleFeatures.getInstance();
		tf.triangle(tf.c(tf.p(points))).get().into(tdata.traverse(1)).evaluate();
  		return tdata;
	}

	/**
	 * Returns the mesh point data as a packed collection for hardware-accelerated processing.
	 *
	 * @return packed collection of vertex positions organized by triangle
	 * @throws RuntimeException if no vertex data provider is configured
	 */
	public PackedCollection getMeshPointData() {
  		if (vertexData == null) {
  			throw new RuntimeException("Not implemented");
		}

  		return vertexData.getMeshPointData();
	}

	/**
	 * Returns the triangle connectivity data for this mesh.
	 *
	 * @return the triangle index array, or null if a mesh source is set
	 */
  	public int[][] getTriangleData() {
  		return this.getTriangleData(this.source == null);
  	}

	/**
	 * Returns the triangle connectivity data for this mesh, or null if {@code b} is false.
	 *
	 * @param b true to return the data, false to return null
	 * @return the triangle index array, or null
	 */
  	public int[][] getTriangleData(boolean b) {
  		if (b)
  			return (int[][]) this.triangles.toArray(new int[0][0]);
  		else
  			return null;
  	}
  	
	/**
	 * Returns an iterable over all {@link Triangle} objects in this mesh.
	 * If a {@link VertexData} provider is configured, triangles are constructed on demand
	 * from the provider; otherwise the internal triangle list is used.
	 *
	 * @return an iterable of triangles
	 */
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
						int[] t = vertexData.getTriangle(i);
						i++;
						return new Triangle(t[0], t[1], t[2],
								Mesh.white.clone(), vertexData);
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
	
	/**
	 * Returns an array of Triangle objects stored by this {@link Mesh} object.
	 *
	 * @return  An array of Triangle objects stored by this {@link Mesh} object.
	 */
	public Triangle[] getTriangles() {
		Triangle[] t = new Triangle[this.triangles.size()];
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
	public Triangle getTriangle(int face) { return this.getTriangle(face, true); }
	
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
			int[] v = (int[]) this.triangles.get(face);
			
			Vertex v1 = (Vertex) this.points.get(v[0]);
			Vertex v2 = (Vertex) this.points.get(v[1]);
			Vertex v3 = (Vertex) this.points.get(v[2]);
			
			t = new Triangle(v1, v2, v3, Mesh.white.clone());
		} else {
			int[] d = this.vertexData.getTriangle(face);
			t = new Triangle(d[0], d[1], d[2], Mesh.white.clone(), this.vertexData);
		}
		
		t.setParent(this);
		t.setColor(Mesh.white.clone());
		t.setSmooth(this.smooth);
		t.setUseTransform(false);
		t.setInterpolateVertexColor(this.intcolor);
		
		if (cache) this.tcache[face] = t;
		
		return t;
	}
  	
  	/**
  	 * Returns the index of the specified Vector in this mesh's point list, or -1 if not found.
  	 *
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
	 * Returns {@code null}: normal computation at a point is not implemented for meshes.
	 *
	 * @return  null.
	 */
	public Evaluable<Vector> getNormalAt(Vector point) { return null; }
	
	/**
	 * Sets the smooth flag for this Mesh object. If the flag is true,
	 * the surface normals will be interpolated to produce a smoothing effect.
	 * 
	 * @param smooth  Boolean flag to use.
	 */
	public void setSmooth(boolean smooth) { this.smooth = smooth; }
	
	/**
	 * Returns {@code true} if this Mesh object will be smoothed when rendered.
	 *
	 * @return  True if this Mesh object will be smoothed when rendered, false otherwise.
	 */
	public boolean getSmooth() { return this.smooth; }

	/**
	 * Sets whether back faces should be removed (ignored) during rendering.
	 *
	 * @param b  True if back faces should be ignored, false if they should be included.
	 */
	public void setRemoveBackFaces(boolean b) { this.removeBackFaces = b; }

	/**
	 * Returns {@code true} if back faces will be ignored during rendering.
	 *
	 * @return  True if back faces will be ignored, false if they will be included.
	 */
	public boolean getRemoveBackFaces() { return this.removeBackFaces; }

	/**
	 * Sets whether vertex color interpolation is enabled.
	 *
	 * @param c  If set to true, color will be interpolated based on vertex colors.
	 */
	public void setInterpolateColor(boolean c) { this.intcolor = c; }

	/**
	 * Returns {@code true} if color will be interpolated based on vertex colors.
	 *
	 * @return  True if color will be interpolated based on vertex colors, false otherwise.
	 */
	public boolean getInterpolateColor() { return this.intcolor; }
	
	/**
	 * Returns the index of the specified vertex in this mesh's point list.
	 *
	 * @param v the vertex to search for
	 * @return the zero-based index, or -1 if not found
	 */
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
		int[][] t = this.getTriangleData(true);
		Vector[] v = this.getVectors(true);
		
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
				new Vector(((Triangle) this.triangles.get(face)).getNormalAt(Vector.blank()).get().evaluate(), 0).multiply(l));
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
	 * {@inheritDoc}
	 *
	 * <p>Not yet implemented for {@link Mesh}.
	 *
	 * @throws UnsupportedOperationException always
	 */
	@Override
	public BoundingSolid calculateBoundingSolid() {
		throw new UnsupportedOperationException();
		// TODO
		// return BoundingSolid.getBounds(getVectors());
	}
	
	/**
	 * @return  this.
	 */
	@Override
	public Mesh triangulate() { return this; }

	/**
	 * @see ShadableSurface#intersectAt(Producer)
	 */
	@Override
	public ContinuousField intersectAt(Producer ray) {
		if (this.isTreeLoaded()) return super.intersectAt(ray);

		TransformMatrix t = getTransform(true);
		Producer<Ray> tray = (Producer<Ray>) ray;
		if (t != null) tray = (Producer) t.getInverse().transform(tray);

		CachedMeshIntersectionKernel kernel = new CachedMeshIntersectionKernel(getMeshData(), tray);
		return new ShadableIntersection(ray,
				(Producer) () -> kernel.getClosestNormal(),
				new DimensionAwareKernel<>(kernel));
	}

	/**
	 * Updates the ignore flags for all cached triangles based on whether their normals
	 * face toward or away from the given ray origin.
	 *
	 * @param r the ray to test back-face visibility against
	 */
	private void removeBackFaces(Ray r) {
		int b = 0;

		i: for (int i = 0; i < tcache.length; i++) {
			if (!removeBackFaces) {
				ignore[i] = false;
				continue i;
			}

			Vector trv = tcache[i].getVertices()[0].subtract(r.getOrigin());
			double dt = new Vector(tcache[i].getNormalAt(Vector.blank()).get()
					.evaluate(), 0).dotProduct(trv);

			if ((!getShadeFront() && !getShadeBack()) ||
					(!getShadeFront() && dt < 0.0) ||
					(!getShadeBack() && dt > 0.0) ||
					(dt == 0.0)) {
				b++;
				ignore[i] = true;
			} else {
				ignore[i] = false;
			}
		}

		if (b > 0) System.out.println("Mesh: Removed " + b + " back faces.");
	}
	
	/**
	 * Does nothing.
	 */
	@Override
	public void setSurfaces(Triangle[] surfaces) { }
	
	/**
	 * Not supported for {@link Mesh}; use {@link #addVector} and {@link #addTriangle} instead.
	 *
	 * @param s the triangle to add (ignored)
	 * @throws RuntimeException always
	 */
	@Override
	public void addSurface(Triangle s) {
		throw new RuntimeException("Not implemented");
	}

	/**
	 * Not supported for {@link Mesh}; use the triangle connectivity data methods instead.
	 *
	 * @param index the index of the surface to remove (ignored)
	 * @throws RuntimeException always
	 */
	@Override
	public void removeSurface(int index) {
		throw new RuntimeException("Not implemented");
	}

	/**
	 * Not supported for {@link Mesh}; use {@link #getTriangles()} instead.
	 *
	 * @return never returns; always throws
	 * @throws RuntimeException always
	 */
	@Override
	public ShadableSurface[] getSurfaces() {
		throw new RuntimeException("Not implemented");
	}
	
	/**
	 * @return  The Triangle object stored by this Mesh object with the specified index.
	 */
	public ShadableSurface getSurface(int index) { return this.getTriangle(index, false); }
	
	/**
	 * Returns an object that can be used to encode and serialize this mesh.
	 * If a mesh source is set, the source is returned; otherwise this mesh itself is returned.
	 *
	 * @return the mesh source or this mesh
	 */
	public Object encode() {
		if (this.source != null) {
			return this.source;
		} else {
			return this;
		}
	}
}
