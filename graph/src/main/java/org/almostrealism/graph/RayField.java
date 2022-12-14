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

import org.almostrealism.geometry.DiscreteField;
import org.almostrealism.geometry.Ray;
import org.almostrealism.algebra.Vector;
import io.almostrealism.relation.Producer;

import java.util.*;

/**
 * Represents a field of Rays within 3D space.
 *
 * @author Dan Chivers
 */
public class RayField implements DiscreteField {
    private final HashSet<Ray> raysSet = new HashSet<>();
    private final KdTree<Ray> rays = new KdTree.SqrEuclid<>(3, null);

    public KdTree.Entry<Ray> getClosestRay(Vector vertex) {
        return getClosestRays(vertex, 1, false).get(0);
    }

    public List<KdTree.Entry<Ray>> getClosestRays(Vector vertex, int numberOfResults, boolean sorted) {
        return rays.nearestNeighbor(vertex.getData(), numberOfResults, sorted);
    }

    public Set<Ray> getRaySet() {
        return Collections.unmodifiableSet(raysSet);
    }

    @Override
    public boolean add(Producer<Ray> rayCallable) {
        try {
            Ray ray = rayCallable.get().evaluate();
            raysSet.add(ray);
            rays.addPoint(ray.getOrigin().getData(), ray);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean addAll(Collection<? extends Producer<Ray>> c) {
        boolean allOk = true;
        for (Producer<Ray> r : c) {
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
    public Iterator<Producer<Ray>> iterator() {
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
    public boolean addAll(int index, Collection<? extends Producer<Ray>> c) {
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
    public Producer<Ray> get(int index) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Producer<Ray> set(int index, Producer<Ray> element) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void add(int index, Producer<Ray> element) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Producer<Ray> remove(int index) {
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
    public ListIterator<Producer<Ray>> listIterator() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ListIterator<Producer<Ray>> listIterator(int index) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Producer<Ray>> subList(int fromIndex, int toIndex) {
        throw new UnsupportedOperationException();
    }
}
