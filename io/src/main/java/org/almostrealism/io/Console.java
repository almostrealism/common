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

package org.almostrealism.io;

public class Console {
	public static boolean systemOutEnabled = true;
	
	private StringBuffer data = new StringBuffer();
	private StringBuffer lastLine = new StringBuffer();
	private boolean resetLastLine = false;
	
	public void print(String s) {
		if (resetLastLine) lastLine = new StringBuffer();
		
		data.append(s);
		lastLine.append(s);
		
		if (systemOutEnabled)
			System.out.print(s);
	}
	
	public void println(String s) {
		if (resetLastLine) lastLine = new StringBuffer();
		
		data.append(s);
		data.append("\n");
		
		lastLine.append(s);
		resetLastLine = true;
		
		if (systemOutEnabled)
			System.out.println(s);
	}
	
	public void println() {
		if (resetLastLine) lastLine = new StringBuffer();
		
		data.append("\n");
		resetLastLine = true;
		
		if (systemOutEnabled)
			System.out.println();
	}
	
	public String lastLine() { return lastLine.toString(); }
}
