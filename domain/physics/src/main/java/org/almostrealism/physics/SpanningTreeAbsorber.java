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
 * <p><b>Note:</b> This class is incomplete. All interface methods currently throw
 * {@link UnsupportedOperationException}. It exists as a structural placeholder
 * for the spanning tree absorption algorithm and is extended by
 * {@link org.almostrealism.chem.ElectronDensityAbsorber}.</p>
 *
 * @author  Michael Murray
 */
public class SpanningTreeAbsorber implements AbsorberSet {
	private double bound;

	@Override
	public int addAbsorber(Absorber a, Producer x) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int removeAbsorbers(double[] x, double radius) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int removeAbsorber(Absorber a) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setPotentialMap(PotentialMap m) {
		throw new UnsupportedOperationException();
	}

	@Override
	public PotentialMap getPotentialMap() {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setMaxProximity(double radius) {
		throw new UnsupportedOperationException();
	}

	@Override
	public double getMaxProximity() {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean absorb(Vector x, Vector p, double energy) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Producer<PackedCollection> emit() {
		throw new UnsupportedOperationException();
	}

	@Override
	public double getEmitEnergy() {
		throw new UnsupportedOperationException();
	}

	@Override
	public double getNextEmit() {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setClock(Clock c) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Clock getClock() {
		throw new UnsupportedOperationException();
	}

	@Override
	public int size() {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isEmpty() {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean contains(Object arg0) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Iterator iterator() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Object[] toArray() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Object[] toArray(Object[] arg0) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean add(Object arg0) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean remove(Object arg0) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean containsAll(Collection arg0) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean addAll(Collection arg0) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean retainAll(Collection arg0) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean removeAll(Collection arg0) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void clear() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Producer<PackedCollection> getEmitPosition() {
		throw new UnsupportedOperationException();
	}

	/** Sets the spatial bound for this absorber set. */
	public void setBound(double bound) { this.bound = bound; }

	@Override
	public double getBound() { return this.bound; }

	@Override
	public Iterator absorberIterator() {
		throw new UnsupportedOperationException();
	}

	@Override
	public double getDistance(Vector p, Vector d) {
		throw new UnsupportedOperationException();
	}
}
