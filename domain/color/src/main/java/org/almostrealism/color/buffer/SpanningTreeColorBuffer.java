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

/**
 * A {@link ColorBuffer} placeholder that will use a spanning-tree data structure for
 * efficient color storage and retrieval.
 *
 * <p>This implementation is not yet functional; all mutating methods are no-ops and
 * retrieval always returns {@code null}. The scale factor is stored but not applied.</p>
 *
 * @see ColorBuffer
 * @author Michael Murray
 */
public class SpanningTreeColorBuffer implements ColorBuffer {
	/** The scale factor applied to colors when added to the buffer. */
	private double m = 1.0;

	/**
	 * Not implemented; this method is a no-op.
	 *
	 * @param u     the horizontal texture coordinate (unused)
	 * @param v     the vertical texture coordinate (unused)
	 * @param front {@code true} for front surface, {@code false} for back (unused)
	 * @param c     the color to add (unused)
	 */
	@Override
	public void addColor(double u, double v, boolean front, RGB c) {
		// TODO Auto-generated method stub
	}

	/**
	 * Not implemented; always returns {@code null}.
	 *
	 * @param u     the horizontal texture coordinate (unused)
	 * @param v     the vertical texture coordinate (unused)
	 * @param front {@code true} for front surface, {@code false} for back (unused)
	 * @return {@code null}
	 */
	@Override
	public RGB getColorAt(double u, double v, boolean front) {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * Not implemented; this method is a no-op.
	 */
	@Override
	public void clear() {
		// TODO Auto-generated method stub
	}

	/** {@inheritDoc} */
	@Override
	public double getScale() { return this.m; }

	/** {@inheritDoc} */
	@Override
	public void setScale(double m) { this.m = m; }
}