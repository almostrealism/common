package org.almostrealism.util;

import io.almostrealism.relation.Factory;

/**
 * A factory that can be configured with typed parameters before creating objects.
 *
 * <p>This interface extends {@link Factory} to add the ability to set typed
 * parameters that influence how objects are created. Parameters are identified
 * by their class type, allowing type-safe configuration.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * public class ConfiguredModelFactory implements ParameterizedFactory<Object, Model> {
 *     private int hiddenSize;
 *
 *     public <A> void setParameter(Class<A> param, A value) {
 *         if (param == Integer.class) {
 *             this.hiddenSize = (Integer) value;
 *         }
 *     }
 *
 *     public Model construct() {
 *         return new Model(hiddenSize);
 *     }
 * }
 * }</pre>
 *
 * @param <T> the base type for parameter values
 * @param <V> the type of objects created by this factory
 * @author Michael Murray
 */
public interface ParameterizedFactory<T, V> extends Factory<V> {

	/**
	 * Sets a typed parameter for this factory.
	 *
	 * @param <A>   the specific parameter type (must extend T)
	 * @param param the class representing the parameter type
	 * @param value the parameter value
	 */
	<A extends T> void setParameter(Class<A> param, A value);
}
