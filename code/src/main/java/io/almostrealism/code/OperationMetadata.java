/*
 * Copyright 2023 Michael Murray
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

package io.almostrealism.code;

import java.util.ArrayList;
import java.util.List;

public class OperationMetadata {
	private String displayName, shortDescription, longDescription;
	private String contextName;

	private List<OperationMetadata> children;

	private OperationMetadata() { children = new ArrayList<>(); }

	public OperationMetadata(OperationMetadata from) {
		this();

		if (from != null) {
			setDisplayName(from.getDisplayName());
			setShortDescription(from.getShortDescription());
			setLongDescription(from.getLongDescription());
			setContextName(from.getContextName());
			setChildren(from.getChildren());
		}
	}

	public OperationMetadata(String displayName, String shortDescription) {
		this(displayName, shortDescription, null);
	}

	public OperationMetadata(String displayName, String shortDescription, String longDescription) {
		this();
		setDisplayName(displayName);
		setShortDescription(shortDescription);
		setLongDescription(longDescription);

		if (displayName == null) {
			throw new IllegalArgumentException();
		}
	}

	public String getDisplayName() { return displayName; }
	public void setDisplayName(String displayName) { this.displayName = displayName; }

	public String getShortDescription() { return shortDescription; }
	public void setShortDescription(String description) { this.shortDescription = description; }

	public String getLongDescription() { return longDescription; }
	public void setLongDescription(String longDescription) { this.longDescription = longDescription; }

	public String getContextName() { return contextName; }
	public void setContextName(String contextName) { this.contextName = contextName; }

	public List<OperationMetadata> getChildren() { return children; }
	public void setChildren(List<OperationMetadata> children) { this.children = children; }

	public OperationMetadata withContextName(String contextName) {
		OperationMetadata metadata = new OperationMetadata(this);
		metadata.setContextName(contextName);
		return metadata;
	}

	public OperationMetadata appendShortDescription(String desc) {
		OperationMetadata metadata = new OperationMetadata(this);
		metadata.setShortDescription(metadata.getShortDescription() + desc);
		return metadata;
	}
}
