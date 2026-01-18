/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.chem;

import io.almostrealism.relation.Graph;
import org.almostrealism.physics.Element;
import org.almostrealism.physics.Substance;

import java.util.Collection;

/**
 * Represents a chemical molecule as a graph of connected elements.
 *
 * <p>A {@code Molecule} is a chemical substance consisting of two or more
 * {@link Element}s held together by chemical bonds. This interface models
 * molecules as graphs where elements are nodes and chemical bonds are edges,
 * enabling graph-based algorithms for molecular structure analysis.</p>
 *
 * <p>This interface extends both {@link Substance} (marking it as a chemical
 * substance) and {@link Graph} (providing graph traversal capabilities).</p>
 *
 * <h2>Current Limitations</h2>
 * <p>This interface is currently minimally implemented. The default implementations
 * of graph methods throw {@link UnsupportedOperationException} or return stub values.
 * Future implementations should override these methods to provide actual molecular
 * structure information.</p>
 *
 * <h2>Intended Usage (Future)</h2>
 * <pre>{@code
 * // Example: Water molecule (H2O) - conceptual implementation
 * Molecule water = new WaterMolecule();
 * int atomCount = water.countNodes();  // Should return 3 (2 H + 1 O)
 *
 * // Get neighbors of oxygen atom
 * Collection<Element> hydrogens = water.neighbors(PeriodicTable.Oxygen);
 * }</pre>
 *
 * @see Substance
 * @see Element
 * @see Hydrocarbon
 * @see io.almostrealism.relation.Graph
 *
 * @author Michael Murray
 */
public interface Molecule extends Substance, Graph<Element> {

	/**
	 * Returns the elements chemically bonded to the specified element in this molecule.
	 *
	 * <p><b>Note:</b> This default implementation throws {@link UnsupportedOperationException}.
	 * Concrete implementations should override this method to return the actual
	 * neighboring elements based on the molecular structure.</p>
	 *
	 * @param node the element whose neighbors are to be returned
	 * @return a collection of elements bonded to the specified element
	 * @throws UnsupportedOperationException always (default implementation)
	 */
	@Override
	default Collection<Element> neighbors(Element node) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns the total number of atoms (element instances) in this molecule.
	 *
	 * <p><b>Note:</b> This default implementation returns 0. Concrete implementations
	 * should override this method to return the actual atom count.</p>
	 *
	 * @return the number of atoms in this molecule (default returns 0)
	 */
	@Override
	default int countNodes() {
		return 0;
	}
}
