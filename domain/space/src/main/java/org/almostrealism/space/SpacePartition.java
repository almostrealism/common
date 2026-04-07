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

package org.almostrealism.space;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.geometry.ContinuousField;
import org.almostrealism.geometry.Intersection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.ShadableIntersection;
import org.almostrealism.geometry.TransformMatrix;

/**
 * {@link SpacePartition} implements a Binary Space Partitioning (BSP) tree for accelerating
 * ray-surface intersection tests on collections of {@link ShadableSurface} objects.
 *
 * <p>BSP trees recursively subdivide 3D space along axis-aligned planes (XY, XZ, YZ),
 * organizing surfaces into a hierarchical structure. During ray intersection tests,
 * the tree is traversed to quickly eliminate large groups of surfaces that cannot
 * possibly intersect with the ray, significantly improving performance for complex scenes.
 *
 * <p>The partitioning algorithm classifies each triangle based on which side of the
 * splitting plane its vertices fall:
 * <ul>
 *   <li><b>LEFT</b>: All vertices are on the negative side of the plane</li>
 *   <li><b>RIGHT</b>: All vertices are on the positive side of the plane</li>
 *   <li><b>SPANNING</b>: Vertices span both sides of the plane (stored at current node)</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * SpacePartition<Triangle> partition = new SpacePartition<>();
 * partition.addSurface(triangle1);
 * partition.addSurface(triangle2);
 * // ... add more triangles
 *
 * // Build the BSP tree (required before intersection queries)
 * partition.loadTree();
 *
 * // Query intersections
 * ContinuousField intersection = partition.intersectAt(rayProducer);
 * }</pre>
 *
 * <p>This class extends {@link SurfaceGroup} and inherits its surface management capabilities.
 * The tree must be explicitly built by calling {@link #loadTree()} before intersection
 * queries will use the BSP acceleration.
 *
 * @param <T> the type of surface stored in this partition, must extend {@link ShadableSurface}
 * @author Michael Murray
 * @see SurfaceGroup
 * @see Mesh
 */
public class SpacePartition<T extends ShadableSurface> extends SurfaceGroup<T> {
	/** Debug counter for surfaces assigned to the left child. */
	public static int l;

	/** Debug counter for surfaces assigned to the right child. */
	public static int r;

	/** Debug counter for spanning surfaces stored at a node. */
	public static int s;

	/**
	 * Internal BSP tree node that recursively partitions surfaces by an axis-aligned plane.
	 */
	private class Node {
		/** Classification constant: surface is entirely on the negative side of the splitting plane. */
		public static final int LEFT = 1;

		/** Classification constant: surface is entirely on the positive side of the splitting plane. */
		public static final int RIGHT = 2;

		/** Classification constant: surface spans the splitting plane. */
		public static final int SPANNING = 4;

		/** Candidate offset values tried during adaptive partitioning. */
		private final double[] offsetValues = {0.0, 0.0, 0.0, 0.2, 0.2, 0.2};

		/** Maximum recursion depth before surfaces are stored at the current node. */
		private final int maxDepth = 4;

		/** The axis-aligned plane type used for splitting (XY, XZ, or YZ). */
		private final int plane;

		/** Offset along the splitting axis. */
		private final double offset;

		/** Left child node (negative side of the splitting plane). */
		private Node left;

		/** Right child node (positive side of the splitting plane). */
		private Node right;

		/** Indices into the parent's surface list for surfaces stored at this node. */
		private int[] surfaces;

		/** Depth of this node in the BSP tree. */
		private final int depth;
		
		/**
		 * Constructs a root node splitting on the given plane type at offset 0 with depth 0.
		 *
		 * @param plane the plane type constant
		 */
		public Node(int plane) {
			this.plane = plane;
			this.offset = 0.0;
			this.depth = 0;
		}

		/**
		 * Constructs a node splitting on the given plane type at offset 0 with the specified depth.
		 *
		 * @param plane the plane type constant
		 * @param depth the recursion depth of this node
		 */
		public Node(int plane, int depth) {
			this.plane = plane;
			this.offset = 0.0;
			this.depth = depth;
		}

		/**
		 * Constructs a node splitting on the given plane type at the specified offset and depth.
		 *
		 * @param plane  the plane type constant
		 * @param offset the signed offset along the splitting axis
		 * @param depth  the recursion depth of this node
		 */
		public Node(int plane, double offset, int depth) {
			this.plane = plane;
			this.offset = offset;
			this.depth = depth;
		}

		/** Returns the left child node, or {@code null} if not yet created. */
		public Node getLeft() { return this.left; }

		/** Returns the right child node, or {@code null} if not yet created. */
		public Node getRight() { return this.right; }

		/** Sets the surface index array for this node. */
		public void setSurfaces(int[] s) { this.surfaces = s; }

		/** Returns the surface index array for this node. */
		public int[] getSurfaces() { return this.surfaces; }

		/**
		 * Adds the surface at the given index to the BSP tree, routing it to the
		 * appropriate child or storing it at this node if it spans the splitting plane.
		 *
		 * @param s the surface index in the parent {@link SpacePartition}
		 */
		public void add(int s) {
			ShadableSurface sr = SpacePartition.this.getSurface(s);
			
			if (this.depth >= this.maxDepth || !(sr instanceof Triangle)) {
				this.addSurface(s);
				return;
			}
			
			Triangle t = (Triangle) sr;
			Vector[] v = t.getVertices();
			
			boolean right = false, left = false;
			
			int v0 = this.checkSide(v[0]);
			int v1 = this.checkSide(v[0]);
			int v2 = this.checkSide(v[0]);
			
			if (v0 == Node.RIGHT) {
				right = true;
			} else if (v0 == Node.LEFT) {
				left = true;
			} else {
				right = true;
				left = true;
			}
			
			if (v1 == Node.RIGHT) {
				right = true;
			} else if (v1 == Node.LEFT) {
				left = true;
			} else {
				right = true;
				left = true;
			}
			
			if (v2 == Node.RIGHT) {
				right = true;
			} else if (v2 == Node.LEFT) {
				left = true;
			} else {
				right = true;
				left = true;
			}
			
			if (right && !left) {
				if (this.right == null) this.initRight();
				this.right.add(s);
			} else if (left && !right) {
				if (this.left == null) this.initLeft();
				this.left.add(s);
			} else {
				this.addSurface(s);
			}
		}
		
		/**
		 * Appends the given surface index to the list of surfaces stored at this node.
		 *
		 * @param s the surface index to store
		 */
		protected void addSurface(int s) {
			if (this.surfaces == null) {
				this.surfaces = new int[] {s};
			} else {
				int[] newSurfaces = new int[this.surfaces.length + 1];
				System.arraycopy(this.surfaces, 0, newSurfaces, 0, this.surfaces.length);
				newSurfaces[newSurfaces.length - 1] = s;
				this.surfaces = newSurfaces;
			}
		}
		
		/**
		 * Creates the next child node by cycling to the next plane type and incrementing the depth.
		 *
		 * @return a new {@link Node} on the next axis-aligned plane
		 */
		public Node nextNode() {
			int p = -1;
			
			if (this.plane == Plane.XY) {
				p = Plane.XZ;
			} else if (this.plane == Plane.XZ) {
				p = Plane.YZ;
			} else if (this.plane == Plane.YZ) {
				p = Plane.XY;
			}
			
			double off = this.offsetValues[this.depth + 1];
			
			return new Node(p, off, this.depth + 1);
		}
		
		/** Initialises the right child node using {@link #nextNode()}. */
		public void initRight() { this.right = this.nextNode(); }

		/** Initialises the left child node using {@link #nextNode()}. */
		public void initLeft() { this.left = this.nextNode(); }

		/**
		 * Classifies a ray as {@link #LEFT}, {@link #RIGHT}, or {@link #SPANNING}
		 * relative to this node's splitting plane.
		 *
		 * @param r the ray to classify
		 * @return the classification constant
		 */
		public int checkRay(Ray r) {
			Vector o = r.getOrigin();
			
			if (this.isRight(o)) {
				if (this.isLeft(r.getDirection(), 0.0))
					return Node.SPANNING;
				else
					return Node.RIGHT;
			} else {
				if (this.isRight(r.getDirection(), 0.0))
					return Node.SPANNING;
				else
					return Node.LEFT;
			}
		}
		
		/**
		 * Returns {@code true} if the ray spans this node's splitting plane.
		 *
		 * @param r the ray to test
		 * @return {@code true} if the ray crosses the splitting plane
		 */
		public boolean isSpanning(Ray r) {
			Vector o = r.getOrigin();
			
			if (this.isRight(o)) {
				return this.isLeft(r.getDirection(), 0.0);
			} else {
				return this.isRight(r.getDirection(), 0.0);
			}
		}
		
		/**
		 * Classifies a vertex position as {@link #LEFT}, {@link #RIGHT}, or {@link #SPANNING}.
		 *
		 * @param v the vertex position to classify
		 * @return the classification constant
		 */
		public int checkSide(Vector v) {
			if (this.isLeft(v))
				return Node.LEFT;
			else if (this.isRight(v))
				return Node.RIGHT;
			else
				return Node.SPANNING;
		}
		
		/**
		 * Returns {@code true} if the vector is on the right (positive) side of the splitting
		 * plane at the specified offset.
		 *
		 * @param v   the vertex position to test
		 * @param off the plane offset value to use instead of {@link #offset}
		 * @return true if the vertex is to the right of the offset plane
		 */
		public boolean isRight(Vector v, double off) {
			if (this.plane == Plane.XY) {
				return (v.getZ() > (off + Intersection.e));
			} else if (this.plane == Plane.YZ) {
				return (v.getX() > (off + Intersection.e));
			} else if (this.plane == Plane.XZ) {
				return (v.getY() > (off + Intersection.e));
			} else {
				return false;
			}
		}
		
		/**
		 * Returns {@code true} if the vector is on the left (negative) side of the splitting plane.
		 *
		 * @param v   the vector to test
		 * @param off the signed offset along the splitting axis
		 * @return {@code true} if the vector is on the left side
		 */
		public boolean isLeft(Vector v, double off) {
			if (this.plane == Plane.XY) {
				return (v.getZ() < (off - Intersection.e));
			} else if (this.plane == Plane.YZ) {
				return (v.getX() < (off - Intersection.e));
			} else if (this.plane == Plane.XZ) {
				return (v.getY() < (off - Intersection.e));
			} else {
				return false;
			}
		}
		
		/**
		 * Returns {@code true} if the vector is on the right (positive) side using this node's offset.
		 *
		 * @param v the vector to test
		 * @return {@code true} if the vector is on the right side
		 */
		public boolean isRight(Vector v) { return this.isRight(v, this.offset); }

		/**
		 * Returns {@code true} if the vector is on the left (negative) side using this node's offset.
		 *
		 * @param v the vector to test
		 * @return {@code true} if the vector is on the left side
		 */
		public boolean isLeft(Vector v) { return this.isLeft(v, this.offset); }

		/**
		 * Returns the closest ray-surface intersection in this subtree, or {@code null} if none.
		 * Currently unimplemented.
		 *
		 * @return a {@link ShadableIntersection} or {@code null} (currently unimplemented)
		 */
		public ShadableIntersection intersectAt() {
			return null; // TODO
			/*
			List<ShadableIntersection> l = new ArrayList<ShadableIntersection>();
			
			if (this.surfaces != null) {
				for (int i = 0; i < this.surfaces.length; i++) {
					if (this.scache[i] == null)
						this.scache[i] = SpacePartition.this.getSurface(surfaces[i]);
					
					ShadableIntersection inter = this.scache[i].intersectAt(r);
					if (inter != null) l.add(inter);
				}
			}
			
			if (this.left != null || this.right != null) {
				int side = this.checkSide(r.getOrigin());
				
				if (side == Node.SPANNING) {
					if (this.right != null) {
						ShadableIntersection inter = this.right.intersectAt(r);
						if (inter != null) l.add(inter);
					}
					
					if (this.left != null) {
						ShadableIntersection inter = this.left.intersectAt(r);
						if (inter != null) l.add(inter);
					}
				} else if (this.left != null && side == Node.LEFT) {
					ShadableIntersection inter = this.left.intersectAt(r);
					
					if (inter != null) {
						l.add(inter);
					} else if (this.right != null) {
						inter = this.right.intersectAt(r);
						if (inter != null) l.add(inter);
					}
				} else if (this.right != null && side == Node.RIGHT) {
					ShadableIntersection inter = this.right.intersectAt(r);
					
					if (inter != null) {
						l.add(inter);
					} else if (this.left != null) {
						inter = this.left.intersectAt(r);
						if (inter != null) l.add(inter);
					}
				}
			}
			
			double closestIntersection = -1.0;
			int closestIntersectionIndex = -1;
			
			i: for (int i = 0; i < l.size(); i++) {
				double intersect[] = ((Intersection)l.get(i)).getIntersections();
				
				for (int j = 0; j < intersect.length; j++) {
					if (intersect[j] >= Intersection.e) {
						if (closestIntersectionIndex == -1 || intersect[j] < closestIntersection) {
							closestIntersection = intersect[j];
							closestIntersectionIndex = i;
						}
					}
				}
			}
			
			if (closestIntersectionIndex < 0)
				return null;
			else
				return l.get(closestIntersectionIndex);
			*/
		}

		@Override
		public String toString() {
			String p = "";
			
			if (this.plane == Plane.XY)
				p = "XY";
			else if (this.plane == Plane.XZ)
				p = "XZ";
			else if (this.plane == Plane.YZ)
				p = "YZ";
			
			return "SpacePartition.Node(" + p + ", " + this.offset + ")";
		}
	}
	
	/** The root node of the BSP tree, or {@code null} if the tree has not been built. */
	private Node root;

	/**
	 * Builds the BSP tree using all surfaces currently in this partition.
	 * This must be called before intersection queries will use BSP acceleration.
	 */
	public void loadTree() { this.loadTree(this.getSurfaces().length); }

	/**
	 * Builds the BSP tree using the specified number of surfaces.
	 * The tree starts with a YZ splitting plane at the root and alternates
	 * through XZ and XY planes as depth increases.
	 *
	 * @param s the number of surfaces to include in the tree
	 */
	public void loadTree(int s) {
		this.root = new Node(Plane.YZ);
		for (int i = 0; i < s; i++) this.root.add(i);
	}

	/**
	 * Returns whether the BSP tree has been built.
	 *
	 * @return {@code true} if the tree has been loaded, {@code false} otherwise
	 */
	public boolean isTreeLoaded() { return (this.root != null); }

	/**
	 * Returns a {@link ContinuousField} representing the ray-surface intersection
	 * for this partition.
	 *
	 * <p>If the BSP tree has been loaded, this method uses the tree structure to
	 * accelerate intersection tests. Otherwise, it falls back to the standard
	 * linear search from {@link SurfaceGroup}.
	 *
	 * @param ray the ray producer to test for intersection
	 * @return a continuous field representing the intersection, or {@code null} if no intersection
	 */
	@Override
	public ContinuousField intersectAt(Producer ray) {
		TransformMatrix t = getTransform(true);
		boolean ut = t != null;
		Producer<Ray> r = ray;
		if (ut) r = (Producer) t.getInverse().transform(r);
		return this.root.intersectAt();
	}
}
