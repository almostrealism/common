package org.almostrealism.auth;

/**
 * Marker interface for objects that require authentication.
 *
 * <p>Classes implementing this interface indicate that they represent
 * entities that can be authenticated (e.g., users, services, or sessions).
 * This interface is typically used in conjunction with {@link AuthenticatableFactory}
 * for creating authenticated instances.</p>
 *
 * @author Michael Murray
 * @see AuthenticatableFactory
 * @see Login
 */
public interface Authenticatable {
}
