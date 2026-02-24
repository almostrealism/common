package io.almostrealism.html;

public class HTMLString implements HTMLContent {
	private String content;
	
	public HTMLString(String s) { this.content = s; }
	
	@Override
	public String toHTML() { return content; }
}
