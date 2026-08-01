package org.almostrealism.texture;

/**
 * A marker interface for objects that contain multiple ordered layers of type {@code T}.
 *
 * <p>Implementations such as {@link ImageLayers} and {@link Animation} expose their
 * layers through the inherited {@link Iterable#iterator()} method.</p>
 *
 * @param <T> the type of the elements in each layer
 * @see ImageLayers
 * @see Animation
 * @author Michael Murray
 */
public interface Layered<T> extends Iterable<T> {

}
