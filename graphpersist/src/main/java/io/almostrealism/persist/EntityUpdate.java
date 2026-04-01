package io.almostrealism.persist;

/**
 * Marker interface for an asynchronous entity update operation.
 *
 * <p>Implementations encapsulate a single database or in-memory update for an entity of
 * type {@code T}. Updates are submitted to an {@link EntityUpdateService} for sequential
 * execution on a background thread.</p>
 *
 * @param <T> The entity type that this update operates on
 */
public interface EntityUpdate<T> extends Runnable {
}
