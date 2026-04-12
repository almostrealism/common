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

package io.almostrealism.profile;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.scope.Scope;
import io.almostrealism.util.DescribableParent;

import java.util.List;

/**
 * Immutable-style descriptor carrying identity and display information for a
 * compiled operation.
 *
 * <p>Every operation in the computation graph is assigned an {@code OperationMetadata}
 * instance that provides:</p>
 * <ul>
 *   <li><b>id</b> &mdash; a globally unique, monotonically increasing identifier
 *       assigned at construction time via an atomic counter</li>
 *   <li><b>displayName</b> &mdash; a short, whitespace-free name used as the primary
 *       label (e.g., the computation class name)</li>
 *   <li><b>shortDescription</b> &mdash; a brief human-readable description for
 *       profiling summaries and UI display</li>
 *   <li><b>longDescription</b> &mdash; an optional detailed description</li>
 *   <li><b>shape</b> &mdash; the {@link TraversalPolicy} describing the output
 *       tensor shape of the operation</li>
 *   <li><b>contextName</b> &mdash; the name of the enclosing context (e.g., layer
 *       name) for hierarchical display</li>
 *   <li><b>signature</b> &mdash; the compiled function signature</li>
 *   <li><b>children</b> &mdash; child metadata forming a hierarchical tree that
 *       mirrors the computation graph structure</li>
 * </ul>
 *
 * <p>Instances are constructed either directly from display name and description
 * strings, or by copying from an existing instance via the copy constructor.
 * The {@code with*} methods create modified copies without mutating the original,
 * supporting a builder-like pattern.</p>
 *
 * <p>The metadata hierarchy is used by {@link OperationProfileNode#forMetadata}
 * to construct the profiling tree, and by {@link OperationProfile#metadataKey}
 * to derive the canonical lookup key for each operation.</p>
 *
 * @see OperationProfile
 * @see OperationProfileNode
 * @see OperationInfo
 * @see TraversalPolicy
 *
 * @author Michael Murray
 */
public class OperationMetadata implements DescribableParent<OperationMetadata> {
	/** Global counter for assigning unique IDs to metadata instances. */
	private static long opIndex = 0;

	/** Unique identifier assigned sequentially at construction time. */
	private long id;

	/** Human-readable name, short summary, and detailed description for this operation. */
	private String displayName, shortDescription, longDescription;

	/** The output shape of the operation, if known. */
	private TraversalPolicy shape;

	/** An optional string identifying the execution context (e.g. thread or device). */
	private String contextName;

	/** A compact signature string uniquely identifying the operation within its context. */
	private String signature;

	/** Child operations nested within this metadata node. */
	private List<OperationMetadata> children;

	/** Default constructor for deserialization. */
	private OperationMetadata() { }

	/**
	 * Copy constructor that duplicates all fields from the given metadata.
	 * The ID is preserved (not reassigned).
	 *
	 * @param from the metadata to copy, or {@code null} for an empty instance
	 */
	public OperationMetadata(OperationMetadata from) {
		this();

		if (from != null) {
			setId(from.getId());
			setDisplayName(from.getDisplayName());
			setShortDescription(from.getShortDescription());
			setLongDescription(from.getLongDescription());
			setShape(from.getShape());
			setContextName(from.getContextName());
			setSignature(from.getSignature());
			setChildren(from.getChildren());
		}
	}

	/**
	 * Copy constructor that duplicates all fields and replaces the children list.
	 *
	 * @param from     the metadata to copy
	 * @param children the new children list
	 */
	public OperationMetadata(OperationMetadata from, List<OperationMetadata> children) {
		this(from);
		setChildren(children);
	}

	/**
	 * Creates metadata with a display name and short description. A unique ID
	 * is automatically assigned.
	 *
	 * @param displayName      the primary label (must not be {@code null} or contain whitespace)
	 * @param shortDescription a brief human-readable description
	 */
	public OperationMetadata(String displayName, String shortDescription) {
		this(displayName, shortDescription, null);
	}

	/**
	 * Creates metadata with a display name, short description, and optional long
	 * description. A unique ID is automatically assigned.
	 *
	 * @param displayName      the primary label (must not be {@code null} or contain whitespace)
	 * @param shortDescription a brief human-readable description
	 * @param longDescription  an optional detailed description (may be {@code null})
	 */
	public OperationMetadata(String displayName, String shortDescription, String longDescription) {
		this(++opIndex, displayName, shortDescription, longDescription);
	}

	/**
	 * Internal constructor with explicit ID assignment.
	 *
	 * @param id               the unique identifier
	 * @param displayName      the primary label (must not be {@code null})
	 * @param shortDescription a brief human-readable description
	 * @param longDescription  an optional detailed description
	 * @throws IllegalArgumentException if displayName is {@code null}
	 */
	private OperationMetadata(long id, String displayName, String shortDescription, String longDescription) {
		this();
		setId(id);
		setDisplayName(displayName);
		setShortDescription(shortDescription);
		setLongDescription(longDescription);

		if (displayName == null) {
			throw new IllegalArgumentException();
		}
	}

	/** Returns the globally unique identifier for this metadata instance. */
	public long getId() { return id; }

	/** Sets the unique identifier. Primarily used during deserialization. */
	public void setId(long id) { this.id = id; }

	/** Returns the primary display label for this operation. */
	public String getDisplayName() { return displayName; }

	/**
	 * Sets the display name. Logs a warning if the name contains whitespace
	 * or is the literal string "null".
	 *
	 * @param displayName the display name to set
	 */
	public void setDisplayName(String displayName) {
		this.displayName = displayName;

		if (displayName != null) {
			if (displayName.contains(" ")) {
				Scope.console.features(OperationMetadata.class)
						.warn("Display name contains whitespace");
			} else if (displayName.equalsIgnoreCase("null")) {
				Scope.console.features(OperationMetadata.class)
						.warn("Display name is \"" + displayName + "\"");
			}
		}
	}

	/** Returns the brief human-readable description for profiling summaries. */
	public String getShortDescription() { return shortDescription; }

	/** Sets the short description. */
	public void setShortDescription(String description) { this.shortDescription = description; }

	/** Returns the detailed description, or {@code null} if not set. */
	public String getLongDescription() { return longDescription; }

	/** Sets the long description. */
	public void setLongDescription(String longDescription) { this.longDescription = longDescription; }

	/** Returns the output tensor shape of the operation, or {@code null} if not set. */
	public TraversalPolicy getShape() { return shape; }

	/** Sets the output tensor shape. */
	public void setShape(TraversalPolicy shape) { this.shape = shape; }

	/** Returns the enclosing context name (e.g., layer name), or {@code null}. */
	public String getContextName() { return contextName; }

	/** Sets the context name. */
	public void setContextName(String contextName) { this.contextName = contextName; }

	/** Returns the compiled function signature, or {@code null} if not set. */
	public String getSignature() { return signature; }

	/** Sets the function signature. */
	public void setSignature(String signature) { this.signature = signature; }

	/** Returns the child metadata list, or {@code null} if this is a leaf. */
	@Override
	public List<OperationMetadata> getChildren() { return children; }

	/** Sets the child metadata list. */
	public void setChildren(List<OperationMetadata> children) { this.children = children; }

	/**
	 * Returns a copy of this metadata with the given shape.
	 *
	 * @param shape the output tensor shape
	 * @return a new metadata instance with the shape set
	 */
	public OperationMetadata withShape(TraversalPolicy shape) {
		OperationMetadata metadata = new OperationMetadata(this);
		metadata.setShape(shape);
		return metadata;
	}

	/**
	 * Returns a copy of this metadata with the given context name.
	 *
	 * @param contextName the context name (e.g., enclosing layer name)
	 * @return a new metadata instance with the context name set
	 */
	public OperationMetadata withContextName(String contextName) {
		OperationMetadata metadata = new OperationMetadata(this);
		metadata.setContextName(contextName);
		return metadata;
	}

	/**
	 * Returns a copy of this metadata with the given display name.
	 *
	 * @param name the new display name
	 * @return a new metadata instance with the display name set
	 */
	public OperationMetadata withDisplayName(String name) {
		OperationMetadata metadata = new OperationMetadata(this);
		metadata.setDisplayName(name);
		return metadata;
	}

	/**
	 * Returns a copy of this metadata with the given function signature.
	 *
	 * @param signature the compiled function signature
	 * @return a new metadata instance with the signature set
	 */
	public OperationMetadata withSignature(String signature) {
		OperationMetadata metadata = new OperationMetadata(this);
		metadata.setSignature(signature);
		return metadata;
	}

	/**
	 * Returns a copy of this metadata with the given text appended to the
	 * short description.
	 *
	 * @param desc the text to append to the short description
	 * @return a new metadata instance with the extended short description
	 */
	public OperationMetadata appendShortDescription(String desc) {
		OperationMetadata metadata = new OperationMetadata(this);
		metadata.setShortDescription(metadata.getShortDescription() + desc);
		return metadata;
	}

	@Override
	public String description(List<String> children) {
		return displayName + "(id=" + id + ")";
	}
}
