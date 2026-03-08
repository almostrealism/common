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

package org.almostrealism.graph;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.DiscreteField;
import org.almostrealism.geometry.Ray;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

/**
 * Represents a field of Rays within 3D space.
 *
 * @author Dan Chivers
 */
public class RayField implements DiscreteField {
    private final HashSet<PackedCollection> raysSet = new HashSet<>();
    private final KdTree<PackedCollection> rays = new KdTree.SqrEuclid<>(3, null);

    public KdTree.Entry<PackedCollection> getClosestRay(Vector vertex) {
        return getClosestRays(vertex, 1, false).get(0);
    }

    public List<KdTree.Entry<PackedCollection>> getClosestRays(Vector vertex, int numberOfResults, boolean sorted) {
        return rays.nearestNeighbor(vertex.getData(), numberOfResults, sorted);
    }

    public Set<PackedCollection> getRaySet() {
        return Collections.unmodifiableSet(raysSet);
    }

    @Override
    public boolean add(Producer<PackedCollection> rayCallable) {
        try {
            Ray ray = new Ray(rayCallable.get().evaluate(), 0);
            raysSet.add(ray);
            rays.addPoint(ray.getOrigin().getData(), ray);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean addAll(Collection<? extends Producer<PackedCollection>> c) {
        boolean allOk = true;
        for (Producer<PackedCollection> r : c) {
            allOk &= add(r);
        }
        return allOk;
    }

    @Override
    public boolean isEmpty()
    {
        return raysSet.isEmpty();
    }

    @Override
    public int size() {
        return raysSet.size();
    }

    @Override
    public boolean contains(Object o) {
        return raysSet.contains(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return raysSet.containsAll(c);
    }

    @Override
    public Object[] toArray() {
        return raysSet.toArray();
    }

    /*******************************
     * End of supported operations *
     *******************************/

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterator<Producer<PackedCollection>> iterator() {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(int index, Collection<? extends Producer<PackedCollection>> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Producer<PackedCollection> get(int index) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Producer<PackedCollection> set(int index, Producer<PackedCollection> element) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void add(int index, Producer<PackedCollection> element) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Producer<PackedCollection> remove(int index) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int indexOf(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int lastIndexOf(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ListIterator<Producer<PackedCollection>> listIterator() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ListIterator<Producer<PackedCollection>> listIterator(int index) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Producer<PackedCollection>> subList(int fromIndex, int toIndex) {
        throw new UnsupportedOperationException();
    }
}
