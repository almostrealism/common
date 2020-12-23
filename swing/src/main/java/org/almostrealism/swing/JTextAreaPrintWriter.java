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

package org.almostrealism.swing;

import javax.swing.JTextArea;

import org.almostrealism.io.PrintWriter;


/**
 * A {@link JTextAreaPrintWriter} object can be used as an interface for printing
 * text into a {@link JTextArea} object.
 */
public class JTextAreaPrintWriter implements PrintWriter {
  private StringBuffer indent;
  
  private JTextArea textArea;

	/**
	 * Constructs a new JTextAreaPrintWriter using a new default JTextArea object.
	 */
	public JTextAreaPrintWriter() {
		this.indent = new StringBuffer();
		this.setTextArea(new JTextArea());
	}
	
	/**
	 * Constructs a new JTextAreaPrintWriter using the specified JTextArea object.
	 */
	public JTextAreaPrintWriter(JTextArea textArea) {
		this.indent = new StringBuffer();
		this.setTextArea(textArea);
	}
	
	/**
	 * Sets the JTextArea object used by this JTextAreaPrintWriter to the specified JTextArea object.
	 */
	public void setTextArea(JTextArea textArea) { this.textArea = textArea; }
	
	/**
	 * Returns the JTextArea object used by this JTextAreaPrintWriter.
	 */
	public JTextArea getTextArea() { return this.textArea; }
	
	/**
	 * Increases the indent used for this JTextAreaPrintWriter object.
	 */
	public void moreIndent() { this.indent.append("    "); }
	
	/**
	 * Reduces the indent used for this JTextAreaPrintWriter object.
	 *
	 */
	public void lessIndent() { this.indent.delete(this.indent.length() - 4, this.indent.length()); }
	
	/**
	 * Appends the specified String to the JTextArea used by this JTextAreaPrintWriter.
	 */
	public void print(String s) { this.textArea.append(this.indent + s); }
	
	/**
	 * Appends the specified String, followed by a new line character, to the JTextArea used by this JTextAreaPrintWriter.
	 */
	public void println(String s) { this.textArea.append(this.indent + s + "\n"); }
	
	/**
	 * Appends a new line character to the JTextArea used by this JTextAreaPrintWriter.
	 */
	public void println() { this.textArea.append("\n"); }
}
