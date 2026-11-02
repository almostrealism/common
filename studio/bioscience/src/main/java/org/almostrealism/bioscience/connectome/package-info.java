/**
 * Connectome representations and the dynamics that run on them, loaded as collections.
 *
 * <ul>
 *   <li>{@link org.almostrealism.bioscience.connectome.NeuronConnectome} &mdash; a connectome at
 *       the level of individual neurons: a signed synapse-count matrix in compressed sparse rows,
 *       together with the body id and annotations of every neuron.</li>
 *   <li>{@link org.almostrealism.bioscience.connectome.CellTypeConnectome} &mdash; a connectome
 *       aggregated to cell types: the dense, signed type-by-type weight matrix produced from a
 *       neuron-level connectome, with the name, size and outgoing synapse count of every type.</li>
 *   <li>{@link org.almostrealism.bioscience.connectome.ConnectomeNetwork} &mdash; a spiking
 *       network over a {@code NeuronConnectome}, every neuron an exponential-synapse neuron and
 *       every synapse a delayed, weighted connection.</li>
 * </ul>
 */
package org.almostrealism.bioscience.connectome;
