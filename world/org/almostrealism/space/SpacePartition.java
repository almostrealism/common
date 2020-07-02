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

import org.almostrealism.algebra.*;
import org.almostrealism.geometry.Ray;
import org.almostrealism.graph.Triangle;
import org.almostrealism.util.Producer;

public class SpacePartition<T extends ShadableSurface> extends SurfaceGroup<T> {
	public static int l, r, s;
	
	private class Node {
		public static final int LEFT = 1, RIGHT = 2, SPANNING = 4;
		private double offsetValues[] = {0.0, 0.0, 0.0, 0.2, 0.2, 0.2};
		private int maxDepth = 4;
		private double maxOffset = 0.2;
		
		private int plane;
		private double offset;
		private Node left, right;
		private int surfaces[];
		private ShadableSurface scache[];
		private int depth;
		private double orient;
		
		public Node(int plane) {
			this.plane = plane;
			this.offset = 0.0;
			this.depth = 0;
		}
		
		public Node(int plane, int depth) {
			this.plane = plane;
			this.offset = 0.0;
			this.depth = depth;
		}
		
		public Node(int plane, double offset, int depth) {
			this.plane = plane;
			this.offset = offset;
			this.depth = depth;
		}
		
		public void setOrientation(double orient) { this.orient = orient; }
		
		public Node getLeft() { return this.left; }
		public Node getRight() { return this.right; }
		public void setSurfaces(int s[]) { this.surfaces = s; }
		public int[] getSurfaces() { return this.surfaces; }
		
		public void add(int s) {
			ShadableSurface sr = SpacePartition.this.getSurface(s);
			
			if (this.depth >= this.maxDepth || sr instanceof Triangle == false) {
				this.addSurface(s);
				return;
			}
			
			Triangle t = (Triangle) sr;
			Vector v[] = t.getVertices();
			
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
		
		protected void addSurface(int s) {
			if (this.surfaces == null) {
				this.surfaces = new int[] {s};
			} else {
				int newSurfaces[] = new int[this.surfaces.length + 1];
				for (int i = 0; i < this.surfaces.length; i++) newSurfaces[i] = this.surfaces[i];
				newSurfaces[newSurfaces.length - 1] = s;
				this.surfaces = newSurfaces;
			}
			
			this.scache = new ShadableSurface[this.surfaces.length];
		}
		
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
		
		public void initRight() { this.right = this.nextNode(); }
		
		public void initLeft() { this.left = this.nextNode(); }
		
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
		
		public boolean isSpanning(Ray r) {
			Vector o = r.getOrigin();
			
			if (this.isRight(o)) {
				return this.isLeft(r.getDirection(), 0.0);
			} else {
				return this.isRight(r.getDirection(), 0.0);
			}
		}
		
		public int checkSide(Vector v) {
			if (this.isLeft(v))
				return Node.LEFT;
			else if (this.isRight(v))
				return Node.RIGHT;
			else
				return Node.SPANNING;
		}
		
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
		
		public boolean isRight(Vector v) { return this.isRight(v, this.offset); }
		public boolean isLeft(Vector v) { return this.isLeft(v, this.offset); }
		
		public ShadableIntersection intersectAt(Producer r) {
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
	
	private Node root;
	
	public void loadTree() { this.loadTree(this.getSurfaces().length); }
	
	public void loadTree(int s) {
		this.root = new Node(Plane.YZ);
		for (int i = 0; i < s; i++) this.root.add(i);
	}
	
	public boolean isTreeLoaded() { return (this.root != null); }

	@Override
	public ContinuousField intersectAt(Producer r) {
		TransformMatrix t = getTransform(true);
		boolean ut = t != null;
		if (ut) r = new RayMatrixTransform(t.getInverse(), r);
		return this.root.intersectAt(r);
	}
}
