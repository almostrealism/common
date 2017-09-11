package io.almostrealism.auth;

public interface AuthenticatableFactory<T extends Authenticatable> {
	public void init();
	
	public T getAuthenticatable(String identifier);
}
