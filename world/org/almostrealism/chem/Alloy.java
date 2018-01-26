package org.almostrealism.chem;

import org.almostrealism.heredity.Gene;
import org.almostrealism.util.ProbabilisticFactory;

import java.util.List;

public class Alloy extends ProbabilisticFactory<Atom> implements Atomic {
    public Alloy() { }

    public Alloy(List<Atomic> components, Gene<Double> composition) {
        super(components, composition);
    }
}
