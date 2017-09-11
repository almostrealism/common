package org.almostrealism.color;

import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

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
}
