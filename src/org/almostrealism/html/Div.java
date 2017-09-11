/*
 * Copyright 2016 Michael Murray
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

package org.almostrealism.html;

import java.util.ArrayList;

/**
 * @author  Michael Murray
 */
public class Div extends ArrayList<HTMLContent> implements HTMLContent, Styleable {
	private ArrayList<String> classNames = new ArrayList<String>();
	
	public String toHTML() {
		StringBuffer buf = new StringBuffer();
		
		buf.append("<div ");
		buf.append(getClassString());
		buf.append(">");
		
		for (HTMLContent c : this) {
			buf.append(c.toHTML());
		}
		
		buf.append("</div>");
		
		return buf.toString();
	}
	
	@Override
	public void addStyleClass(String name) {
		classNames.add(name);
	}
	
	private String getClassString() {
		StringBuffer buf = new StringBuffer();
		buf.append("class=\"");
		
		for (String s : classNames) {
			buf.append(s);
			buf.append(" ");
		}
		
		buf.append("\"");
		
		return buf.toString();
	}
}
