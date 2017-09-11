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
import java.util.List;

public class HTMLPage implements HTMLContent {
	private List<HTMLFragment> head;
	private List<HTMLFragment> body;
	private List<HTMLFragment> script;
	
	public HTMLPage() {
		head = new ArrayList<HTMLFragment>();
		body = new ArrayList<HTMLFragment>();
		script = new ArrayList<HTMLFragment>();
	}
	
	public void add(HTMLFragment f) {
		if (f.getType() == HTMLFragment.Type.HEAD) {
			head.add(f);
		} else if (f.getType() == HTMLFragment.Type.SCRIPT) {
			script.add(f);
		} else {
			body.add(f);
		}
	}
	
	@Override
	public String toHTML() {
		StringBuffer buf = new StringBuffer();
		
		buf.append("<html>\n");
		buf.append("<head>\n");
		
		for (HTMLFragment f : head) {
			buf.append(f.toHTML());
			buf.append("\n");
		}
		
		buf.append("</head>\n");
		buf.append("<body>\n");

		buf.append("<script>\n");
		for (HTMLFragment f : script) {
			buf.append(f.toHTML());
			buf.append("\n");
		}
		buf.append("</script>\n");
		
		for (HTMLFragment f : body) {
			buf.append(f.toHTML());
			buf.append("\n");
		}
		
		buf.append("</body>\n");
		buf.append("</html>");
		
		return buf.toString();
	}
}
