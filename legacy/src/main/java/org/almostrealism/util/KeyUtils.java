package org.almostrealism.util;

import java.util.UUID;

/**
 * Utilities for generating unique identifiers.
 *
 * @author  Michael Murray
 */
public class KeyUtils {
	public static String generateKey() {
		return UUID.randomUUID().toString();
	}
}
