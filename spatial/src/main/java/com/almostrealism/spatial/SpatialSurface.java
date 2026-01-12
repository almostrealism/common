/*
 * Copyright 2024 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

import io.almostrealism.code.Constant;
import io.almostrealism.code.Operator;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.ContinuousField;
import org.almostrealism.geometry.Ray;
import org.almostrealism.space.AbstractSurface;

/**
 * A surface wrapper for {@link SpatialElement} objects enabling integration
 * with the 3D rendering system.
 *
 * <p>{@code SpatialSurface} extends {@link AbstractSurface} to allow spatial
 * elements to participate in the ar-common geometry and rendering pipeline.
 * This enables spatial visualization points to be treated as scene objects
 * for ray tracing or other rendering operations.</p>
 *
 * <p>Note: This is a minimal implementation. Ray intersection and normal
 * calculation are not supported and will throw {@link UnsupportedOperationException}.</p>
 *
 * @see SpatialElement
 * @see AbstractSurface
 */
public class SpatialSurface extends AbstractSurface {
	private SpatialElement element;

	/**
	 * Creates a surface wrapping the specified spatial element.
	 *
	 * @param element the spatial element to wrap
	 */
	public SpatialSurface(SpatialElement element) {
		this.element = element;
	}

	/**
	 * Returns the wrapped spatial element.
	 *
	 * @return the spatial element
	 */
	public SpatialElement getElement() { return element; }

	/**
	 * {@inheritDoc}
	 *
	 * @throws UnsupportedOperationException always (not implemented)
	 */
	@Override
	public Producer<PackedCollection> getNormalAt(Producer<PackedCollection> point) {
		throw new UnsupportedOperationException();
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws UnsupportedOperationException always (not implemented)
	 */
	@Override
	public ContinuousField intersectAt(Producer<?> ray) {
		throw new UnsupportedOperationException();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns a constant producer of 0.</p>
	 */
	@Override
	public Operator<PackedCollection> get() {
		return new Constant<>(pack(0));
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns a constant producer of 1.</p>
	 */
	@Override
	public Operator<PackedCollection> expect() {
		return new Constant<>(pack(1));
	}
}
