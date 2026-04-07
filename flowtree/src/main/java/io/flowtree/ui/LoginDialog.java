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
 * A Swing login panel that collects a username and password from the user.
 * The panel is embedded in a small {@link JFrame} and centred on screen.
 *
 * <p>Callers provide a {@link Runnable} to {@link #showDialog(Runnable)} that
 * is executed after the user presses "Ok". At that point {@link #getUser()}
 * and {@link #getPassword()} return the values entered in the respective
 * fields.
 *
 * @author  Mike Murray
 */
public class LoginDialog extends JPanel {

	/** Enclosing frame that hosts this panel. */
	private final JFrame frame;

	/** Text field for the username entry. */
	private final JTextField userField;

	/** Text field (password-masked) for the password entry. */
	private final JTextField passwdField;

	/** Button that captures the field values and triggers the supplied callback. */
	private final JButton okButton;

	/** Button that closes the dialog without taking any action. */
	private final JButton cancelButton;

	/** Username captured when the Ok button is pressed. */
	private String user;

	/** Password captured when the Ok button is pressed. */
	private String passwd;

	/**
	 * Constructs a new {@link LoginDialog}, initialises the input fields and
	 * buttons, wires the Ok action listener to capture the field values, and
	 * creates the enclosing frame centred on screen.
	 */
	public LoginDialog() {
		this.userField = new JTextField(20);
		this.passwdField = new JPasswordField(20);

		this.okButton = new JButton("Ok");
		this.cancelButton = new JButton("Cancel");

		this.okButton.addActionListener(new ActionListener() {
			@Override
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

	/**
	 * Makes the login dialog visible and registers the given {@link Runnable}
	 * to be invoked (on the event-dispatch thread) after the user presses Ok.
	 * The frame is hidden before the runnable is called.
	 *
	 * @param r the action to perform after the user submits their credentials
	 */
	public void showDialog(final Runnable r) {
		this.okButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent event) {
				LoginDialog.this.frame.setVisible(false);
				r.run();
			}
		});

		this.frame.setVisible(true);
	}

	/**
	 * Returns the username entered by the user after the Ok button was pressed.
	 * Returns {@code null} if Ok has not yet been pressed.
	 *
	 * @return the username string, or {@code null}
	 */
	public String getUser() { return this.user; }

	/**
	 * Returns the password entered by the user after the Ok button was pressed.
	 * Returns {@code null} if Ok has not yet been pressed.
	 *
	 * @return the password string, or {@code null}
	 */
	public String getPassword() { return this.passwd; }
}
