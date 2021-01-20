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

package org.almostrealism.chem;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.heredity.ArrayListGene;
import org.almostrealism.heredity.Gene;
import org.almostrealism.physics.Atom;
import org.almostrealism.heredity.ProbabilisticFactory;

import java.util.List;

public class Alloy extends ProbabilisticFactory<Atom> implements Atomic {
    public Alloy() { }

    public Alloy(List<Atomic> components, double... g) {
        this(components, new ArrayListGene<>(g));
    }

    public Alloy(List<Atomic> components, Gene<Scalar> composition) {
        super(components, composition);
    }
}
