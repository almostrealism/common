package org.almostrealism.auth;

/**
 * Factory interface for creating authenticated instances of {@link Authenticatable} objects.
 *
 * <p>Implementations of this interface handle the authentication process,
 * verifying credentials and returning authenticated instances when valid.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * public class UserFactory implements AuthenticatableFactory<User> {
 *     private UserRepository repository;
 *
 *     public void init() {
 *         // Initialize database connection, etc.
 *     }
 *
 *     public User getAuthenticatable(String identifier, String password) {
 *         User user = repository.findByUsername(identifier);
 *         if (user != null && user.checkPassword(password)) {
 *             return user;
 *         }
 *         return null;
 *     }
 * }
 * }</pre>
 *
 * @param <T> the type of authenticatable object this factory creates
 * @author Michael Murray
 * @see Authenticatable
 */
public interface AuthenticatableFactory<T extends Authenticatable> {

	/**
	 * Initializes this factory. Should be called before any authentication attempts.
	 * Implementations may use this to set up database connections, load credentials, etc.
	 */
	public void init();

	/**
	 * Attempts to authenticate and return an {@link Authenticatable} instance.
	 *
	 * @param identifier the user identifier (e.g., username, email)
	 * @param password   the password or authentication token
	 * @return the authenticated instance, or null if authentication fails
	 */
	public T getAuthenticatable(String identifier, String password);
}
