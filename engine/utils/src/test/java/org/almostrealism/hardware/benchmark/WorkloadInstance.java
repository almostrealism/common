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

package org.almostrealism.hardware.benchmark;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * One computation of the benchmark population: a {@link ComputationTemplate} bound to a shape,
 * to the memory its tensor inputs live in, and to the values of its scalars.
 *
 * <p>Each tensor slot is a view of a root collection. Normally every slot has a root of its own,
 * exactly as long as the shape; an instance can instead give a slot a longer root (to move the
 * root across the aggregation size limit without changing the shape), or place the first two
 * slots on one root (see {@link Aliasing}). Input values are drawn from the instance's own seed,
 * so they do not depend on which other instances were generated, and they are read back once,
 * when allocated, so the reference is computed from the data the kernels read.</p>
 *
 * <p>The memory is allocated by {@link #allocate(CodeFeatures)} immediately before the instance is
 * run and released by {@link #release()} after it, so a large population does not hold every
 * input at once.</p>
 */
public class WorkloadInstance {
	/** How the first two tensor slots of an instance share memory. */
	public enum Aliasing {
		/** Every slot has its own root. */
		DISTINCT,
		/** The first two slots are disjoint views of one root, at offsets 0 and the shape size. */
		SHARED_ROOT,
		/** The first two slots are overlapping views of one root, the second at half the shape size. */
		OVERLAP,
		/** The first two slots are bound to the same view. */
		SAME_VIEW
	}

	/** The relative tolerance of {@link #verify(double[])}. */
	public static final double TOLERANCE = 1e-3;

	/** The template this instance binds. */
	private final ComputationTemplate template;
	/** The shape every tensor slot is viewed at. */
	private final TraversalPolicy shape;
	/** How the first two tensor slots share memory. */
	private final Aliasing aliasing;
	/** The length of each slot's root when it has a root of its own. */
	private final int[] rootLengths;
	/** The value of each scalar slot. */
	private final double[] scalarValues;
	/** The seed the input values are drawn from. */
	private final long dataSeed;

	/** The roots of the tensor slots, in root order, while allocated. */
	private List<PackedCollection> roots;
	/** The length of each root, in root order. */
	private int[] rootLengthsInOrder;
	/** The values of each root, in root order, read back once while allocated. */
	private List<double[]> rootValues;
	/** The view bound to each tensor slot, while allocated. */
	private PackedCollection[] tensors;
	/** The root index of each tensor slot. */
	private int[] rootOfSlot;
	/** The offset of each tensor slot within its root. */
	private int[] offsetOfSlot;
	/** The one-element input of each scalar slot, while allocated. */
	private PackedCollection[] scalars;

	/**
	 * Creates an instance.
	 *
	 * @param template     the template to bind
	 * @param shape        the shape every tensor slot is viewed at
	 * @param aliasing     how the first two tensor slots share memory
	 * @param rootLengths  the length of each slot's own root, at least the shape's size; ignored
	 *                     for the slots {@code aliasing} places on a shared root
	 * @param scalarValues the value of each scalar slot
	 * @param dataSeed     the seed the input values are drawn from
	 */
	public WorkloadInstance(ComputationTemplate template, TraversalPolicy shape, Aliasing aliasing,
							int[] rootLengths, double[] scalarValues, long dataSeed) {
		if (aliasing != Aliasing.DISTINCT && template.getTensorSlots() < 2) {
			throw new IllegalArgumentException("Aliasing needs at least two tensor slots");
		}

		this.template = template;
		this.shape = shape;
		this.aliasing = aliasing;
		this.rootLengths = rootLengths.clone();
		this.scalarValues = scalarValues.clone();
		this.dataSeed = dataSeed;
		layout();
	}

	/**
	 * Creates an instance whose slots each have a root exactly as long as the shape.
	 *
	 * @param template     the template to bind
	 * @param shape        the shape every tensor slot is viewed at
	 * @param aliasing     how the first two tensor slots share memory
	 * @param scalarValues the value of each scalar slot
	 * @param dataSeed     the seed the input values are drawn from
	 */
	public WorkloadInstance(ComputationTemplate template, TraversalPolicy shape, Aliasing aliasing,
							double[] scalarValues, long dataSeed) {
		this(template, shape, aliasing,
				IntStream.range(0, template.getTensorSlots()).map(slot -> shape.getTotalSize()).toArray(),
				scalarValues, dataSeed);
	}

	/** Decides which root, of which length, and which offset every tensor slot is bound to. */
	private void layout() {
		int size = shape.getTotalSize();
		int slots = template.getTensorSlots();
		rootOfSlot = new int[slots];
		offsetOfSlot = new int[slots];
		List<Integer> lengths = new ArrayList<>();

		for (int slot = 0; slot < slots; slot++) {
			if (slot == 1 && aliasing != Aliasing.DISTINCT) {
				rootOfSlot[slot] = rootOfSlot[0];
				offsetOfSlot[slot] = aliasing == Aliasing.SHARED_ROOT ? size
						: aliasing == Aliasing.OVERLAP ? size / 2 : 0;
				continue;
			}

			int length = Math.max(size, rootLengths[slot]);
			if (slot == 0 && aliasing == Aliasing.SHARED_ROOT) length = 2 * size;
			if (slot == 0 && aliasing == Aliasing.OVERLAP) length = size + size / 2;

			rootOfSlot[slot] = lengths.size();
			lengths.add(length);
		}

		rootLengthsInOrder = lengths.stream().mapToInt(Integer::intValue).toArray();
	}

	/**
	 * Allocates the roots, the slot views and the scalar inputs. The roots are drawn uniformly
	 * from {@code [0, 1)} by a random producer seeded from this instance's seed, and their values
	 * are read back once, so the reference is computed from exactly the data the kernels read.
	 *
	 * @param features the features used to construct the random producers
	 */
	public void allocate(CodeFeatures features) {
		Random random = new Random(dataSeed);
		roots = new ArrayList<>();
		rootValues = new ArrayList<>();
		for (int length : rootLengthsInOrder) {
			PackedCollection root = features.rand(new TraversalPolicy(length), random).evaluate();
			roots.add(root);
			rootValues.add(root.toArray());
		}

		tensors = new PackedCollection[template.getTensorSlots()];
		for (int slot = 0; slot < tensors.length; slot++) {
			if (slot == 1 && aliasing == Aliasing.SAME_VIEW) {
				tensors[slot] = tensors[0];
			} else {
				tensors[slot] = roots.get(rootOfSlot[slot]).range(shape, offsetOfSlot[slot]);
			}
		}

		scalars = new PackedCollection[scalarValues.length];
		for (int slot = 0; slot < scalars.length; slot++) {
			if (!template.isLiteral(slot)) {
				double value = scalarValues[slot];
				scalars[slot] = new PackedCollection(1).fill(value);
			}
		}
	}

	/** Releases the memory {@link #allocate(CodeFeatures)} created. */
	public void release() {
		if (roots != null) roots.forEach(PackedCollection::destroy);
		if (scalars != null) {
			for (PackedCollection scalar : scalars) {
				if (scalar != null) scalar.destroy();
			}
		}

		roots = null;
		rootValues = null;
		tensors = null;
		scalars = null;
	}

	/**
	 * Builds this instance's computation. The instance must be {@link #allocate(CodeFeatures) allocated}.
	 *
	 * @param features the features used to construct producers
	 * @return the computation
	 */
	public CollectionProducer build(CodeFeatures features) {
		return template.build(features, this);
	}

	/**
	 * Returns the output the computation must produce, evaluated on the host. The instance must be
	 * {@link #allocate(CodeFeatures) allocated}.
	 *
	 * @return the expected values, in output order
	 */
	public double[] reference() {
		return template.reference(this);
	}

	/**
	 * Returns the term magnitude of each output value (see
	 * {@link ComputationTemplate#referenceMagnitude(WorkloadInstance)}).
	 *
	 * @return the magnitudes, in output order
	 */
	public double[] referenceMagnitude() {
		return template.referenceMagnitude(this);
	}

	/**
	 * Checks an output of this instance's computation against the reference. Each value must be
	 * within {@value #TOLERANCE} of the reference, relative to the larger of 1 and the magnitude of
	 * the terms the value is computed from, which allows for single-precision rounding over a
	 * reduction of thousands of terms while still rejecting any kernel that reads or writes the
	 * wrong memory.
	 *
	 * @param actual the output values, in output order
	 * @return null when the output matches, otherwise a description of the first difference
	 */
	public String verify(double[] actual) {
		double[] expected = reference();
		double[] magnitude = referenceMagnitude();
		if (actual.length != expected.length) {
			return "output length " + actual.length + " where the reference has " + expected.length;
		}

		for (int i = 0; i < expected.length; i++) {
			double tolerance = TOLERANCE * Math.max(1.0, magnitude[i]);
			if (!(Math.abs(actual[i] - expected[i]) <= tolerance)) {
				return "output[" + i + "] = " + actual[i] + " where the reference is " + expected[i];
			}
		}

		return null;
	}

	/**
	 * Returns the view bound to a tensor slot.
	 *
	 * @param slot the tensor slot index
	 * @return the view
	 */
	public PackedCollection getTensor(int slot) { return tensors[slot]; }

	/**
	 * Returns the host values of the view bound to a tensor slot, in element order.
	 *
	 * @param slot the tensor slot index
	 * @return the values
	 */
	public double[] getTensorValues(int slot) {
		int offset = offsetOfSlot[slot];
		return Arrays.copyOfRange(rootValues.get(rootOfSlot[slot]), offset, offset + shape.getTotalSize());
	}

	/**
	 * Returns the one-element input of a scalar slot that is not embedded as a constant.
	 *
	 * @param slot the scalar slot index
	 * @return the input
	 */
	public PackedCollection getScalar(int slot) { return scalars[slot]; }

	/**
	 * Returns the value of a scalar slot.
	 *
	 * @param slot the scalar slot index
	 * @return the value
	 */
	public double getScalarValue(int slot) { return scalarValues[slot]; }

	/**
	 * Returns the shape every tensor slot is viewed at.
	 *
	 * @return the shape
	 */
	public TraversalPolicy getShape() { return shape; }

	/**
	 * Returns the template this instance binds.
	 *
	 * @return the template
	 */
	public ComputationTemplate getTemplate() { return template; }

	/**
	 * Returns how the first two tensor slots share memory.
	 *
	 * @return the aliasing
	 */
	public Aliasing getAliasing() { return aliasing; }

	/**
	 * Returns the lengths of the roots of the tensor slots, in root order.
	 *
	 * @return the root lengths
	 */
	public int[] getRootLengths() { return rootLengthsInOrder.clone(); }

	/**
	 * Returns a key that is equal for two instances exactly when they bind the same template to
	 * the same shape, memory layout and embedded constants: everything a kernel could depend on
	 * other than the input data. The fraction of instances whose key was already seen is the
	 * cache hit rate a signature that distinguished exactly these properties would achieve.
	 *
	 * @return the structure key
	 */
	public String structureKey() {
		StringBuilder key = new StringBuilder(template.getId()).append('|')
				.append(describeShape()).append('|').append(aliasing)
				.append('|').append(Arrays.toString(getRootLengths()));
		for (int slot = 0; slot < scalarValues.length; slot++) {
			if (template.isLiteral(slot)) key.append("|k").append(slot).append('=').append(scalarValues[slot]);
		}

		return key.toString();
	}

	/**
	 * Returns the shape as its dimensions, such as {@code 16x64}.
	 *
	 * @return the shape description
	 */
	public String describeShape() {
		StringBuilder b = new StringBuilder();
		for (int d = 0; d < shape.getDimensions(); d++) {
			if (d > 0) b.append('x');
			b.append(shape.length(d));
		}

		return b.toString();
	}

	/**
	 * Returns a key that is equal for two instances exactly when they were generated identically:
	 * the {@link #describe() structure and layout} together with the value of every scalar slot,
	 * embedded or not, and the seed of the input values. Unlike {@link #structureKey()}, it also
	 * distinguishes instances that differ only in data a kernel does not depend on, since that
	 * data still decides whether the instance verifies.
	 *
	 * @return the generation key
	 */
	public String generationKey() {
		return describe() + " scalars=" + Arrays.toString(scalarValues) + " dataSeed=" + dataSeed;
	}

	/**
	 * Returns a description of this instance's structure and layout.
	 *
	 * @return the description
	 */
	public String describe() {
		return template.getId() + " " + template.describe() + " shape=" + describeShape() +
				" aliasing=" + aliasing + " roots=" + Arrays.toString(getRootLengths());
	}
}
