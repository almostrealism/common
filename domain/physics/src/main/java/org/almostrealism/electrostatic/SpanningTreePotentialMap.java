/*
 * Copyright 2016 Michael Murray
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

/*
 * Copyright (C) 2006  Mike Murray
 *
 *  All rights reserved.
 *  This document may not be reused without
 *  express written permission from Mike Murray.
 */

package org.almostrealism.electrostatic;

import java.util.Collection;
import java.util.Iterator;

/**
 * A SpanningTreePotentialMap object is an implementation of PotentialMapSet that uses
 * a minimum spanning tree to store the child potential maps. Each child is linked to
 * a small number of closest neighbors so that potential energy calculations can be done
 * more quickly by traversing the tree.
 * 
 * @author Mike Murray
 */
public class SpanningTreePotentialMap implements PotentialMapSet {

	/**
	 * Adds a potential map to the spanning tree at the specified position.
	 *
	 * @param m  the potential map to add
	 * @param x  {x, y, z} position of the map in space
	 * @return   the new size of the collection (not yet implemented, returns 0)
	 */
	@Override
	public int addPotentialMap(PotentialMap m, double[] x) {
		// TODO Auto-generated method stub
		return 0;
	}

	/**
	 * Removes all potential maps within the specified spherical volume.
	 *
	 * @param x       {x, y, z} center of the removal volume
	 * @param radius  radius of the removal volume
	 * @return        the number of maps removed (not yet implemented, returns 0)
	 */
	@Override
	public int removePotentialMaps(double[] x, double radius) {
		// TODO Auto-generated method stub
		return 0;
	}

	/**
	 * Removes the specified potential map from the spanning tree.
	 *
	 * @param m  the potential map to remove
	 * @return   the new size of the collection (not yet implemented, returns 0)
	 */
	@Override
	public int removePotentialMap(PotentialMap m) {
		// TODO Auto-generated method stub
		return 0;
	}

	/**
	 * Sets the maximum distance between neighboring nodes in the spanning tree.
	 *
	 * @param radius  the maximum proximity radius
	 */
	@Override
	public void setMaxProximity(double radius) {
		// TODO Auto-generated method stub

	}

	/**
	 * Returns the maximum distance between neighboring nodes in the spanning tree.
	 *
	 * @return the maximum proximity radius (not yet implemented, returns 0)
	 */
	@Override
	public double getMaxProximity() {
		// TODO Auto-generated method stub
		return 0;
	}

	/**
	 * Returns the combined electrostatic potential at the specified point.
	 *
	 * @param p  {x, y, z} the position at which to evaluate the potential
	 * @return   the potential at the specified point (not yet implemented, returns 0)
	 */
	@Override
	public double getPotential(double[] p) {
		// TODO Auto-generated method stub
		return 0;
	}

	/**
	 * Returns the number of potential maps in this collection.
	 *
	 * @return the size (not yet implemented, returns 0)
	 */
	@Override
	public int size() {
		// TODO Auto-generated method stub
		return 0;
	}

	/**
	 * Returns {@code true} if this collection contains no potential maps.
	 *
	 * @return whether the collection is empty (not yet implemented, returns false)
	 */
	@Override
	public boolean isEmpty() {
		// TODO Auto-generated method stub
		return false;
	}

	/**
	 * Returns {@code true} if this collection contains the specified object.
	 *
	 * @param arg0  the object to check for containment
	 * @return      whether the object is contained (not yet implemented, returns false)
	 */
	@Override
	public boolean contains(Object arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	/**
	 * Returns an iterator over the potential maps in this collection.
	 *
	 * @return an iterator (not yet implemented, returns null)
	 */
	@Override
	public Iterator iterator() {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * Returns an array containing all potential maps in this collection.
	 *
	 * @return an object array (not yet implemented, returns null)
	 */
	@Override
	public Object[] toArray() {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * Returns an array containing all potential maps, using the provided array if large enough.
	 *
	 * @param arg0  the array into which to store the elements
	 * @return      an object array (not yet implemented, returns null)
	 */
	@Override
	public Object[] toArray(Object[] arg0) {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * Adds a potential map object to this collection.
	 *
	 * @param arg0  the object to add
	 * @return      whether the collection changed (not yet implemented, returns false)
	 */
	@Override
	public boolean add(Object arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	/**
	 * Removes a single instance of the specified object from this collection.
	 *
	 * @param arg0  the object to remove
	 * @return      whether the collection changed (not yet implemented, returns false)
	 */
	@Override
	public boolean remove(Object arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	/**
	 * Returns {@code true} if this collection contains all elements of the specified collection.
	 *
	 * @param arg0  the collection to check
	 * @return      whether all elements are contained (not yet implemented, returns false)
	 */
	@Override
	public boolean containsAll(Collection arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	/**
	 * Adds all elements of the specified collection to this collection.
	 *
	 * @param arg0  the collection of elements to add
	 * @return      whether the collection changed (not yet implemented, returns false)
	 */
	@Override
	public boolean addAll(Collection arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	/**
	 * Retains only the elements in this collection that are contained in the specified collection.
	 *
	 * @param arg0  the collection specifying which elements to retain
	 * @return      whether the collection changed (not yet implemented, returns false)
	 */
	@Override
	public boolean retainAll(Collection arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	/**
	 * Removes all elements of the specified collection from this collection.
	 *
	 * @param arg0  the collection of elements to remove
	 * @return      whether the collection changed (not yet implemented, returns false)
	 */
	@Override
	public boolean removeAll(Collection arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	/**
	 * Removes all potential maps from this collection.
	 */
	@Override
	public void clear() {
		// TODO Auto-generated method stub

	}

}
