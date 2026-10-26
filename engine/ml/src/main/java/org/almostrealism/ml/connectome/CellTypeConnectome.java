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

package org.almostrealism.ml.connectome;

import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/**
 * A connectome aggregated to cell types: a dense, signed type-by-type weight matrix together with
 * the name, size and outgoing synapse count of every type.
 *
 * <p>The data is the output directory of {@code tools/connectome/male_cns_cell_types.py}:</p>
 * <ul>
 *   <li>{@value #WEIGHTS_FILE}, read through {@link StateDictionary}, holding {@code weights}
 *       ({@code [T, T]}, row = postsynaptic type, column = presynaptic type, each entry the sum
 *       over member neurons of the presynaptic neuron's transmitter sign times the synapse
 *       count), {@code type_sizes} ({@code [T]}, neurons per type) and
 *       {@code type_out_synapses} ({@code [T]}, outgoing synapses of each type);</li>
 *   <li>{@value #TYPES_FILE}, one type name per line in index order;</li>
 *   <li>{@value #ANNOTATIONS_FILE}, a header line followed by the name, superclass and class of
 *       each type (each the most common among the type's members), tab-separated, in index
 *       order.</li>
 * </ul>
 *
 * <p>Because rows are postsynaptic, the matrix is used as is for the input {@code W r} of a
 * population whose unit {@code i} is type {@code i}. The counts are not normalized; a model that
 * wants the mean input per neuron of a type scales the rows by {@link #inverseTypeSizes()}.</p>
 *
 * <p>This type knows the file layout of the derived data and nothing about dynamics; the rate
 * model that runs on it knows nothing about the layout. The connectome owns the loaded
 * collections and releases them in {@link #destroy()}.</p>
 *
 * @author Michael Murray
 */
public class CellTypeConnectome implements CollectionFeatures, Destroyable {
	/** The safetensors file holding the matrix and the per-type counts. */
	public static final String WEIGHTS_FILE = "cell-type-weights.safetensors";

	/** The file listing the type names in index order. */
	public static final String TYPES_FILE = "cell-types.txt";

	/** The file listing each type's superclass and class in index order. */
	public static final String ANNOTATIONS_FILE = "cell-type-annotations.tsv";

	/** The loaded tensors. */
	private final StateDictionary tensors;

	/** The type names, in index order. */
	private final List<String> typeNames;

	/** The superclass of each type, in index order ({@code ""} when unannotated). */
	private final List<String> superclasses;

	/** The class of each type, in index order ({@code ""} when unannotated). */
	private final List<String> classes;

	/** The index of each type name. */
	private final Map<String, Integer> indices;

	/** The signed weight matrix, {@code [T, T]}, row = postsynaptic type. */
	private final PackedCollection weights;

	/** The number of neurons of each type, {@code [T]}. */
	private final PackedCollection typeSizes;

	/** The outgoing synapse count of each type, {@code [T]}. */
	private final PackedCollection outSynapses;

	/**
	 * Creates a connectome over loaded tensors, names and annotations, checking that they agree.
	 *
	 * @param tensors      the loaded tensors, owned from now on
	 * @param typeNames    the type names in index order
	 * @param superclasses the superclass of each type in index order
	 * @param classes      the class of each type in index order
	 * @throws IllegalArgumentException if a tensor is missing, or the shapes or annotations
	 *                                  disagree with the names
	 */
	protected CellTypeConnectome(StateDictionary tensors, List<String> typeNames,
								 List<String> superclasses, List<String> classes) {
		if (superclasses.size() != typeNames.size() || classes.size() != typeNames.size()) {
			throw new IllegalArgumentException("There are " + typeNames.size() + " types but "
					+ superclasses.size() + " superclasses and " + classes.size() + " classes");
		}

		this.tensors = tensors;
		this.typeNames = Collections.unmodifiableList(typeNames);
		this.superclasses = Collections.unmodifiableList(superclasses);
		this.classes = Collections.unmodifiableList(classes);
		this.weights = tensors.require("weights");
		this.typeSizes = tensors.require("type_sizes");
		this.outSynapses = tensors.require("type_out_synapses");

		int t = typeNames.size();
		if (!weights.getShape().equalsIgnoreAxis(shape(t, t))
				|| !typeSizes.getShape().equalsIgnoreAxis(shape(t))
				|| !outSynapses.getShape().equalsIgnoreAxis(shape(t))) {
			throw new IllegalArgumentException("Tensor shapes " + weights.getShape() + ", "
					+ typeSizes.getShape() + " and " + outSynapses.getShape()
					+ " do not match the " + t + " listed types");
		}

		this.indices = new HashMap<>();
		for (int i = 0; i < t; i++) {
			if (indices.put(typeNames.get(i), i) != null) {
				throw new IllegalArgumentException("Type " + typeNames.get(i) + " is listed twice");
			}
		}
	}

	/**
	 * Loads a connectome from the output directory of the conversion script.
	 *
	 * @param directory the directory holding {@value #WEIGHTS_FILE}, {@value #TYPES_FILE} and
	 *                  {@value #ANNOTATIONS_FILE}
	 * @return the loaded connectome
	 * @throws IOException              if a file cannot be read
	 * @throws IllegalArgumentException if a tensor is missing, the shapes disagree with the
	 *                                  number of type names, or the annotations do not list the
	 *                                  same types in the same order
	 */
	public static CellTypeConnectome load(File directory) throws IOException {
		List<String> names = Files.readAllLines(new File(directory, TYPES_FILE).toPath(), StandardCharsets.UTF_8);
		names.removeIf(String::isEmpty);

		List<String> rows = Files.readAllLines(new File(directory, ANNOTATIONS_FILE).toPath(), StandardCharsets.UTF_8);
		rows.removeIf(String::isEmpty);
		List<String> superclasses = new ArrayList<>();
		List<String> classes = new ArrayList<>();
		for (int i = 1; i < rows.size(); i++) {
			String[] fields = rows.get(i).split("\t", -1);
			if (fields.length != 3 || i > names.size() || !fields[0].equals(names.get(i - 1))) {
				throw new IllegalArgumentException("Line " + (i + 1) + " of " + ANNOTATIONS_FILE
						+ " does not annotate type " + (i - 1) + " of " + TYPES_FILE);
			}

			superclasses.add(fields[1]);
			classes.add(fields[2]);
		}

		StateDictionary tensors = new StateDictionary(directory.getPath());
		try {
			return new CellTypeConnectome(tensors, names, superclasses, classes);
		} catch (RuntimeException e) {
			tensors.destroy();
			throw e;
		}
	}

	/** Returns the number of types. */
	public int getTypeCount() { return typeNames.size(); }

	/**
	 * Returns the signed weight matrix, {@code [T, T]}, row = postsynaptic type, column =
	 * presynaptic type.
	 *
	 * @return the weight matrix, owned by this connectome
	 */
	public PackedCollection getWeights() { return weights; }

	/**
	 * Returns the number of neurons of each type, {@code [T]}.
	 *
	 * @return the type sizes, owned by this connectome
	 */
	public PackedCollection getTypeSizes() { return typeSizes; }

	/**
	 * Returns the outgoing synapse count of each type, {@code [T]}.
	 *
	 * @return the outgoing synapse counts, owned by this connectome
	 */
	public PackedCollection getOutSynapses() { return outSynapses; }

	/**
	 * Returns the type names in index order.
	 *
	 * @return an unmodifiable list of type names
	 */
	public List<String> getTypeNames() { return typeNames; }

	/**
	 * Returns the superclass of a type (for example {@code cb_sensory} or {@code cb_motor}), the
	 * most common among its members.
	 *
	 * @param index the type index
	 * @return the superclass, or {@code ""} when no member is annotated with one
	 */
	public String getSuperclass(int index) { return superclasses.get(index); }

	/**
	 * Returns the class of a type (for example {@code gustatory} or {@code olfactory}), the most
	 * common among its members.
	 *
	 * @param index the type index
	 * @return the class, or {@code ""} when no member is annotated with one
	 */
	public String getTypeClass(int index) { return classes.get(index); }

	/**
	 * Returns the index of a type.
	 *
	 * @param type the type name
	 * @return its index
	 * @throws IllegalArgumentException if there is no such type
	 */
	public int indexOf(String type) {
		Integer index = indices.get(type);
		if (index == null) {
			throw new IllegalArgumentException("No cell type named " + type);
		}

		return index;
	}

	/**
	 * Returns the indices of the types whose names satisfy a predicate, in index order.
	 *
	 * @param predicate the predicate on type names
	 * @return the matching indices
	 */
	public int[] indicesMatching(Predicate<String> predicate) {
		return IntStream.range(0, typeNames.size()).filter(i -> predicate.test(typeNames.get(i))).toArray();
	}

	/**
	 * Returns {@code 1 / type_size} for every type, the row scale that turns each row of the
	 * summed matrix into the mean input per neuron of the postsynaptic type.
	 *
	 * @return a producer of the {@code [T]} inverse type sizes
	 */
	public CollectionProducer inverseTypeSizes() {
		return c(1.0).divide(cp(typeSizes));
	}

	/** Releases the loaded tensors. */
	@Override
	public void destroy() {
		tensors.destroy();
	}
}
