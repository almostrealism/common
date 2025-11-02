/*
 * Copyright 2018 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.io;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;


public class FilePrintWriter implements PrintWriter {
	private StringBuffer indent;
	private java.io.PrintWriter out;
	
	public FilePrintWriter(File f) throws FileNotFoundException { 
		this.out = new java.io.PrintWriter(new FileOutputStream(f), true);
	}

	@Override
	public void close() { this.out.close(); }

	@Override
	public void moreIndent() { this.indent.append("    "); }

	@Override
	public void lessIndent() { this.indent.delete(this.indent.length() - 4, this.indent.length()); }

	@Override
	public void print(String s) { this.out.print(this.indent.toString() + s); }

	@Override
	public void println(String s) { this.out.println(this.indent.toString() + s); }

	@Override
	public void println() { this.out.println(); }
}
