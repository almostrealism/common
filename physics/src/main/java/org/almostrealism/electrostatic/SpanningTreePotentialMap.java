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

	public int addPotentialMap(PotentialMap m, double[] x) {
		// TODO Auto-generated method stub
		return 0;
	}

	public int removePotentialMaps(double[] x, double radius) {
		// TODO Auto-generated method stub
		return 0;
	}

	public int removePotentialMap(PotentialMap m) {
		// TODO Auto-generated method stub
		return 0;
	}

	public void setMaxProximity(double radius) {
		// TODO Auto-generated method stub

	}

	public double getMaxProximity() {
		// TODO Auto-generated method stub
		return 0;
	}

	public double getPotential(double[] p) {
		// TODO Auto-generated method stub
		return 0;
	}

	public int size() {
		// TODO Auto-generated method stub
		return 0;
	}

	public boolean isEmpty() {
		// TODO Auto-generated method stub
		return false;
	}

	public boolean contains(Object arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	public Iterator iterator() {
		// TODO Auto-generated method stub
		return null;
	}

	public Object[] toArray() {
		// TODO Auto-generated method stub
		return null;
	}

	public Object[] toArray(Object[] arg0) {
		// TODO Auto-generated method stub
		return null;
	}

	public boolean add(Object arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	public boolean remove(Object arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	public boolean containsAll(Collection arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	public boolean addAll(Collection arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	public boolean retainAll(Collection arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	public boolean removeAll(Collection arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	public void clear() {
		// TODO Auto-generated method stub

	}

}
