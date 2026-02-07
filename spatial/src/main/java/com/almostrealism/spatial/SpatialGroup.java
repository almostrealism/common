/*
 * Copyright 2024 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

import org.almostrealism.algebra.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * A container that groups multiple {@link SpatialElement} objects together,
 * implementing a composite pattern for hierarchical spatial organization.
 *
 * <p>{@code SpatialGroup} extends {@link SpatialElement} and maintains a list
 * of child elements. The group itself has a position, and child elements can
 * be positioned relative to or independent of the group's position depending
 * on the visualization context.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * SpatialGroup group = new SpatialGroup(new Vector(0, 0, 0));
 * group.getElements().add(new SpatialElement(new Vector(1, 0, 0)));
 * group.getElements().add(new SpatialElement(new Vector(2, 0, 0)));
 * }</pre>
 *
 * @see SpatialElement
 * @see SpatialValue
 */
public class SpatialGroup extends SpatialElement {
	private List<SpatialElement> elements;

	/**
	 * Creates a new {@code SpatialGroup} at the origin (0, 0, 0).
	 */
	public SpatialGroup() {
		this(new Vector());
	}

	/**
	 * Creates a new {@code SpatialGroup} at the specified position.
	 *
	 * @param position the position of this group in 3D space
	 */
	public SpatialGroup(Vector position) {
		super(position);
		elements = new ArrayList<>();
	}

	/**
	 * Returns the list of child spatial elements in this group.
	 *
	 * @return a mutable list of {@link SpatialElement} children
	 */
	public List<SpatialElement> getElements() {
		return elements;
	}

	/**
	 * Replaces the list of child spatial elements in this group.
	 *
	 * @param elements the new list of child elements
	 */
	public void setElements(List<SpatialElement> elements) {
		this.elements = elements;
	}
}
