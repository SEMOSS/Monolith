package prerna.web.services.util;

import java.util.Hashtable;

public class InMemoryHash extends Hashtable{

	protected static InMemoryHash instance = new InMemoryHash();
	
	private InMemoryHash()
	{
		// do nothing
	}
	
	public static InMemoryHash getInstance()
	{
		return instance;
	}
	
}
