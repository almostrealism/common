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

package io.almostrealism.html;

import java.util.ArrayList;

public class HTMLFragment extends ArrayList<HTMLContent> implements HTMLContent {
	public enum Type { HEAD, BODY, SCRIPT; }
	
	private Type type;
	
	public HTMLFragment() { this(Type.BODY); }
	
	public HTMLFragment(Type t) { this.type = t; }
	
	public Type getType() { return type; }
	
	@Override
	public String toHTML() {
		StringBuffer buf = new StringBuffer();
		
		for (HTMLContent c : this) {
			buf.append(c.toHTML());
			buf.append("\n");
		}
		
		return buf.toString();
	}
}
