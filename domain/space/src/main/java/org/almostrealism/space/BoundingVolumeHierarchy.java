/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.space;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.BoundingSolid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A host-built Bounding Volume Hierarchy (BVH) over a set of triangles, used to
 * accelerate ray-triangle intersection by pruning triangles whose axis-aligned
 * bounding box a ray cannot reach.
 *
 * <p>The hierarchy is a binary tree of axis-aligned bounding boxes. Each leaf holds a
 * small group of triangles; each internal node bounds its two children. A ray query
 * descends the tree, testing the ray against each node's box with the slab method, and
 * returns the indices of every triangle whose own box the ray pierces. The result is
 * <em>conservative</em>: it always contains the triangle that is actually closest along
 * the ray (that triangle's box is necessarily pierced), so running the exact
 * intersection test over the returned candidates yields the same closest hit as a linear
 * scan over all triangles. Boxes are inflated by a small epsilon at construction so that
 * a grazing hit on a box face is never excluded by floating-point error.</p>
 *
 * <p>The tree is constructed once from per-triangle bounds (computed by a kernel) and is immutable.
 * Traversal runs on the host, which mirrors the existing per-ray loop in
 * {@link MeshData#evaluateIntersectionKernel}; the exact triangle math is still a compiled
 * kernel (see {@link MeshData#evaluateIntersectionAccelerated(io.almostrealism.relation.Evaluable,
 * Object[])}). A {@link MeshData} builds its own hierarchy over its packed triangle data via
 * {@link MeshData#getBoundingVolumeHierarchy()}; a fully device-resident traversal is a
 * possible future refinement.</p>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * BoundingVolumeHierarchy bvh = meshData.getBoundingVolumeHierarchy();
 * int[] candidates = bvh.candidates(ox, oy, oz, dx, dy, dz);
 * }</pre>
 *
 * @author Michael Murray
 * @see MeshData
 * @see Mesh
 */
public class BoundingVolumeHierarchy {
	/** Maximum number of triangles stored in a leaf node before it is split. */
	private static final int DEFAULT_LEAF_SIZE = 4;

	/** Absolute amount by which each triangle box is inflated to guard against grazing-hit exclusion. */
	private static final double BOX_EPSILON = 1e-6;

	/** Axis-aligned bounding box (inflated) of each triangle, indexed by triangle index. */
	private final BoundingSolid[] triangleBounds;

	/** Permutation of triangle indices, arranged so that each leaf references a contiguous range. */
	private final int[] order;

	/** Root of the tree, or {@code null} if there are no triangles. */
	private final Node root;

	/**
	 * Internal tree node bounding a contiguous range of {@link #order}.
	 */
	private static class Node {
		/** The box bounding every triangle beneath this node. */
		private final BoundingSolid bounds;

		/** The left child, or {@code null} for a leaf. */
		private Node left;

		/** The right child, or {@code null} for a leaf. */
		private Node right;

		/** Start index into {@link #order} for a leaf's triangle range. */
		private int start;

		/** Number of triangles in a leaf's range. */
		private int count;

		/** Whether this node is a leaf. */
		private boolean leaf;

		/**
		 * Constructs a node with the given bounds.
		 *
		 * @param bounds the box bounding the node's triangles
		 */
		private Node(BoundingSolid bounds) { this.bounds = bounds; }
	}

	/**
	 * Constructs a hierarchy over the given per-triangle bounding boxes.
	 *
	 * @param triangleBounds the inflated box for each triangle, indexed by triangle index
	 */
	private BoundingVolumeHierarchy(BoundingSolid[] triangleBounds) {
		this.triangleBounds = triangleBounds;
		this.order = new int[triangleBounds.length];
		for (int i = 0; i < order.length; i++) order[i] = i;

		if (triangleBounds.length == 0) {
			this.root = null;
		} else {
			this.root = build(0, order.length);
		}
	}

	/**
	 * Builds a hierarchy from per-triangle axis-aligned bounds, such as those computed for a
	 * packed mesh by {@link org.almostrealism.graph.mesh.TriangleFeatures#meshTriangleBounds}.
	 *
	 * <p>The bounds are read from the host once, here, because the tree itself is a host
	 * structure; every per-triangle quantity has already been computed by the kernel that
	 * produced them.</p>
	 *
	 * @param triangleBounds the bounds, shaped {@code (T, 6)} as the minimum X, Y and Z of each
	 *                       triangle followed by its maximum X, Y and Z
	 * @return a new hierarchy whose triangle indices match the row order of the bounds
	 */
	public static BoundingVolumeHierarchy build(PackedCollection triangleBounds) {
		double[] b = triangleBounds.toArray();
		BoundingSolid[] bounds = new BoundingSolid[b.length / 6];
		for (int i = 0; i < bounds.length; i++) {
			int o = i * 6;
			bounds[i] = new BoundingSolid(b[o], b[o + 3], b[o + 1], b[o + 4], b[o + 2], b[o + 5])
					.expand(BOX_EPSILON);
		}
		return new BoundingVolumeHierarchy(bounds);
	}

	/**
	 * Returns the number of triangles indexed by this hierarchy.
	 *
	 * @return the triangle count
	 */
	public int getTriangleCount() { return triangleBounds.length; }

	/**
	 * Returns the indices of every triangle whose bounding box the given ray pierces.
	 *
	 * <p>The ray is the half-line starting at the origin and extending in the given
	 * direction. The returned set is conservative: it always includes the triangle that
	 * is actually closest along the ray, so intersecting only these candidates reproduces
	 * the closest hit of a full linear scan.</p>
	 *
	 * @param ox origin x
	 * @param oy origin y
	 * @param oz origin z
	 * @param dx direction x
	 * @param dy direction y
	 * @param dz direction z
	 * @return the candidate triangle indices in ascending order (possibly empty, never {@code null})
	 */
	public int[] candidates(double ox, double oy, double oz, double dx, double dy, double dz) {
		List<Integer> hits = new ArrayList<>();
		if (root != null) collect(root, ox, oy, oz, dx, dy, dz, hits);

		int[] result = new int[hits.size()];
		for (int i = 0; i < result.length; i++) result[i] = hits.get(i);

		// Return candidates in ascending triangle-index order so that a tie on intersection
		// distance (a ray grazing a shared edge or coplanar triangles) resolves to the same
		// triangle the linear scan would select, which tests triangles in mesh-index order.
		Arrays.sort(result);
		return result;
	}

	/**
	 * Recursively builds a subtree over the {@link #order} range {@code [from, to)}.
	 *
	 * @param from the inclusive start of the range
	 * @param to   the exclusive end of the range
	 * @return the root node of the subtree
	 */
	private Node build(int from, int to) {
		BoundingSolid bounds = triangleBounds[order[from]];
		for (int i = from + 1; i < to; i++) bounds = bounds.combine(triangleBounds[order[i]]);

		Node node = new Node(bounds);

		int count = to - from;
		if (count <= DEFAULT_LEAF_SIZE) {
			node.leaf = true;
			node.start = from;
			node.count = count;
			return node;
		}

		int axis = bounds.longestAxis();
		sortRange(from, to, axis);

		int mid = from + count / 2;
		node.left = build(from, mid);
		node.right = build(mid, to);
		return node;
	}

	/**
	 * Collects the triangles beneath the given node whose box the ray pierces.
	 *
	 * @param node the subtree to search
	 * @param ox   origin x
	 * @param oy   origin y
	 * @param oz   origin z
	 * @param dx   direction x
	 * @param dy   direction y
	 * @param dz   direction z
	 * @param hits the accumulating list of candidate triangle indices
	 */
	private void collect(Node node, double ox, double oy, double oz,
						 double dx, double dy, double dz, List<Integer> hits) {
		if (!node.bounds.intersectsRay(ox, oy, oz, dx, dy, dz)) return;

		if (node.leaf) {
			for (int i = 0; i < node.count; i++) {
				int tri = order[node.start + i];
				if (triangleBounds[tri].intersectsRay(ox, oy, oz, dx, dy, dz)) hits.add(tri);
			}
			return;
		}

		collect(node.left, ox, oy, oz, dx, dy, dz, hits);
		collect(node.right, ox, oy, oz, dx, dy, dz, hits);
	}

	/**
	 * Sorts the {@link #order} range {@code [from, to)} by triangle box center along the given axis.
	 *
	 * @param from the inclusive start of the range
	 * @param to   the exclusive end of the range
	 * @param axis the axis (0 = x, 1 = y, 2 = z)
	 */
	private void sortRange(int from, int to, int axis) {
		Integer[] slice = new Integer[to - from];
		for (int i = 0; i < slice.length; i++) slice[i] = order[from + i];

		Arrays.sort(slice, (a, b) -> Double.compare(triangleBounds[a].center(axis), triangleBounds[b].center(axis)));

		for (int i = 0; i < slice.length; i++) order[from + i] = slice[i];
	}
}
