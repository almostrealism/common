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

package org.almostrealism.swing.panels;

import java.awt.FlowLayout;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/** An EditNumberPanel object can be used to specify a number. */
public class EditNumberPanel extends JPanel {
  private JTextField numberField;

	/**
	 * Consturcts an EditNumberPanel object labeled "Value" and initial value set to 0.0.
	 */
	public EditNumberPanel() {
		this("Value", 0.0);
	}
	
	/**
	 * Constructs an EditNumberPanel object with the specified label and initial value set to 0.0.
	 */
	public EditNumberPanel(String label) {
		this(label, 0.0);
	}
	
	/**
	 * Constructs an EditNumberPanel object with the specified label and initial value.
	 */
	public EditNumberPanel(String label, double d) {
		super(new FlowLayout());
		
		this.numberField = new JTextField(6);
		
		this.numberField.setText(String.valueOf(d));
		
		this.add(new JLabel(label + " = "));
		this.add(this.numberField);
	}
	
	/**
	 * Returns the number selected by this EditNumberPanel object as a double value.
	 */
	public double getSelectedNumber() {
		double d = Double.parseDouble(this.numberField.getText());
		
		return d;
	}
}
