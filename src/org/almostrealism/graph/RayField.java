package org.almostrealism.graph;

import org.almostrealism.algebra.DiscreteField;
import org.almostrealism.algebra.Ray;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.Callable;

public class RayField implements DiscreteField {

    @Override
    public int size() {
        return 0;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public boolean contains(Object o) {
        return false;
    }

    @Override
    public Iterator<Callable<Ray>> iterator() {
        return null;
    }

    @Override
    public Object[] toArray() {
        return new Object[0];
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return null;
    }

    @Override
    public boolean add(Callable<Ray> rayCallable) {
        return false;
    }

    @Override
    public boolean remove(Object o) {
        return false;
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return false;
    }

    @Override
    public boolean addAll(Collection<? extends Callable<Ray>> c) {
        return false;
    }

    @Override
    public boolean addAll(int index, Collection<? extends Callable<Ray>> c) {
        return false;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return false;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return false;
    }

    @Override
    public void clear() {

    }

    @Override
    public Callable<Ray> get(int index) {
        return null;
    }

    @Override
    public Callable<Ray> set(int index, Callable<Ray> element) {
        return null;
    }

    @Override
    public void add(int index, Callable<Ray> element) {

    }

    @Override
    public Callable<Ray> remove(int index) {
        return null;
    }

    @Override
    public int indexOf(Object o) {
        return 0;
    }

    @Override
    public int lastIndexOf(Object o) {
        return 0;
    }

    @Override
    public ListIterator<Callable<Ray>> listIterator() {
        return null;
    }

    @Override
    public ListIterator<Callable<Ray>> listIterator(int index) {
        return null;
    }

    @Override
    public List<Callable<Ray>> subList(int fromIndex, int toIndex) {
        return null;
    }
}
