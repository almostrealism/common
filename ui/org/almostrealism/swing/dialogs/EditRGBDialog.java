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

import org.almostrealism.color.RGB;
import org.almostrealism.swing.DynamicDisplay;
import org.almostrealism.swing.panels.EditRGBPanel;

/**
 * An EditRGBDialog object can be used to allow a user to specify
 * the data for a RGB object.
 */
public class EditRGBDialog extends JFrame {
  private RGB color;
  private DynamicDisplay display;
  
  private EditRGBPanel editPanel;
  private JPanel buttonPanel;
  private JButton okButton, cancelButton;

	/**
	  Constructs a new EditRGBDialog that can be used to edit
	  the specified RGB object. The dialog will update the
	  specified DynamicDisplay object when the RGB value
	  has been changed.
	*/
	
	public EditRGBDialog(RGB color, DynamicDisplay display) {
		super("Edit RGB");
		
		this.color = color;
		this.display = display;
		
		this.editPanel = new EditRGBPanel(this.color);
		
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
		RGB newColor = this.editPanel.getSelectedColor();
		
		this.color.setRed(newColor.getRed());
		this.color.setGreen(newColor.getGreen());
		this.color.setBlue(newColor.getBlue());
		
		if (this.display != null)
			this.display.updateDisplay();
	}
}
