package org.almostrealism.graph;

import org.almostrealism.algebra.DiscreteField;
import org.almostrealism.algebra.Ray;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.Callable;

/**
 * Represents a field of Rays within 3D space.
 */
public class RayField implements DiscreteField {

    /*
     TODO:
        This is just a skeletal implementation of a RayField forwarding all methods to an internal List.
        We'll need to implement a proper data structure (octree?) for spatial operations.
      */

    private List<Callable<Ray>> rays = new ArrayList<>();

    @Override
    public int size() {
        return rays.size();
    }

    @Override
    public boolean isEmpty()
    {
        return rays.isEmpty();
    }

    @Override
    public boolean add(Callable<Ray> rayCallable) {
        return rays.add(rayCallable);
    }

    @Override
    public boolean contains(Object o) {
        return rays.contains(o);
    }

    @Override
    public Iterator<Callable<Ray>> iterator() {
        return rays.iterator();
    }

    @Override
    public Object[] toArray() {
        return rays.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return (T[]) rays.toArray();
    }

    @Override
    public boolean remove(Object o) {
        return rays.remove(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return rays.containsAll(c);
    }

    @Override
    public boolean addAll(Collection<? extends Callable<Ray>> c) {
        return rays.addAll(c);
    }

    @Override
    public boolean addAll(int index, Collection<? extends Callable<Ray>> c) {
        return rays.addAll(index, c);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return rays.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return rays.retainAll(c);
    }

    @Override
    public void clear() {
        rays.clear();
    }

    @Override
    public Callable<Ray> get(int index) {
        return rays.get(index);
    }

    @Override
    public Callable<Ray> set(int index, Callable<Ray> element) {
        return rays.set(index, element);
    }

    @Override
    public void add(int index, Callable<Ray> element) {
        rays.add(index, element);
    }

    @Override
    public Callable<Ray> remove(int index) {
        return rays.remove(index);
    }

    @Override
    public int indexOf(Object o) {
        return rays.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        return rays.lastIndexOf(o);
    }

    @Override
    public ListIterator<Callable<Ray>> listIterator() {
        return rays.listIterator();
    }

    @Override
    public ListIterator<Callable<Ray>> listIterator(int index) {
        return rays.listIterator(index);
    }

    @Override
    public List<Callable<Ray>> subList(int fromIndex, int toIndex) {
        return rays.subList(fromIndex, toIndex);
    }
}
