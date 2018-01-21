package org.almostrealism.util;

import java.util.HashMap;
import java.util.List;

import org.almostrealism.heredity.DoubleScaleFactor;
import org.almostrealism.heredity.Factor;
import org.almostrealism.heredity.Gene;

public class ProbabilisticFactory<V> extends HashMap<Factory<V>, Double> implements Factory<V> {
	/** Constructs an empty {@link ProbabilisticFactory}. */
	public ProbabilisticFactory() { }
	
	/**
	 * Constructs a {@link ProbabilisticFactory} with the specified factories, where
	 * probabilities are determined by evaluating the {@link Factor}s in the specified
	 * {@link Gene}. The value 1.0 is used as an argument, making it convenient to use
	 * the {@link DoubleScaleFactor} to specify scalar probability values.
	 */
	public ProbabilisticFactory(List<Factory<V>> factories, Gene<Double> probabilities) {
		for (int i = 0; i < factories.size(); i++) {
			put(factories.get(i), probabilities.getFactor(i).getResultant(1.0));
		}
	}
	
	@Override
	public V construct() {
		double r = Defaults.random.nextDouble();
		double p = 0;
		
		for (Entry<Factory<V>, Double> e : entrySet()) {
			p += e.getValue();
			if (p > r) return e.getKey().construct();
		}
		
		return null;
	}
	
	protected double total() {
		double tot = 0;
		for (double d : values()) tot += d;
		return tot;
	}
}
