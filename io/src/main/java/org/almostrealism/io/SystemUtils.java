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

public class SystemUtils {
	private SystemUtils() { }

	public static boolean isAarch64() {
		return "aarch64".equals(System.getProperty("os.arch"));
	}

	public static boolean isMacOS() {
		return System.getProperty("os.name", "").contains("Mac OS X");
	}

	public static String getProperty(String key) {
		String value = System.getProperty(key);
		if (value == null) {
			value = System.getenv(key);
		}

		return value;
	}

	public static String getProperty(String key, String defaultValue) {
		String value = getProperty(key);
		if (value == null) {
			value = defaultValue;
		}

		return value;
	}

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

	public static String getHome() {
		return System.getProperty("user.home");
	}

	public static Optional<String> getMacApp() {
		if (!isMacOS()) return Optional.empty();
		return Optional.ofNullable(getProperty("AR_MAC_APP"));
	}

	public static Optional<String> getAppleTeam() {
		if (!isMacOS()) return Optional.empty();
		return Optional.ofNullable(getProperty("AR_APPLE_TEAM"));
	}

	public static String getLocalDestination(String... pathElements) {
		Path path = getLocalDestination();

		for (String element : pathElements) {
			path = path.resolve(element);
		}

		return path.toString();
	}

	public static Path getLocalDestination() {
		return getMacApp().map(SystemUtils::getAppSupportPath)
				.orElse(Path.of("."));
	}

	public static String getSharedDestination(String file) {
		return getSharedDestination().resolve(file).toString();
	}

	public static Path getSharedDestination() {
		return getMacApp().map(s -> SystemUtils.getSharedContainer(getAppleTeam()
						.orElseThrow(() -> new RuntimeException("AR_APPLE_TEAM not set")) + "." + s))
				.orElseGet(() -> Path.of("."));
	}

	public static Path getExtensionsPath() {
		if (!isMacOS()) return null;

		return getMacApp().map(SystemUtils::getCachesPath)
				.map(p -> p.resolve("Extensions"))
				.orElse(Path.of(getHome())
						.resolve("Library")
						.resolve("Java")
						.resolve("Extensions"));
	}

	public static Path getAppSupportPath(String appName) {
		Path path = Paths.get(getHome(), "Library", "Application Support", appName);
		ensureDirectoryExists(path);
		return path;
	}

	public static Path getSharedContainer(String containerName) {
		Path path = Paths.get(getHome(), "Library", "Group Containers", containerName);
		ensureDirectoryExists(path);
		return path;
	}

	public static Path getCachesPath(String appName) {
		Path path = Paths.get(getHome(), "Library", "Caches", appName);
		ensureDirectoryExists(path);
		return path;
	}

	public static File download(String url, String dest) {
		return download(url, dest, null);
	}

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
