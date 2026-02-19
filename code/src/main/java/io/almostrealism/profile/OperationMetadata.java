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

/** The OperationMetadata class. */
public class OperationMetadata implements DescribableParent<OperationMetadata> {
	private static long opIndex = 0;

	private long id;
	private String displayName, shortDescription, longDescription;
	private TraversalPolicy shape;
	private String contextName;
	private String signature;

	private List<OperationMetadata> children;

	private OperationMetadata() { }

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

	public OperationMetadata(OperationMetadata from, List<OperationMetadata> children) {
		this(from);
		setChildren(children);
	}

	public OperationMetadata(String displayName, String shortDescription) {
		this(displayName, shortDescription, null);
	}

	public OperationMetadata(String displayName, String shortDescription, String longDescription) {
		this(++opIndex, displayName, shortDescription, longDescription);
	}

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

	public long getId() { return id; }
	public void setId(long id) { this.id = id; }

	public String getDisplayName() { return displayName; }
	/** Performs the setDisplayName operation. */
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

	public String getShortDescription() { return shortDescription; }
	public void setShortDescription(String description) { this.shortDescription = description; }

	public String getLongDescription() { return longDescription; }
	public void setLongDescription(String longDescription) { this.longDescription = longDescription; }

	public TraversalPolicy getShape() { return shape; }
	public void setShape(TraversalPolicy shape) { this.shape = shape; }

	public String getContextName() { return contextName; }
	public void setContextName(String contextName) { this.contextName = contextName; }

	public String getSignature() { return signature; }
	public void setSignature(String signature) { this.signature = signature; }

	public List<OperationMetadata> getChildren() { return children; }
	public void setChildren(List<OperationMetadata> children) { this.children = children; }

	/** Performs the withShape operation. */
	public OperationMetadata withShape(TraversalPolicy shape) {
		OperationMetadata metadata = new OperationMetadata(this);
		metadata.setShape(shape);
		return metadata;
	}

	/** Performs the withContextName operation. */
	public OperationMetadata withContextName(String contextName) {
		OperationMetadata metadata = new OperationMetadata(this);
		metadata.setContextName(contextName);
		return metadata;
	}

	/** Performs the withDisplayName operation. */
	public OperationMetadata withDisplayName(String name) {
		OperationMetadata metadata = new OperationMetadata(this);
		metadata.setDisplayName(name);
		return metadata;
	}

	/** Performs the withSignature operation. */
	public OperationMetadata withSignature(String signature) {
		OperationMetadata metadata = new OperationMetadata(this);
		metadata.setSignature(signature);
		return metadata;
	}

	/** Performs the appendShortDescription operation. */
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
