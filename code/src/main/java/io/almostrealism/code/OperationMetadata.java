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

package io.almostrealism.code;

import java.util.ArrayList;
import java.util.List;

public class OperationMetadata {
	private String displayName, shortDescription, longDescription;
	private List<OperationMetadata> children;

	private OperationMetadata() { children = new ArrayList<>(); }

	public OperationMetadata(OperationMetadata from) {
		this();

		if (from != null) {
			setDisplayName(from.getDisplayName());
			setShortDescription(from.getShortDescription());
			setLongDescription(from.getLongDescription());
		}
	}

	public OperationMetadata(String displayName, String shortDescription) {
		this();
		setDisplayName(displayName);
		setShortDescription(shortDescription);

		if (displayName == null) {
			throw new IllegalArgumentException();
		}
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getShortDescription() {
		return shortDescription;
	}

	public void setShortDescription(String description) {
		this.shortDescription = description;
	}

	public String getLongDescription() {
		return longDescription;
	}

	public void setLongDescription(String longDescription) {
		this.longDescription = longDescription;
	}

	public List<OperationMetadata> getChildren() {
		return children;
	}

	public void setChildren(List<OperationMetadata> children) {
		this.children = children;
	}
}
