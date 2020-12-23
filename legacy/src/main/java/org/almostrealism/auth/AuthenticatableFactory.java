package org.almostrealism.auth;

public interface AuthenticatableFactory<T extends Authenticatable> {
	public void init();
	
	public T getAuthenticatable(String identifier, String password);
}
