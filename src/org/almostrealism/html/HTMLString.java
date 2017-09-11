package org.almostrealism.html;

public class HTMLString implements HTMLContent {
	private String content;
	
	public HTMLString(String s) { this.content = s; }
	
	public String toHTML() { return content; }
}
