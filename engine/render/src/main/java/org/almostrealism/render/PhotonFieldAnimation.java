/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.render;

import org.almostrealism.algebra.ZeroVector;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.physics.Absorber;
import org.almostrealism.physics.Clock;
import org.almostrealism.physics.PhotonField;
import org.almostrealism.raytrace.AbsorberHashSet;
import org.almostrealism.raytrace.DefaultPhotonField;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * A {@link RayTracedAnimation} that also runs a photon field on its clock. Every member that is an
 * {@link Absorber} is kept in the field's {@link AbsorberHashSet}, at the origin, however it is
 * added to or removed from the animation.
 *
 * @param <T> the type of surface in the scene
 */
public class PhotonFieldAnimation<T extends ShadableSurface> extends RayTracedAnimation<T> {
	/** The absorbers among the members, which the photon field propagates between. */
	private final AbsorberHashSet absorber;

	/** The photon field, ticked by the animation's clock. */
	private final PhotonField field;

	/** Creates an empty animation whose clock runs a photon field over an empty absorber set. */
	public PhotonFieldAnimation() {
		absorber = new AbsorberHashSet();
		field = new DefaultPhotonField();
		field.setAbsorber(absorber);
		getClock().addPhotonField(field);
	}

	/**
	 * Returns the absorber set holding the members that are absorbers.
	 *
	 * @return the absorber set
	 */
	public AbsorberHashSet getAbsorber() { return absorber; }

	/**
	 * Replaces the clock, moving the photon field from the previous clock to the replacement so the
	 * simulation keeps advancing when the new clock ticks.
	 *
	 * @param c the clock that will tick the animation
	 */
	@Override
	public void setClock(Clock c) {
		Clock previous = getClock();
		if (previous != null) previous.removePhotonField(field);
		super.setClock(c);
		if (c != null) c.addPhotonField(field);
	}

	/**
	 * Returns the photon field run by the animation's clock.
	 *
	 * @return the photon field
	 */
	public PhotonField getPhotonField() { return field; }

	/**
	 * Adds the given member to the absorber set if it is an absorber.
	 *
	 * @param member the member added to the animation
	 */
	private void attach(Object member) {
		if (member instanceof Absorber) absorber.addAbsorber((Absorber) member, ZeroVector.getInstance());
	}

	/**
	 * Removes the given member from the absorber set if it is an absorber and no remaining member
	 * of the animation is the same object. Called after the member has been removed from the list,
	 * so a surviving duplicate reference keeps the absorber registered.
	 *
	 * @param member the member removed from the animation
	 */
	private void detach(Object member) {
		if (member instanceof Absorber && !containsIdentity(member)) absorber.removeAbsorber((Absorber) member);
	}

	/**
	 * Returns whether the given object is still a member of the animation by reference identity.
	 * Used by {@link #detach} rather than {@link #contains} so that the decision keys on the exact
	 * object reference, not on {@code equals} - matching the "same object" semantics the absorber
	 * set is kept in step with.
	 *
	 * @param member the object to look for
	 * @return true if a member of the animation is the same object as {@code member}
	 */
	private boolean containsIdentity(Object member) {
		for (int i = 0; i < size(); i++) {
			if (get(i) == member) return true;
		}

		return false;
	}

	@Override
	public boolean add(T t) {
		if (!super.add(t)) return false;
		attach(t);
		return true;
	}

	@Override
	public void add(int index, T element) {
		super.add(index, element);
		attach(element);
	}

	@Override
	public boolean addAll(Collection<? extends T> c) {
		boolean changed = false;
		for (T t : new ArrayList<T>(c)) changed |= add(t);
		return changed;
	}

	@Override
	public boolean addAll(int index, Collection<? extends T> c) {
		int i = index;
		for (T t : new ArrayList<T>(c)) add(i++, t);
		return !c.isEmpty();
	}

	@Override
	public T set(int index, T element) {
		T previous = super.set(index, element);
		detach(previous);
		attach(element);
		return previous;
	}

	@Override
	public boolean remove(Object o) {
		if (!super.remove(o)) return false;
		detach(o);
		return true;
	}

	@Override
	public T remove(int index) {
		T t = super.remove(index);
		detach(t);
		return t;
	}

	@Override
	public boolean removeIf(Predicate<? super T> filter) {
		boolean changed = false;

		for (int i = size() - 1; i >= 0; i--) {
			if (filter.test(get(i))) {
				remove(i);
				changed = true;
			}
		}

		return changed;
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		return removeIf(c::contains);
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		return removeIf(t -> !c.contains(t));
	}

	@Override
	public void clear() {
		super.clear();
		absorber.clear();
	}

	/**
	 * Removes the members in the given index range, detaching each absorber among them whose object
	 * no longer survives elsewhere in the animation. This is the path a view-based removal takes
	 * (for example {@code subList(from, to).clear()}), so overriding it keeps the absorber set in
	 * step with such removals too.
	 *
	 * @param fromIndex the index of the first member to remove, inclusive
	 * @param toIndex   the index after the last member to remove, exclusive
	 */
	@Override
	protected void removeRange(int fromIndex, int toIndex) {
		List<T> removed = new ArrayList<T>(subList(fromIndex, toIndex));
		super.removeRange(fromIndex, toIndex);
		for (T t : removed) detach(t);
	}
}
