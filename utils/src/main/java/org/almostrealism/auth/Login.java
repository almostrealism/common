package org.almostrealism.auth;

/**
 * Interface for verifying user credentials.
 *
 * <p>Implementations provide password verification logic, typically by
 * comparing against stored credentials (hashed passwords, tokens, etc.).</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * public class SimpleLogin implements Login {
 *     private Map<String, String> credentials = new HashMap<>();
 *
 *     public boolean checkPassword(String user, String password) {
 *         String storedHash = credentials.get(user);
 *         return storedHash != null && storedHash.equals(hashPassword(password));
 *     }
 * }
 * }</pre>
 *
 * @author Michael Murray
 * @see Authenticatable
 * @see AuthenticatableFactory
 */
public interface Login {

	/**
	 * Verifies that the provided password is correct for the given user.
	 *
	 * @param user     the username or identifier
	 * @param password the password to verify
	 * @return true if the password is correct, false otherwise
	 */
	public boolean checkPassword(String user, String password);
}
