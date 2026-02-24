/*
 * Copyright 2018 Michael Murray
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

package io.flowtree.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * @author Mike Murray
 */
public class LoginDialog extends JPanel {
	private final JFrame frame;
	private final JTextField userField;
	private final JTextField passwdField;
	private final JButton okButton;
	private final JButton cancelButton;
	
	private String user, passwd;
	
	public LoginDialog() {
		this.userField = new JTextField(20);
		this.passwdField = new JPasswordField(20);
		
		this.okButton = new JButton("Ok");
		this.cancelButton = new JButton("Cancel");
		
		this.okButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				LoginDialog.this.user = LoginDialog.this.userField.getText();
				LoginDialog.this.passwd = LoginDialog.this.passwdField.getText();
			}
		});
		
		super.setLayout(new GridLayout(3, 2));
		
		super.add(new JLabel("User: "));
		super.add(this.userField);
		super.add(new JLabel("Password: "));
		super.add(this.passwdField);
		super.add(this.okButton);
		super.add(this.cancelButton);
		
		this.frame = new JFrame("Login");
		this.frame.setSize(250, 80);
		Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
		this.frame.setLocation((screen.width - this.frame.getWidth()) / 2,
				(screen.height - this.frame.getHeight()) / 2);
		this.frame.getContentPane().add(this);
	}
	
	public void showDialog(final Runnable r) {
		this.okButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent event) {
				LoginDialog.this.frame.setVisible(false);
				r.run();
			}
		});
		
		this.frame.setVisible(true);
	}
	
	public String getUser() { return this.user; }
	public String getPassword() { return this.passwd; }
}
