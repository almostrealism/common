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
	/** Common word-boundary separator characters used when padding join operations. */
	public static final String[] SEPARATORS = {" ", "-", "_", "."};

	/** Determines which part of the file path (name or directory path) is matched. */
	private FilterOn filterOn;

	/** The comparison operation used to match the selected value against the filter string. */
	private FilterType filterType;

	/** The filter string to match against the selected path component. */
	private String filter;

	/** Creates an uninitialized filter; fields must be set before use. */
	public FileWaveDataProviderFilter() { }

	/**
	 * Creates a filter with the specified matching criteria.
	 *
	 * @param filterOn   which path component to match against
	 * @param filterType comparison operation to apply
	 * @param filter     the pattern string to match
	 */
	public FileWaveDataProviderFilter(FilterOn filterOn, FilterType filterType, String filter) {
		this.filterOn = filterOn;
		this.filterType = filterType;
		this.filter = filter;
	}

	/** Returns which path component this filter matches against. */
	public FilterOn getFilterOn() { return filterOn; }

	/**
	 * Sets which path component this filter matches against.
	 *
	 * @param filterOn the new FilterOn value
	 */
	public void setFilterOn(FilterOn filterOn) { this.filterOn = filterOn; }

	/** Returns the comparison operation used for matching. */
	public FilterType getFilterType() { return filterType; }

	/**
	 * Sets the comparison operation used for matching.
	 *
	 * @param filterType the new FilterType value
	 */
	public void setFilterType(FilterType filterType) { this.filterType = filterType; }

	/** Returns the filter pattern string. */
	public String getFilter() { return filter; }

	/**
	 * Sets the filter pattern string.
	 *
	 * @param filter the new filter pattern
	 */
	public void setFilter(String filter) { this.filter = filter; }

	/**
	 * Returns true if the specified provider's path matches this filter.
	 *
	 * @param tree the tree providing relative path context
	 * @param p    the provider whose path is tested
	 * @return true if the selected path component matches the filter pattern
	 */
	public boolean matches(FileWaveDataProviderTree tree, FileWaveDataProvider p) {
		return getFilterType().matches(getFilterOn().select(tree, p), getFilter());
	}

	/**
	 * Coerces a value to satisfy this filter's match criteria.
	 *
	 * @param value      the current value to coerce
	 * @param allowEqual if true, EQUALS-type filters may substitute the filter string directly
	 * @return the coerced value that satisfies the filter, or the original value if already matching
	 */
	public String coerceToMatch(String value, boolean allowEqual) {
		return getFilterType().coerceToMatch(value, getFilter(), allowEqual);
	}

	/**
	 * Coerces a relative path to satisfy this filter's match criteria.
	 *
	 * @param relativePath the relative path to coerce
	 * @return a new Path with the relevant component adjusted to match the filter
	 */
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

	/**
	 * Creates a filter that matches providers whose file name starts with the given prefix.
	 *
	 * @param prefix the required name prefix
	 * @return a new FileWaveDataProviderFilter configured with NAME and STARTS_WITH
	 */
	public static FileWaveDataProviderFilter nameStartsWith(String prefix) {
		return new FileWaveDataProviderFilter(FilterOn.NAME, FilterType.STARTS_WITH, prefix);
	}

	/** Determines which part of the file path is extracted for comparison. */
	public enum FilterOn {
		/** Match against the file's directory path (relative, without leading slash). */
		PATH,
		/** Match against the file's base name (last path component). */
		NAME;

		/**
		 * Extracts the selected path component from the given provider within the given tree.
		 *
		 * @param tree the tree used to compute relative paths
		 * @param p    the provider whose path is extracted
		 * @return the file name or relative directory path, depending on this FilterOn value
		 */
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

		/**
		 * Extracts the selected path component from the given relative path.
		 *
		 * @param relativePath the relative path to extract from
		 * @return the name or directory path, depending on this FilterOn value
		 */
		String select(Path relativePath) {
			return select(relativePath.toFile());
		}

		/**
		 * Extracts the selected path component from a relative file.
		 *
		 * @param relativeFile the relative file to extract from
		 * @return the name or directory path, depending on this FilterOn value
		 */
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

		/**
		 * Returns the human-readable label for this FilterOn constant.
		 *
		 * @return display name suitable for UI presentation
		 */
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

		/**
		 * Removes a leading slash from the given path string, if present.
		 *
		 * @param value path string to strip
		 * @return the path without a leading slash, or the original value if none
		 */
		public static String stripSlash(String value) {
			if (value != null && value.startsWith("/")) {
				return value.substring(1);
			} else {
				return value;
			}
		}

		/**
		 * Returns the FilterOn constant with the given human-readable label.
		 *
		 * @param name the display name to look up
		 * @return the matching FilterOn constant, or null if none matches
		 */
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

	/** Defines the string comparison operation applied by a filter. */
	public enum FilterType {
		/** Exact case-sensitive match. */
		EQUALS,
		/** Exact case-insensitive match. */
		EQUALS_IGNORE_CASE,
		/** Value must begin with the filter string. */
		STARTS_WITH,
		/** Value must end with the filter string. */
		ENDS_WITH,
		/** Value must contain the filter string (case-sensitive). */
		CONTAINS,
		/** Value must contain the filter string (case-insensitive). */
		CONTAINS_IGNORE_CASE;

		/**
		 * Returns true if the given value satisfies this filter type for the given pattern.
		 *
		 * @param value  the string to test; may be null
		 * @param filter the filter pattern; may be null or empty
		 * @return true if value satisfies the condition
		 */
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

		/**
		 * Adjusts the given value so that it satisfies this filter type for the given pattern.
		 *
		 * @param value      the current value to coerce; may be null
		 * @param filter     the target filter pattern
		 * @param allowEqual if true, EQUALS-type filters may directly substitute the filter string
		 * @return a coerced value satisfying this filter type
		 */
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

		/**
		 * Returns the human-readable label for this FilterType constant.
		 *
		 * @return display name suitable for UI presentation
		 */
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

		/**
		 * Returns the FilterType constant with the given human-readable label.
		 *
		 * @param name the display name to look up
		 * @return the matching FilterType constant, or null if none matches
		 */
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

	/**
	 * Joins two strings with a space separator if neither already ends or starts with a separator character.
	 *
	 * @param a the left string
	 * @param b the right string
	 * @return the concatenated string, with a space inserted if needed
	 */
	public static String paddedJoin(String a, String b) {
		if (endsWithSeparator(a) || startsWithSeparator(b)) {
			return a + b;
		} else {
			return a + " " + b;
		}
	}

	/**
	 * Returns true if the given text starts with one of the defined separator characters.
	 *
	 * @param text the string to test
	 * @return true if text begins with a separator
	 */
	public static boolean startsWithSeparator(String text) {
		for (String separator : SEPARATORS) {
			if (text.startsWith(separator)) return true;
		}

		return false;
	}

	/**
	 * Returns true if the given text ends with one of the defined separator characters.
	 *
	 * @param text the string to test
	 * @return true if text ends with a separator
	 */
	public static boolean endsWithSeparator(String text) {
		for (String separator : SEPARATORS) {
			if (text.endsWith(separator)) return true;
		}

		return false;
	}
}
