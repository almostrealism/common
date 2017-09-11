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

package org.almostrealism.swing.dialogs;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;

import org.almostrealism.algebra.Vector;
import org.almostrealism.swing.DynamicDisplay;
import org.almostrealism.swing.panels.EditVectorPanel;

/**
  An EditVectorDialog object can be used to allow a user to specify
  the data for a Vector object.
*/
public class EditVectorDialog extends JFrame {
  private Vector vector;
  private DynamicDisplay display;
  
  private EditVectorPanel editPanel;
  private JPanel buttonPanel;
  private JButton okButton, cancelButton;

	/**
	  Constructs a new EditVectorDialog that can be used to edit
	  the specified Vector object. The dialog will update the
	  specified DynamicDisplay object when the Vector value
	  has been changed.
	*/
	
	public EditVectorDialog(Vector vector, DynamicDisplay display) {
		super("Edit Vector");
		
		this.vector = vector;
		this.display = display;
		
		this.editPanel = new EditVectorPanel(this.vector);
		
		this.buttonPanel = new JPanel(new FlowLayout());
		this.okButton = new JButton("OK");
		this.cancelButton = new JButton("Cancel");
		
		this.buttonPanel.add(this.okButton);
		this.buttonPanel.add(this.cancelButton);
		
		super.getContentPane().add(this.editPanel, BorderLayout.CENTER);
		super.getContentPane().add(this.buttonPanel, BorderLayout.SOUTH);
		
		super.setSize(170, 160);
		
		this.okButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent event) {
				apply();
				setVisible(false);
			}
		});
		
		this.cancelButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent event) {
				setVisible(false);
			}
		});
	}
	
	/**
	  Applies the changes made in this dialog.
	*/
	
	public void apply() {
		Vector newVector = this.editPanel.getSelectedVector();
		
		this.vector.setX(newVector.getX());
		this.vector.setY(newVector.getY());
		this.vector.setZ(newVector.getZ());
		
		if (this.display != null)
			this.display.updateDisplay();
	}
}
