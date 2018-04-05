package org.almostrealism.color;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import io.almostrealism.code.Scope;
import io.almostrealism.code.Variable;
import org.almostrealism.uml.Function;

@Function
public class ColorProduct extends ColorFutureAdapter {
	public ColorProduct() { }
	public ColorProduct(ColorProducer... producers) { addAll(convertToFutures(producers)); }
	public ColorProduct(Future<ColorProducer>... producers) { addAll(Arrays.asList(producers)); }
	
	@Override
	public RGB evaluate(Object[] args) {
		RGB rgb = new RGB(1.0, 1.0, 1.0);
		
		for (Future<ColorProducer> c : this) {
			try {
				rgb.multiplyBy(c.get().evaluate(args));
			} catch (InterruptedException | ExecutionException e) {
				e.printStackTrace();
			}
		}
		
		return rgb;
	}

	@Override
	public Scope<? extends Variable> getScope(String prefix) {
		throw new RuntimeException("getScope is not implemented");
	}

	// TODO  Combine ColorProducts that are equal by converting to ColorPow
	@Override
	public void compact() {
		super.compact();

		List<Future<ColorProducer>> p = new ArrayList<>();
		List<StaticColorProducer> replaced = new ArrayList<>();

		for (ColorProducer c : getStaticColorProducers()) {
			if (c instanceof ColorProduct) {
				replaced.add(new StaticColorProducer(c));

				for (Future<ColorProducer> cp : ((ColorProduct) c)) {
					p.add(cp);
				}
			}
		}

		for (StaticColorProducer s : replaced) {
			remove(replaced);
		}

		addAll(p);
	}
}
