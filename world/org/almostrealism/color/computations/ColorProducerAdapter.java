package org.almostrealism.color.computations;

import io.almostrealism.code.Scope;
import io.almostrealism.code.Variable;
import org.almostrealism.algebra.Triple;
import org.almostrealism.color.RGB;
import org.almostrealism.relation.NameProvider;
import org.almostrealism.relation.TripleFunction;
import org.almostrealism.util.Producer;

public abstract class ColorProducerAdapter implements ColorProducer, TripleFunction<RGB> {
	@Override
	public RGB operate(Triple in) { return evaluate(new Triple[] { in }); }

	@Override
	public void compact() { }

	@Override
	public Scope<RGB> getScope(NameProvider p) {
		Scope<RGB> s = new Scope<>();
		RGB v = operate(null); // TODO  Input?
		s.getVariables().add(new Variable<>(p.getFunctionName() + "r", v.getRed()));
		s.getVariables().add(new Variable<>(p.getFunctionName() + "g", v.getGreen()));
		s.getVariables().add(new Variable<>(p.getFunctionName() + "b", v.getBlue()));
		return s;
	}

	public static ColorProducer fromFunction(TripleFunction<RGB> t) {
		return new ColorProducer() {
			@Override
			public RGB evaluate(Object args[]) {
				return operate((Triple) args[0]);
			}

			@Override
			public RGB operate(Triple in) {
				return t.operate(in);
			}

			@Override
			public void compact() {
				if (t instanceof Producer) ((Producer) t).compact();
			}

			@Override
			public Scope<Variable> getScope(NameProvider p) {
				Scope<Variable> s = new Scope<>();
				RGB v = operate(null); // TODO  Input?
				s.getVariables().add(new Variable<>(p.getFunctionName() + "r", v.getRed()));
				s.getVariables().add(new Variable<>(p.getFunctionName() + "g", v.getGreen()));
				s.getVariables().add(new Variable<>(p.getFunctionName() + "b", v.getBlue()));
				return s;
			}
		};
	}
}
