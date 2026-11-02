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

package org.almostrealism.bioscience.connectome;

import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CompressedRowTraversalOrdering;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/**
 * A connectome at the level of individual neurons: a signed synapse-count matrix in compressed
 * sparse rows, together with the body id and annotations of every neuron.
 *
 * <p>The data is the output directory of {@code studio/bioscience/scripts/connectome_csr.py},
 * which reduces MaleCNS, the FlyWire snapshots distributed with Shiu et al. (2024) and a small
 * synthetic dataset to one form:</p>
 * <ul>
 *   <li>{@value #TENSORS_FILE}, read through {@link StateDictionary}, holding
 *       {@code row_offsets} ({@code [N + 1]}), {@code presynaptic} ({@code [E]}, the presynaptic
 *       neuron of each entry), {@code weights} ({@code [E]}, the presynaptic neuron's sign times
 *       the synapse count) and {@code signs} ({@code [N]}, +1 or -1 per neuron);</li>
 *   <li>{@value #NEURONS_FILE}, a header line followed by the body id, type, superclass, class
 *       and predicted transmitter of each neuron, tab-separated, in index order.</li>
 * </ul>
 *
 * <p>Rows are postsynaptic neurons. {@link #getWeights()} is therefore an {@code [N, N]}
 * collection whose shape carries a {@link CompressedRowTraversalOrdering} and whose memory holds
 * only the {@code E} stored entries, so {@code matmul(cp(getWeights()), spikes)} is the summed
 * signed synapse count arriving at every neuron, computed as a sparse product. A model scales it
 * by its weight per synapse.</p>
 *
 * <p>This type knows the file layout and nothing about dynamics. It owns the loaded tensors and
 * the index collections of the ordering, and releases them in {@link #destroy()}.</p>
 *
 * @see CellTypeConnectome
 * @author Michael Murray
 */
public class NeuronConnectome implements CollectionFeatures, Destroyable {
	/** The safetensors file holding the compressed rows and the signs. */
	public static final String TENSORS_FILE = "connectome.safetensors";

	/** The file listing each neuron's body id and annotations in index order. */
	public static final String NEURONS_FILE = "neurons.tsv";

	/** The columns of {@link #NEURONS_FILE}, in order. */
	private static final String[] NEURON_COLUMNS = { "body_id", "type", "superclass", "class", "transmitter" };

	/** The loaded tensors. */
	private final StateDictionary tensors;

	/** The body id of each neuron, in index order. */
	private final long[] bodyIds;

	/** The annotations of each neuron in index order: type, superclass, class and transmitter. */
	private final List<String[]> annotations;

	/** The index of each body id. */
	private final Map<Long, Integer> indices;

	/** The compressed-sparse-row ordering of the weight matrix. */
	private final CompressedRowTraversalOrdering ordering;

	/** The signed weight matrix, {@code [N, N]}, read through {@link #ordering}. */
	private final PackedCollection weights;

	/** The sign of each neuron, {@code [N]}. */
	private final PackedCollection signs;

	/**
	 * Creates a connectome over loaded tensors and the neuron table, checking that they agree.
	 *
	 * @param tensors     the loaded tensors, owned from now on
	 * @param bodyIds     the body id of each neuron in index order
	 * @param annotations the type, superclass, class and transmitter of each neuron in index order
	 * @throws IllegalArgumentException if a tensor is missing, the compressed rows are invalid, the
	 *                                  shapes disagree with the number of neurons, or a body id is
	 *                                  listed twice
	 */
	protected NeuronConnectome(StateDictionary tensors, long[] bodyIds, List<String[]> annotations) {
		int n = bodyIds.length;
		if (annotations.size() != n) {
			throw new IllegalArgumentException("There are " + n + " body ids but "
					+ annotations.size() + " annotated neurons");
		}

		PackedCollection rowOffsets = tensors.require("row_offsets");
		PackedCollection presynaptic = tensors.require("presynaptic");
		PackedCollection values = tensors.require("weights");
		this.signs = tensors.require("signs");
		if (!rowOffsets.getShape().equalsIgnoreAxis(shape(n + 1))
				|| !signs.getShape().equalsIgnoreAxis(shape(n))
				|| presynaptic.getShape().getTotalSize() != values.getShape().getTotalSize()) {
			throw new IllegalArgumentException("Tensor shapes " + rowOffsets.getShape() + ", "
					+ presynaptic.getShape() + ", " + values.getShape() + " and " + signs.getShape()
					+ " do not match the " + n + " listed neurons");
		}

		this.tensors = tensors;
		this.bodyIds = bodyIds;
		this.annotations = Collections.unmodifiableList(annotations);

		// The body ids are validated before the ordering allocates its index collections, so a
		// duplicate id cannot leave those native buffers behind when it rejects the file
		this.indices = new HashMap<>();
		for (int i = 0; i < n; i++) {
			if (indices.put(bodyIds[i], i) != null) {
				throw new IllegalArgumentException("Body " + bodyIds[i] + " is listed twice");
			}
		}

		this.ordering = CompressedRowTraversalOrdering.of(n, rowOffsets, presynaptic);
		this.weights = new PackedCollection(shape(n, n), 0, values, 0, ordering);
	}

	/**
	 * Loads a connectome from the output directory of the conversion script.
	 *
	 * @param directory the directory holding {@value #TENSORS_FILE} and {@value #NEURONS_FILE}
	 * @return the loaded connectome
	 * @throws IOException              if a file cannot be read
	 * @throws IllegalArgumentException if the neuron table is malformed, a tensor is missing, or
	 *                                  the tensors disagree with the neuron table
	 */
	public static NeuronConnectome load(File directory) throws IOException {
		List<String> rows = Files.readAllLines(new File(directory, NEURONS_FILE).toPath(), StandardCharsets.UTF_8);
		rows.removeIf(String::isEmpty);
		if (rows.isEmpty() || !Arrays.equals(rows.get(0).split("\t", -1), NEURON_COLUMNS)) {
			throw new IllegalArgumentException(NEURONS_FILE + " must begin with the header "
					+ String.join("\t", NEURON_COLUMNS));
		}

		long[] bodyIds = new long[rows.size() - 1];
		List<String[]> annotations = new ArrayList<>();
		for (int i = 1; i < rows.size(); i++) {
			String[] fields = rows.get(i).split("\t", -1);
			if (fields.length != NEURON_COLUMNS.length) {
				throw new IllegalArgumentException("Line " + (i + 1) + " of " + NEURONS_FILE
						+ " has " + fields.length + " fields, not " + NEURON_COLUMNS.length);
			}

			bodyIds[i - 1] = Long.parseLong(fields[0]);
			annotations.add(Arrays.copyOfRange(fields, 1, fields.length));
		}

		StateDictionary tensors = new StateDictionary(directory.getPath());
		try {
			return new NeuronConnectome(tensors, bodyIds, annotations);
		} catch (RuntimeException e) {
			tensors.destroy();
			throw e;
		}
	}

	/** Returns the number of neurons. */
	public int getNeuronCount() { return bodyIds.length; }

	/** Returns the number of stored edges. */
	public int getEdgeCount() { return ordering.getLength().orElse(0); }

	/**
	 * Returns the signed weight matrix, {@code [N, N]}, row = postsynaptic neuron, column =
	 * presynaptic neuron, each entry the presynaptic sign times the synapse count. Its shape
	 * carries the compressed-sparse-row ordering, so a product with it is a sparse product.
	 *
	 * @return the weight matrix, owned by this connectome
	 */
	public PackedCollection getWeights() { return weights; }

	/**
	 * Returns the compressed-sparse-row ordering of the weight matrix.
	 *
	 * @return the ordering, owned by this connectome
	 */
	public CompressedRowTraversalOrdering getOrdering() { return ordering; }

	/**
	 * Returns the sign of each neuron, {@code [N]}: -1 for an inhibitory neuron, +1 otherwise.
	 *
	 * @return the signs, owned by this connectome
	 */
	public PackedCollection getSigns() { return signs; }

	/**
	 * Returns the body id of a neuron.
	 *
	 * @param index the neuron index
	 * @return its body id in the source dataset
	 */
	public long getBodyId(int index) { return bodyIds[index]; }

	/**
	 * Returns the cell type of a neuron.
	 *
	 * @param index the neuron index
	 * @return the type, or {@code ""} when the dataset does not annotate one
	 */
	public String getType(int index) { return annotations.get(index)[0]; }

	/**
	 * Returns the superclass of a neuron (for example {@code cb_sensory}).
	 *
	 * @param index the neuron index
	 * @return the superclass, or {@code ""} when the dataset does not annotate one
	 */
	public String getSuperclass(int index) { return annotations.get(index)[1]; }

	/**
	 * Returns the class of a neuron (for example {@code gustatory}).
	 *
	 * @param index the neuron index
	 * @return the class, or {@code ""} when the dataset does not annotate one
	 */
	public String getNeuronClass(int index) { return annotations.get(index)[2]; }

	/**
	 * Returns the predicted neurotransmitter of a neuron.
	 *
	 * @param index the neuron index
	 * @return the transmitter, or {@code ""} when the dataset does not predict one
	 */
	public String getTransmitter(int index) { return annotations.get(index)[3]; }

	/**
	 * Returns the index of the neuron with a body id.
	 *
	 * @param bodyId the body id in the source dataset
	 * @return the neuron index
	 * @throws IllegalArgumentException if no neuron has that body id
	 */
	public int indexOf(long bodyId) {
		Integer index = indices.get(bodyId);
		if (index == null) {
			throw new IllegalArgumentException("No neuron with body id " + bodyId);
		}

		return index;
	}

	/**
	 * Returns the indices of the neurons with the given body ids, in the order given.
	 *
	 * @param bodyIds the body ids
	 * @return the neuron indices
	 * @throws IllegalArgumentException if a body id is not in the connectome
	 */
	public int[] indicesOf(long... bodyIds) {
		return Arrays.stream(bodyIds).mapToInt(this::indexOf).toArray();
	}

	/**
	 * Returns the indices of the neurons whose type satisfies a predicate, in index order.
	 *
	 * @param predicate the predicate on type names
	 * @return the matching indices
	 */
	public int[] indicesOfType(Predicate<String> predicate) {
		return IntStream.range(0, bodyIds.length).filter(i -> predicate.test(getType(i))).toArray();
	}

	/**
	 * Returns a mask over the neurons, {@code [N]}, holding 1 at each given index and 0 elsewhere,
	 * as a population's input set expects: the sum of one comparison of the neuron indices with
	 * each selected index. Each selected index enters the graph as a scalar constant, so the mask
	 * is suited to the handful of neurons an experiment stimulates or records.
	 *
	 * @param indices the distinct neuron indices to select
	 * @return a producer of the mask
	 * @throws IllegalArgumentException if no index is given, an index is outside the connectome,
	 *                                  or an index is given twice
	 */
	public CollectionProducer mask(int... indices) {
		if (indices.length == 0) {
			throw new IllegalArgumentException("A mask selects at least one neuron");
		} else if (Arrays.stream(indices).anyMatch(i -> i < 0 || i >= bodyIds.length)) {
			throw new IllegalArgumentException("A neuron index is outside [0, " + bodyIds.length + ")");
		} else if (Arrays.stream(indices).distinct().count() != indices.length) {
			throw new IllegalArgumentException("A neuron index is given twice");
		}

		CollectionProducer mask = null;
		for (int index : indices) {
			CollectionProducer selected = equals(integers(0, bodyIds.length), c((double) index), c(1.0), c(0.0));
			mask = mask == null ? selected : mask.add(selected);
		}

		return mask;
	}

	/** Releases the loaded tensors and the index collections of the ordering. */
	@Override
	public void destroy() {
		List<Runnable> releases = new ArrayList<>();
		releases.add(tensors::destroy);
		releases.add(ordering.getRowOffsets()::destroy);
		releases.add(ordering.getColumns()::destroy);
		Destroyable.releaseAll(releases);
	}
}
