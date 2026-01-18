/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.data;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Filter for selecting audio files based on path or name patterns.
 *
 * <p>FileWaveDataProviderFilter provides flexible matching criteria for filtering
 * audio files in a {@link FileWaveDataProviderTree}. Filters can match against
 * file names or paths using various comparison operations.</p>
 *
 * <h2>Filter Options</h2>
 * <ul>
 *   <li><b>FilterOn</b>: What to match against (NAME or PATH)</li>
 *   <li><b>FilterType</b>: How to match (EQUALS, STARTS_WITH, ENDS_WITH, CONTAINS, etc.)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Filter for files starting with "kick"
 * FileWaveDataProviderFilter filter = FileWaveDataProviderFilter.nameStartsWith("kick");
 *
 * // Check if a provider matches
 * if (filter.matches(tree, provider)) {
 *     // Process matching file
 * }
 * }</pre>
 *
 * @see FileWaveDataProviderTree
 * @see FileWaveDataProvider
 */
public class FileWaveDataProviderFilter {
	public static final String[] SEPARATORS = {" ", "-", "_", "."};

	private FilterOn filterOn;
	private FilterType filterType;
	private String filter;

	public FileWaveDataProviderFilter() { }

	public FileWaveDataProviderFilter(FilterOn filterOn, FilterType filterType, String filter) {
		this.filterOn = filterOn;
		this.filterType = filterType;
		this.filter = filter;
	}

	public FilterOn getFilterOn() { return filterOn; }
	public void setFilterOn(FilterOn filterOn) { this.filterOn = filterOn; }

	public FilterType getFilterType() { return filterType; }
	public void setFilterType(FilterType filterType) { this.filterType = filterType; }

	public String getFilter() { return filter; }
	public void setFilter(String filter) { this.filter = filter; }

	public boolean matches(FileWaveDataProviderTree tree, FileWaveDataProvider p) {
		return getFilterType().matches(getFilterOn().select(tree, p), getFilter());
	}

	public String coerceToMatch(String value, boolean allowEqual) {
		return getFilterType().coerceToMatch(value, getFilter(), allowEqual);
	}

	public Path coerceToMatch(Path relativePath) {
		String selected = getFilterOn().select(relativePath);
		String result = coerceToMatch(selected, getFilterOn() == FilterOn.PATH);
		System.out.println("FileWaveDataProviderFilter[" + relativePath +
				"]: Coerced " + selected + " to " + result);

		if (getFilterOn() == FilterOn.PATH) {
			return new File(result).toPath()
					.resolve(relativePath.toFile().getName());
		} else if (getFilterOn() == FilterOn.NAME) {
			Path parent = relativePath.getParent();
			return parent == null ? Path.of(result) : parent.resolve(result);
		} else {
			throw new UnsupportedOperationException();
		}
	}

	public static FileWaveDataProviderFilter nameStartsWith(String prefix) {
		return new FileWaveDataProviderFilter(FilterOn.NAME, FilterType.STARTS_WITH, prefix);
	}

	public enum FilterOn {
		PATH, NAME;

		String select(FileWaveDataProviderTree tree, FileWaveDataProvider p) {
			switch (this) {
				case NAME:
					return new File(p.getResourcePath()).getName();
				case PATH:
					return stripSlash(new File(tree.getRelativePath(p.getResourcePath())).getParentFile().getPath());
				default:
					throw new IllegalArgumentException();
			}
		}

		String select(Path relativePath) {
			return select(relativePath.toFile());
		}

		String select(File relativeFile) {
			switch (this) {
				case NAME:
					return relativeFile.getName();
				case PATH:
					return Optional.ofNullable(relativeFile.getParentFile())
							.map(File::getPath).orElse(null);
				default:
					throw new IllegalArgumentException();
			}
		}

		public String readableName() {
			switch (this) {
				case NAME:
					return "File Name";
				case PATH:
					return "File Path";
				default:
					throw new IllegalArgumentException();
			}
		}

		public static String stripSlash(String value) {
			if (value != null && value.startsWith("/")) {
				return value.substring(1);
			} else {
				return value;
			}
		}

		public static FilterOn fromReadableName(String name) {
			switch (name) {
				case "File Name":
					return NAME;
				case "File Path":
					return PATH;
				default:
					return null;
			}
		}
	}

	public enum FilterType {
		EQUALS, EQUALS_IGNORE_CASE, STARTS_WITH, ENDS_WITH, CONTAINS, CONTAINS_IGNORE_CASE;

		boolean matches(String value, String filter) {
			if (value == null || filter == null || filter.isEmpty()) return false;

			switch (this) {
				case EQUALS:
					return value.equals(filter);
				case EQUALS_IGNORE_CASE:
					return value.equalsIgnoreCase(filter);
				case STARTS_WITH:
					return value.startsWith(filter);
				case ENDS_WITH:
					return value.endsWith(filter);
				case CONTAINS:
					return value.contains(filter);
				case CONTAINS_IGNORE_CASE:
					return value.toLowerCase().contains(filter.toLowerCase());
				default:
					throw new IllegalArgumentException();
			}
		}

		String coerceToMatch(String value, String filter, boolean allowEqual) {
			if (filter == null || filter.isEmpty() || matches(value, filter)) return value;


			FilterType type = this;

			if (value == null) {
				// If there is no value, the only coercion
				// that is possible is direct assignment
				type = FilterType.EQUALS;
			}

			switch (type) {
				case STARTS_WITH:
				case CONTAINS:
				case CONTAINS_IGNORE_CASE:
					return paddedJoin(filter, value);
				case ENDS_WITH:
					return paddedJoin(value, filter);
				case EQUALS:
				case EQUALS_IGNORE_CASE:
					return allowEqual ? filter : value;
				default:
					throw new IllegalArgumentException();
			}
		}

		public String readableName() {
			switch (this) {
				case EQUALS:
					return "Exactly Matches";
				case EQUALS_IGNORE_CASE:
					return "Matches (Case Insensitive)";
				case STARTS_WITH:
					return "Starts With";
				case ENDS_WITH:
					return "Ends With";
				case CONTAINS:
					return "Contains";
				case CONTAINS_IGNORE_CASE:
					return "Contains (Case Insensitive)";
				default:
					throw new IllegalArgumentException();
			}
		}

		public static FilterType fromReadableName(String name) {
			switch (name) {
				case "Exactly Matches":
					return EQUALS;
				case "Matches (Case Insensitive)":
					return EQUALS_IGNORE_CASE;
				case "Starts With":
					return STARTS_WITH;
				case "Ends With":
					return ENDS_WITH;
				case "Contains":
					return CONTAINS;
				case "Contains (Case Insensitive)":
					return CONTAINS_IGNORE_CASE;
				default:
					return null;
			}
		}
	}

	public static String paddedJoin(String a, String b) {
		if (endsWithSeparator(a) || startsWithSeparator(b)) {
			return a + b;
		} else {
			return a + " " + b;
		}
	}

	public static boolean startsWithSeparator(String text) {
		for (String separator : SEPARATORS) {
			if (text.startsWith(separator)) return true;
		}

		return false;
	}

	public static boolean endsWithSeparator(String text) {
		for (String separator : SEPARATORS) {
			if (text.endsWith(separator)) return true;
		}

		return false;
	}
}
