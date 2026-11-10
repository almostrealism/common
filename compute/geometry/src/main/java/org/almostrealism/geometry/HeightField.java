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

package org.almostrealism.geometry;

import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * The surface {@code y = h(x, z)} of a height field sampled on a regular grid, as the
 * {@link ImplicitSurface} {@code f(p) = y - h(x, z)}, which is positive above the surface.
 *
 * <p>The heights are an {@code [nx, nz]} collection: the height at grid node {@code (i, j)}
 * is that of the point {@code (x0 + i dx, z0 + j dz)}. Between the nodes the height is
 * interpolated bilinearly, and outside the grid it is the height at the nearest edge. The
 * heights are read through a producer, so a height field built over a collection that is
 * updated between evaluations (a simulated surface, for example) always renders the current
 * heights.</p>
 *
 * <p>Within a grid cell the derivative of the bilinear height along {@code x} is a weighted
 * average of the slopes of the two cell edges along {@code x}, and likewise along {@code z},
 * so {@code |dh/dx| <= sx} and {@code |dh/dz| <= sz}, where {@code sx} and {@code sz} are the
 * largest slopes between neighbouring nodes. The {@link #lipschitzBound()} of the surface is
 * therefore {@code sqrt(1 + sx^2 + sz^2)}, computed from the heights on the device.</p>
 *
 * @author Michael Murray
 */
public class HeightField implements ImplicitSurface {
	/** The margin that keeps a sample position inside the last grid cell. */
	private static final double EDGE_MARGIN = 1e-4;

	/** The {@code [nx, nz]} heights. */
	private final Producer<PackedCollection> heights;

	/** The number of grid nodes along {@code x}. */
	private final int nx;

	/** The number of grid nodes along {@code z}. */
	private final int nz;

	/** The {@code x} coordinate of the first grid node. */
	private final double x0;

	/** The {@code z} coordinate of the first grid node. */
	private final double z0;

	/** The spacing of the grid nodes along {@code x}. */
	private final double dx;

	/** The spacing of the grid nodes along {@code z}. */
	private final double dz;

	/**
	 * Creates a height field over the given heights.
	 *
	 * @param heights the {@code [nx, nz]} heights, with at least two nodes along each axis
	 * @param x0 the {@code x} coordinate of the first grid node
	 * @param z0 the {@code z} coordinate of the first grid node
	 * @param dx the spacing of the grid nodes along {@code x}, which must be positive
	 * @param dz the spacing of the grid nodes along {@code z}, which must be positive
	 * @throws IllegalArgumentException if the heights are not a two-dimensional collection
	 *                                  with at least two nodes along each axis, or if either
	 *                                  spacing is not positive
	 */
	public HeightField(Producer<PackedCollection> heights, double x0, double z0, double dx, double dz) {
		TraversalPolicy shape = Shape.requireShape("HeightField", heights);
		if (shape.getDimensions() != 2 || shape.length(0) < 2 || shape.length(1) < 2) {
			throw new IllegalArgumentException("Heights must be [nx, nz] with nx, nz >= 2, got " + shape);
		}

		if (!(dx > 0.0) || !(dz > 0.0)) {
			throw new IllegalArgumentException("Grid spacing must be positive, got dx=" + dx + ", dz=" + dz);
		}

		this.heights = heights;
		this.nx = shape.length(0);
		this.nz = shape.length(1);
		this.x0 = x0;
		this.z0 = z0;
		this.dx = dx;
		this.dz = dz;
	}

	/**
	 * Returns {@code y - h(x, z)} for the given position: the height of the position above
	 * the surface.
	 *
	 * @param position a {@code (3)} position, or an {@code (n, 3)} batch of positions
	 * @return the {@code (1)} value, or the {@code (n, 1)} batch of values
	 */
	@Override
	public CollectionProducer value(Producer<PackedCollection> position) {
		return subset(shape(1), position, 1).subtract(new Sample(position).height());
	}

	/**
	 * Returns {@code sqrt(1 + sx^2 + sz^2)}, where {@code sx} and {@code sz} are the largest
	 * slopes between neighbouring grid nodes along {@code x} and {@code z}.
	 *
	 * @return a producer of the {@code (1)} bound
	 */
	@Override
	public CollectionProducer lipschitzBound() {
		CollectionProducer sx = max(subtract(subset(shape(nx - 1, nz), heights, 1, 0),
				subset(shape(nx - 1, nz), heights, 0, 0)).abs()).divide(c(dx));
		CollectionProducer sz = max(subtract(subset(shape(nx, nz - 1), heights, 0, 1),
				subset(shape(nx, nz - 1), heights, 0, 0)).abs()).divide(c(dz));
		return sqrt(sx.pow(c(2.0)).add(sz.pow(c(2.0))).add(c(1.0)));
	}

	/**
	 * Returns the unit normal {@code (-dh/dx, 1, -dh/dz) / |(-dh/dx, 1, -dh/dz)|} of the
	 * bilinearly interpolated surface below the given position. Outside the grid the height
	 * does not change along an axis whose coordinate is beyond the grid, so the derivative
	 * along that axis is zero there.
	 *
	 * @param position a {@code (3)} position, or an {@code (n, 3)} batch of positions
	 * @return the {@code (3)} normal, or the {@code (n, 3)} batch of normals
	 */
	@Override
	public CollectionProducer normal(Producer<PackedCollection> position) {
		Sample s = new Sample(position);
		return normalize(vector(s.slopeX().minus(), c(1.0), s.slopeZ().minus()));
	}

	/**
	 * The bilinear interpolation of the heights below a position, or a batch of positions:
	 * the grid cell that contains each position, the position within that cell, and the
	 * heights at the four corners of the cell.
	 */
	private class Sample {
		/** The position within the cell along {@code x}, from 0 to 1. */
		private final CollectionProducer fx;

		/** The position within the cell along {@code z}, from 0 to 1. */
		private final CollectionProducer fz;

		/** 1 where the unclamped grid coordinate along {@code x} is within the grid, otherwise 0. */
		private final CollectionProducer insideX;

		/** 1 where the unclamped grid coordinate along {@code z} is within the grid, otherwise 0. */
		private final CollectionProducer insideZ;

		/** The height at the corner {@code (i, j)} of the cell. */
		private final CollectionProducer h00;

		/** The height at the corner {@code (i + 1, j)} of the cell. */
		private final CollectionProducer h10;

		/** The height at the corner {@code (i, j + 1)} of the cell. */
		private final CollectionProducer h01;

		/** The height at the corner {@code (i + 1, j + 1)} of the cell. */
		private final CollectionProducer h11;

		/**
		 * Locates the cell below the given position and reads its corner heights.
		 *
		 * @param position a {@code (3)} position, or an {@code (n, 3)} batch of positions
		 */
		Sample(Producer<PackedCollection> position) {
			CollectionProducer rawU = subset(shape(1), position, 0).subtract(c(x0)).divide(c(dx));
			CollectionProducer rawV = subset(shape(1), position, 2).subtract(c(z0)).divide(c(dz));
			CollectionProducer u = bound(rawU, 0.0, nx - 1 - EDGE_MARGIN);
			CollectionProducer v = bound(rawV, 0.0, nz - 1 - EDGE_MARGIN);

			insideX = insideGrid(rawU, nx);
			insideZ = insideGrid(rawV, nz);
			CollectionProducer i = floor(u);
			CollectionProducer j = floor(v);
			CollectionProducer node = i.multiply(c(nz)).add(j);

			fx = u.subtract(i);
			fz = v.subtract(j);
			h00 = heightAt(node);
			h10 = heightAt(node.add(c(nz)));
			h01 = heightAt(node.add(c(1.0)));
			h11 = heightAt(node.add(c(nz + 1.0)));
		}

		/**
		 * Returns 1 where the unclamped grid coordinate is within {@code [0, n - 1]}, and 0
		 * where it is beyond the grid, so the height there is clamped to the edge.
		 *
		 * @param coordinate the unclamped grid coordinate, for every position
		 * @param n the number of grid nodes along the axis
		 * @return the mask, for every position
		 */
		private CollectionProducer insideGrid(CollectionProducer coordinate, int n) {
			return greaterThan(coordinate, c(0.0),
					lessThan(coordinate, c(n - 1.0), c(1.0), c(0.0), true), c(0.0), true);
		}

		/**
		 * Returns the height of the grid node with the given flat index.
		 *
		 * @param node the flat index {@code i * nz + j} of the node, for every position
		 * @return the height of the node, for every position
		 */
		private CollectionProducer heightAt(CollectionProducer node) {
			return c(node.getShape(), heights, node);
		}

		/**
		 * Returns the bilinearly interpolated height.
		 *
		 * @return the height, for every position
		 */
		CollectionProducer height() {
			CollectionProducer near = h00.add(h10.subtract(h00).multiply(fx));
			CollectionProducer far = h01.add(h11.subtract(h01).multiply(fx));
			return near.add(far.subtract(near).multiply(fz));
		}

		/**
		 * Returns the derivative of the interpolated height along {@code x}, which is zero
		 * where the position is beyond the grid along {@code x}.
		 *
		 * @return {@code dh/dx}, for every position
		 */
		CollectionProducer slopeX() {
			CollectionProducer near = h10.subtract(h00);
			CollectionProducer far = h11.subtract(h01);
			return near.add(far.subtract(near).multiply(fz)).divide(c(dx)).multiply(insideX);
		}

		/**
		 * Returns the derivative of the interpolated height along {@code z}, which is zero
		 * where the position is beyond the grid along {@code z}.
		 *
		 * @return {@code dh/dz}, for every position
		 */
		CollectionProducer slopeZ() {
			CollectionProducer near = h01.subtract(h00);
			CollectionProducer far = h11.subtract(h10);
			return near.add(far.subtract(near).multiply(fx)).divide(c(dz)).multiply(insideZ);
		}
	}
}
