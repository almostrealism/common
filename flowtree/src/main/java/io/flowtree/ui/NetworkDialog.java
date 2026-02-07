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

import io.almostrealism.util.NumberFormats;
import io.flowtree.job.JobFactory;
import io.flowtree.msg.NodeProxy;
import io.flowtree.node.Client;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.Properties;

/**
 * A {@link NetworkDialog} allows the user to set up a node group for a network.
 * 
 * @author  Michael Murray
 */
public class NetworkDialog extends JPanel {
  private static final boolean verbose = true;
  
  private static final int defaultPort = 7766;
  private static final int defaultTotalNodes = 2;
  private static final int defaultPeers = 2;
  private static final int defaultJobs = 3;
  
  private static final int defaultOutputPort = 7788;
  
  private Client client;
  
  private final JFrame frame;
  private boolean open, locked;
  
  private final JPanel nodePanel;
	private final JPanel serverPanel;
	private final JPanel statusPanel;
  private final JPanel statusButtonPanel;
	private final JPanel serverButtonPanel;
  
  private final JButton startButton;
	private final JButton stopButton;
  private final JButton addButton;
	private final JButton removeButton;
	private final JButton sendButton;
  private final JButton closeButton;
  private final JButton updateStatusButton;
  
  private final JEditorPane statusDetailPane;
  
  private final JLabel statusLabel;
  
  private final JTextField outputHostField;
  private final JFormattedTextField portField;
	private final JFormattedTextField nodesField;
	private final JFormattedTextField peersField;
	private final JFormattedTextField jobsField;
	private final JFormattedTextField outputPortField;
  
  private final JList serverList;

	/**
	 * Constructs a new NetworkDialog object.
	 */
	public NetworkDialog() {
		super(new BorderLayout());
		
		this.client = Client.getCurrentClient();
		
		GridBagLayout gb = new GridBagLayout();
		
		this.nodePanel = new JPanel(gb);
		this.serverPanel = new JPanel(new BorderLayout());
		this.statusPanel = new JPanel(new BorderLayout());
		
		this.statusButtonPanel = new JPanel(new FlowLayout());
		this.serverButtonPanel = new JPanel(new BorderLayout());
		
		ActionListener buttonListener = new ActionListener() {
			public void actionPerformed(ActionEvent event) {
				if (event.getSource() == NetworkDialog.this.startButton) {
					NetworkDialog.this.start();
				} else if (event.getSource() == NetworkDialog.this.stopButton) {
					NetworkDialog.this.stop();
				} else if (event.getSource() == NetworkDialog.this.addButton) {
					if (NetworkDialog.this.locked) {
						JOptionPane.showMessageDialog(NetworkDialog.this,
												"Cannot add servers while network is running.",
												"Error",
												JOptionPane.ERROR_MESSAGE);
						return;
					}
					
					String server = JOptionPane.showInputDialog(NetworkDialog.this,
												"Enter server (host:port): ",
												"Add Server",
												JOptionPane.PLAIN_MESSAGE);
					
					if (server != null) {
						int total = NetworkDialog.this.serverList.getModel().getSize();
						((DefaultListModel)NetworkDialog.this.serverList.getModel()).add(total, server);
					}
				} else if (event.getSource() == NetworkDialog.this.removeButton) {
					if (NetworkDialog.this.locked) {
						JOptionPane.showMessageDialog(NetworkDialog.this,
												"Cannot remove servers while network is running.",
												"Error",
												JOptionPane.ERROR_MESSAGE);
						return;
					}
					
					int s = NetworkDialog.this.serverList.getSelectedIndex();
					
					if (s > 0) ((DefaultListModel)NetworkDialog.this.serverList.getModel()).remove(s);
				} else if (event.getSource() == NetworkDialog.this.sendButton) {
					if (Client.getCurrentClient() != null) {
						int index = NetworkDialog.this.serverList.getSelectedIndex();
						if (index < 0) return;
						
						SendTaskDialog d = new SendTaskDialog(Client.getCurrentClient().getServer(), index);
						d.showDialog();
					} else {
						JOptionPane.showMessageDialog(NetworkDialog.this,
											"A server must be running to send a task.",
											"Error",
											JOptionPane.ERROR_MESSAGE);
					}
				} else if (event.getSource() == NetworkDialog.this.updateStatusButton) {
					NetworkDialog.this.updateStatus();
				}
			}
		};
		
		// Set up status panel
		
		this.statusLabel = new JLabel("Status: Stopped");
		
		this.closeButton = new JButton("Done");
		this.closeButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent event) {
				NetworkDialog.this.closeDialog();
			}
		});
		
		this.startButton = new JButton("Start");
		this.stopButton = new JButton("Stop");
		this.startButton.addActionListener(buttonListener);
		this.stopButton.addActionListener(buttonListener);
		this.statusButtonPanel.add(this.startButton);
		this.statusButtonPanel.add(this.stopButton);
		this.statusButtonPanel.add(this.closeButton);
		
		this.statusPanel.add(this.statusLabel, BorderLayout.CENTER);
		this.statusPanel.add(this.statusButtonPanel, BorderLayout.SOUTH);
		
		// Set up node panel
		
		this.portField = new JFormattedTextField(NumberFormats.integerFormat);
		this.nodesField = new JFormattedTextField(NumberFormats.integerFormat);
		this.peersField = new JFormattedTextField(NumberFormats.integerFormat);
		this.jobsField = new JFormattedTextField(NumberFormats.integerFormat);
		this.outputPortField = new JFormattedTextField(NumberFormats.integerFormat);
		
		this.portField.setValue(Integer.valueOf(NetworkDialog.defaultPort));
		this.nodesField.setValue(Integer.valueOf(NetworkDialog.defaultTotalNodes));
		this.peersField.setValue(Integer.valueOf(NetworkDialog.defaultPeers));
		this.jobsField.setValue(Integer.valueOf(NetworkDialog.defaultJobs));
		this.outputPortField.setValue(Integer.valueOf(NetworkDialog.defaultOutputPort));
		
		this.outputHostField = new JTextField(6);
		
		GridBagConstraints c = new GridBagConstraints();
		c.weightx = 1.0;
		c.weighty = 0.0;
		
		c.insets = new Insets(5, 2, 5, 2);
		
		c.fill = GridBagConstraints.HORIZONTAL;
		c.gridwidth = 1;
		c.gridheight = 1;
		this.nodePanel.add(new JLabel("  Port: "), c);
		
		c.gridwidth = GridBagConstraints.REMAINDER;
		this.nodePanel.add(this.portField, c);
		
		c.gridwidth = 1;
		this.nodePanel.add(new JLabel("  Total nodes: "), c);
		
		c.gridwidth = GridBagConstraints.REMAINDER;
		this.nodePanel.add(this.nodesField, c);
		
		c.gridwidth = 1;
		this.nodePanel.add(new JLabel("  Peers per node: "), c);
		
		c.gridwidth = GridBagConstraints.REMAINDER;
		this.nodePanel.add(this.peersField, c);
		
		c.gridwidth = 1;
		this.nodePanel.add(new JLabel("  Jobs per node: "), c);
		
		c.gridwidth = GridBagConstraints.REMAINDER;
		this.nodePanel.add(this.jobsField, c);
		
		c.gridwidth = GridBagConstraints.REMAINDER;
		this.nodePanel.add(new JLabel("      "), c);
		
		c.gridwidth = 1;
		this.nodePanel.add(new JLabel("  Output server host: "), c);
		
		c.gridwidth = GridBagConstraints.REMAINDER;
		this.nodePanel.add(this.outputHostField, c);
		
		c.gridwidth = 1;
		this.nodePanel.add(new JLabel("  Output server port:"), c);
		
		c.gridwidth = GridBagConstraints.REMAINDER;
		this.nodePanel.add(this.outputPortField, c);
		
		// Set up server panel
		
		this.addButton = new JButton("Add");
		this.removeButton = new JButton("Remove");
		this.sendButton = new JButton("Send Task");
		this.addButton.addActionListener(buttonListener);
		this.removeButton.addActionListener(buttonListener);
		this.sendButton.addActionListener(buttonListener);
		this.serverButtonPanel.add(this.addButton, BorderLayout.WEST);
		this.serverButtonPanel.add(this.sendButton, BorderLayout.CENTER);
		this.serverButtonPanel.add(this.removeButton, BorderLayout.EAST);
		
		this.serverList = new JList(new DefaultListModel());
		
		c.fill = GridBagConstraints.BOTH;
		c.weighty = 1;
		c.gridwidth = GridBagConstraints.REMAINDER;
		c.gridheight = GridBagConstraints.RELATIVE;
		this.nodePanel.add(new JScrollPane(this.serverList), c);
		
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weighty = 0.0;
		c.gridheight = 1;
		this.nodePanel.add(this.serverButtonPanel, c);
		
		this.statusDetailPane = new JEditorPane("text/html", "<html></html>");
		this.updateStatusButton = new JButton("Refresh Status Page");
		this.updateStatusButton.addActionListener(buttonListener);
		
		this.serverPanel.add(new JScrollPane(this.statusDetailPane), BorderLayout.CENTER);
		this.serverPanel.add(this.updateStatusButton, BorderLayout.SOUTH);
		
		// Set up main panel
		
		super.add(this.nodePanel, BorderLayout.WEST);
		super.add(this.serverPanel, BorderLayout.CENTER);
		super.add(this.statusPanel, BorderLayout.SOUTH);
		
		this.frame = new JFrame("Network Configuration");
		this.frame.getContentPane().add(this);
		this.frame.setSize(700, 600);
		this.frame.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				NetworkDialog.this.closeDialog();
			}
		});
		
		if (this.client != null) {
			this.statusLabel.setText("");
			this.client.setStatusLabel(this.statusLabel);
			this.setLockFields(true);
		}
	}
	
	/**
	 * Shows this dialog in a frame.
	 */
	public void showDialog() {
		if (!this.open) {
			this.updateServerList();
			this.updateFields();
			this.updateStatus();
			
			this.frame.setVisible(true);
			this.open = true;
		}
	}
	
	/**
	 * Closes this dialog if it is open.
	 */
	public void closeDialog() {
		if (this.open) {
			this.frame.setVisible(false);
			this.open = false;
		}
	}
	
	public void updateStatus() {
		if (this.client == null) return;
		this.statusDetailPane.setText(this.client.getServer().getNodeGroup().getStatus("<br>\n"));
	}
	
	public void updateServerList() {
		if (this.client == null) return;
		
		DefaultListModel m = (DefaultListModel) NetworkDialog.this.serverList.getModel();
		m.clear();
		
		NodeProxy[] p = this.client.getServer().getNodeGroup().getServers();
		
		for (int i = 0; i < p.length; i++)
			m.add(m.getSize(), p[i].getInetAddress().getHostAddress() + ":" + p[i].getRemotePort());
	}
	
	public void updateFields() {
		if (this.client == null) return;
		
		this.portField.setValue(Integer.valueOf(this.client.getServer().getPort()));
		this.nodesField.setValue(Integer.valueOf(this.client.getServer().getNodeGroup().getNodes().length));
		this.peersField.setValue(Integer.valueOf(this.client.getServer().getNodeGroup().getNodes()[0].getMaxPeers()));
		this.jobsField.setValue(Integer.valueOf(this.client.getServer().getNodeGroup().getNodes()[0].getMaxJobs()));
		this.outputHostField.setText(this.client.getOutputHost());
		this.outputPortField.setValue(Integer.valueOf(this.client.getOutputPort()));
	}
	
	/**
	 * Starts a server if this NetworkDialog object is not already running one.
	 * This will cause all properties in the dialog to become uneditable.
	 */
	public void start() {
		if (this.client != null) return;
		
		final Properties p = new Properties();
		
		p.setProperty("server.port", this.portField.getText());
		p.setProperty("nodes.initial", this.nodesField.getText());
		p.setProperty("nodes.peers", this.peersField.getText());
		p.setProperty("nodes.jobs", this.jobsField.getText());
		p.setProperty("servers.output.host", this.outputHostField.getText());
		p.setProperty("servers.output.port", this.outputPortField.getText());
		
		int servers = this.serverList.getModel().getSize();
		
		p.setProperty("servers.total", String.valueOf(servers));
		
		for (int i = 0; i < servers; i++) {
			String s = (String)this.serverList.getModel().getElementAt(i);
			int index = s.indexOf(JobFactory.ENTRY_SEPARATOR);
			
			String host, port;
			
			if (index > 0) {
				host = s.substring(0, index);
				port = s.substring(index + 1);
			} else {
				host = s;
				port = String.valueOf(NetworkDialog.defaultPort);
			}
			
			p.setProperty("servers." + i + ".host", host);
			p.setProperty("servers." + i + ".port", port);
		}
        
		final LoginDialog l = new LoginDialog();
		
		Runnable r = new Runnable() {
			public void run() {
				String user = l.getUser();
				String passwd = l.getPassword();
				
				try {
					NetworkDialog.this.client = new Client(p, user, passwd, NetworkDialog.this.statusLabel);
					Client.setCurrentClient(NetworkDialog.this.client);
					
					NetworkDialog.this.setLockFields(true);
				} catch (IOException e) {
					JOptionPane.showMessageDialog(NetworkDialog.this, "An IO error occured while starting client.",
												"IO Error", JOptionPane.ERROR_MESSAGE);
					if (verbose) {
						StringBuffer b = new StringBuffer();
						b.append(e.getMessage() + "\n");
						
						Object[] o = e.getStackTrace();
						for (int i = 0; i < o.length; i++) b.append(o[i] + "\n");
						
						JOptionPane.showMessageDialog(NetworkDialog.this, b,
								"IO Error", JOptionPane.ERROR_MESSAGE);
					}
				}
			}
		};
		
		l.showDialog(r);
	}
	
	/**
	 * Stops and disposes the server started by this NetworkDialog object.
	 */
	public void stop() {
		if (this.client != null) {
			this.client.getServer().stop();
			this.client = null;
		}
		
		Client.setCurrentClient(null);
		
		this.setLockFields(false);
	}
	
	public void restart() {
		JFrame f = new JFrame("Network...");
		f.getContentPane().add(new JLabel("Restarting network client..."));
		f.setSize(120, 60);
		
		f.setVisible(true);
		
		this.stop();
		this.start();
		
		f.setVisible(false);
		f.dispose();
	}
	
	private void setLockFields(boolean locked) {
		this.portField.setEditable(!locked);
		this.nodesField.setEditable(!locked);
		this.peersField.setEditable(!locked);
		this.jobsField.setEditable(!locked);
		this.outputHostField.setEditable(!locked);
		this.outputPortField.setEditable(!locked);
		
		this.locked = locked;
	}
}