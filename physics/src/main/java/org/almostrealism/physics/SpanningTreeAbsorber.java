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


package org.almostrealism.physics;

import org.almostrealism.electrostatic.PotentialMap;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;

import java.util.Collection;
import java.util.Iterator;

/**
 * A {@link SpanningTreeAbsorber} is an implementation of AbsorberSet that uses
 * a minimum spanning tree data structure to store the child absorbers. Each
 * child is linked to a small number of closest neighbors so that absorption
 * calculations can be done more quickly by traversing the tree.
 * 
 * @author  Michael Murray
 */
public class SpanningTreeAbsorber implements AbsorberSet {
	private double bound;

	@Override
	public int addAbsorber(Absorber a, Producer x) {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int removeAbsorbers(double[] x, double radius) {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int removeAbsorber(Absorber a) {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public void setPotentialMap(PotentialMap m) {
		// TODO Auto-generated method stub

	}

	@Override
	public PotentialMap getPotentialMap() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void setMaxProximity(double radius) {
		// TODO Auto-generated method stub

	}

	@Override
	public double getMaxProximity() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public boolean absorb(Vector x, Vector p, double energy) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public Producer<PackedCollection> emit() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public double getEmitEnergy() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public double getNextEmit() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public void setClock(Clock c) {
		// TODO Auto-generated method stub

	}

	@Override
	public Clock getClock() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int size() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public boolean isEmpty() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean contains(Object arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public Iterator iterator() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Object[] toArray() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Object[] toArray(Object[] arg0) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean add(Object arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean remove(Object arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean containsAll(Collection arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean addAll(Collection arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean retainAll(Collection arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean removeAll(Collection arg0) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void clear() {
		// TODO Auto-generated method stub

	}

	@Override
	public Producer<PackedCollection> getEmitPosition() {
		// TODO Auto-generated method stub
		return null;
	}

	public void setBound(double bound) { this.bound = bound; }

	@Override
	public double getBound() { return this.bound; }

	@Override
	public Iterator absorberIterator() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public double getDistance(Vector p, Vector d) {
		// TODO Auto-generated method stub
		return 0;
	}
}
