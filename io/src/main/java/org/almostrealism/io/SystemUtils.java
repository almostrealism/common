/*
 * Copyright 2022 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.io;

import io.almostrealism.uml.Signature;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Utility class for system-level operations and environment configuration.
 *
 * <p>SystemUtils provides methods for:</p>
 * <ul>
 *   <li>Platform detection (macOS, ARM64, etc.)</li>
 *   <li>Property and environment variable access with fallback</li>
 *   <li>macOS-specific paths (Application Support, Caches, Shared Containers)</li>
 *   <li>File downloading with optional MD5 verification</li>
 *   <li>Directory creation utilities</li>
 * </ul>
 *
 * <h2>Property Access</h2>
 * <p>The {@link #getProperty(String)} method checks both system properties and
 * environment variables, preferring system properties. This allows configuration
 * via either JVM arguments or environment:</p>
 * <pre>{@code
 * // Set via -DAR_HARDWARE_LIBS=/tmp or AR_HARDWARE_LIBS=/tmp (env)
 * String libs = SystemUtils.getProperty("AR_HARDWARE_LIBS");
 *
 * // Check if a feature is enabled/disabled
 * Optional<Boolean> enabled = SystemUtils.isEnabled("AR_FEATURE");
 * }</pre>
 *
 * <h2>macOS Support</h2>
 * <p>Special support for macOS app sandboxing and shared containers:</p>
 * <pre>{@code
 * // Get app-specific paths
 * Path appSupport = SystemUtils.getAppSupportPath("MyApp");
 * Path caches = SystemUtils.getCachesPath("MyApp");
 * Path shared = SystemUtils.getSharedContainer("TEAM.MyApp");
 * }</pre>
 *
 * @see Console
 */
public class SystemUtils {
	private SystemUtils() { }

	/**
	 * Returns true if running on ARM64 (aarch64) architecture.
	 *
	 * @return true for ARM64 processors (e.g., Apple Silicon)
	 */
	public static boolean isAarch64() {
		return "aarch64".equals(System.getProperty("os.arch"));
	}

	/**
	 * Returns true if running on macOS.
	 *
	 * @return true for macOS systems
	 */
	public static boolean isMacOS() {
		return System.getProperty("os.name", "").contains("Mac OS X");
	}

	/**
	 * Gets a property value, checking system properties first, then environment variables.
	 *
	 * @param key the property key
	 * @return the property value, or null if not found
	 */
	public static String getProperty(String key) {
		String value = System.getProperty(key);
		if (value == null) {
			value = System.getenv(key);
		}

		return value;
	}

	/**
	 * Gets a property value with a default fallback.
	 *
	 * @param key the property key
	 * @param defaultValue the default value if property is not found
	 * @return the property value, or defaultValue if not found
	 */
	public static String getProperty(String key, String defaultValue) {
		String value = getProperty(key);
		if (value == null) {
			value = defaultValue;
		}

		return value;
	}

	/**
	 * Gets a property value as an integer.
	 *
	 * @param key the property key
	 * @return an OptionalInt containing the parsed value, or empty if not found or invalid
	 */
	public static OptionalInt getInt(String key) {
		String value = getProperty(key);
		if (value == null) {
			return OptionalInt.empty();
		}

		try {
			return OptionalInt.of(Integer.parseInt(value));
		} catch (NumberFormatException e) {
			Console.root().warn("Invalid value for " + key + ": " + value);
			return OptionalInt.empty();
		}
	}

	/**
	 * Checks if a feature is enabled or disabled via property.
	 * <p>The property should have value "enabled" or "disabled" (case-insensitive).</p>
	 *
	 * @param key the property key
	 * @return Optional.of(true) if enabled, Optional.of(false) if disabled, empty if unset
	 */
	public static Optional<Boolean> isEnabled(String key) {
		String value = getProperty(key);

		if ("enabled".equalsIgnoreCase(value)) {
			return Optional.of(true);
		} else if ("disabled".equalsIgnoreCase(value)) {
			return Optional.of(false);
		} else {
			return Optional.empty();
		}
	}

	/**
	 * Returns the user's home directory.
	 *
	 * @return the user.home system property value
	 */
	public static String getHome() {
		return System.getProperty("user.home");
	}

	/**
	 * Returns the macOS app identifier from the AR_MAC_APP property.
	 *
	 * @return the app identifier, or empty if not on macOS or property not set
	 */
	public static Optional<String> getMacApp() {
		if (!isMacOS()) return Optional.empty();
		return Optional.ofNullable(getProperty("AR_MAC_APP"));
	}

	/**
	 * Returns the Apple Team ID from the AR_APPLE_TEAM property.
	 *
	 * @return the team ID, or empty if not on macOS or property not set
	 */
	public static Optional<String> getAppleTeam() {
		if (!isMacOS()) return Optional.empty();
		return Optional.ofNullable(getProperty("AR_APPLE_TEAM"));
	}

	/**
	 * Constructs a local destination path from path elements.
	 *
	 * @param pathElements the path elements to append
	 * @return the full path as a string
	 */
	public static String getLocalDestination(String... pathElements) {
		Path path = getLocalDestination();

		for (String element : pathElements) {
			path = path.resolve(element);
		}

		return path.toString();
	}

	/**
	 * Returns the local storage destination for the application.
	 * On macOS with AR_MAC_APP set, returns Application Support; otherwise returns current directory.
	 *
	 * @return the local destination path
	 */
	public static Path getLocalDestination() {
		return getMacApp().map(SystemUtils::getAppSupportPath)
				.orElse(Path.of("."));
	}

	/**
	 * Constructs a shared destination path for the specified file.
	 *
	 * @param file the filename to append
	 * @return the full path as a string
	 */
	public static String getSharedDestination(String file) {
		return getSharedDestination().resolve(file).toString();
	}

	/**
	 * Returns the shared storage destination for the application.
	 * On macOS, returns the shared container path; otherwise returns current directory.
	 *
	 * @return the shared destination path
	 * @throws RuntimeException if AR_MAC_APP is set but AR_APPLE_TEAM is not
	 */
	public static Path getSharedDestination() {
		return getMacApp().map(s -> SystemUtils.getSharedContainer(getAppleTeam()
						.orElseThrow(() -> new RuntimeException("AR_APPLE_TEAM not set")) + "." + s))
				.orElseGet(() -> Path.of("."));
	}

	/**
	 * Returns the path for Java extensions on macOS.
	 *
	 * @return the extensions path, or null if not on macOS
	 */
	public static Path getExtensionsPath() {
		if (!isMacOS()) return null;

		return getMacApp().map(SystemUtils::getCachesPath)
				.map(p -> p.resolve("Extensions"))
				.orElse(Path.of(getHome())
						.resolve("Library")
						.resolve("Java")
						.resolve("Extensions"));
	}

	/**
	 * Returns the Application Support path for the specified app on macOS.
	 * Creates the directory if it does not exist.
	 *
	 * @param appName the application name
	 * @return the Application Support path
	 */
	public static Path getAppSupportPath(String appName) {
		Path path = Paths.get(getHome(), "Library", "Application Support", appName);
		ensureDirectoryExists(path);
		return path;
	}

	/**
	 * Returns the shared container path for the specified container on macOS.
	 * Creates the directory if it does not exist.
	 *
	 * @param containerName the container identifier (typically TeamID.AppID)
	 * @return the shared container path
	 */
	public static Path getSharedContainer(String containerName) {
		Path path = Paths.get(getHome(), "Library", "Group Containers", containerName);
		ensureDirectoryExists(path);
		return path;
	}

	/**
	 * Returns the Caches path for the specified app on macOS.
	 * Creates the directory if it does not exist.
	 *
	 * @param appName the application name
	 * @return the Caches path
	 */
	public static Path getCachesPath(String appName) {
		Path path = Paths.get(getHome(), "Library", "Caches", appName);
		ensureDirectoryExists(path);
		return path;
	}

	/**
	 * Downloads a file from a URL to the specified destination.
	 *
	 * @param url the URL to download from
	 * @param dest the destination file path
	 * @return the downloaded File, or null if download failed
	 */
	public static File download(String url, String dest) {
		return download(url, dest, null);
	}

	/**
	 * Downloads a file from a URL with optional MD5 verification.
	 *
	 * @param url the URL to download from
	 * @param dest the destination file path
	 * @param expectedMd5 the expected MD5 hash (hex string), or null to skip verification
	 * @return the downloaded File, or null if download failed
	 */
	public static File download(String url, String dest, String expectedMd5) {
		try {
			File destFile = new File(dest);
			URL fileUrl = new URL(url);
			URLConnection conn = fileUrl.openConnection();

			MessageDigest md = expectedMd5 == null ? null : MessageDigest.getInstance("MD5");

			try (InputStream is = conn.getInputStream();
					OutputStream os = new FileOutputStream(dest)) {
				byte[] buffer = new byte[8192]; int length;

				while ((length = is.read(buffer)) != -1) {
					os.write(buffer, 0, length);

					if (md != null) {
						md.update(buffer, 0, length);
					}
				}
			}

			if (md != null) {
				String md5 = Signature.hex(md.digest());

				if (!md5.equals(expectedMd5)) {
					Console.root().features(SystemUtils.class)
							.warn("MD5 mismatch for " + url + " (" + md5 + " != " + expectedMd5 + ")");
				}
			}

			return destFile;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Computes the MD5 hash of a file.
	 *
	 * @param location the file to hash
	 * @return the MD5 hash as a hex string, or null if an error occurs
	 */
	public static String md5(File location) {
		try {
			MessageDigest md = MessageDigest.getInstance("MD5");

			FileInputStream fis = new FileInputStream(location);
			byte[] buffer = new byte[8192];
			int length;
			while ((length = fis.read(buffer)) != -1) {
				md.update(buffer, 0, length);
			}
			fis.close();

			return Signature.hex(md.digest());
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Ensures that a directory exists, creating it and parent directories if needed.
	 *
	 * @param path the directory path to ensure exists
	 * @return the same path
	 * @throws RuntimeException if directory creation fails
	 */
	public static Path ensureDirectoryExists(Path path) {
		try {
			if (!Files.exists(path)) {
				Files.createDirectories(path);
			}
		} catch (Exception e) {
			throw new RuntimeException("Failed to create " + path, e);
		}

		return path;
	}
}
