package prerna.web.services.util;

import java.util.Hashtable;

import prerna.rdf.engine.api.IRemoteQueryable;
import prerna.util.DIHelper;

public class QueryResultHash {

	long runner = 1;
	Hashtable objHash = new Hashtable();
	static QueryResultHash hash = null;
	
	protected QueryResultHash()
	{
		// do nothing
	}
	
	public static QueryResultHash getInstance()
	{
		if(hash == null)
			hash = new QueryResultHash();
		return hash;
	}
	
	public String addObject(IRemoteQueryable maObject)
	{
		String key = DIHelper.getInstance().getProperty("ENGINE_GUID") + runner;
		maObject.setRemoteID(key);
		objHash.put(key, maObject);
		runner++;
		return key;
	}
	
	public Object getObject(String key)
	{
		return objHash.get(key);
	}
	
	public String setObject(String key, Object maObject)
	{
		objHash.put(key, maObject);
		return key;
	}

	public void cleanObject(String key)
	{
		objHash.remove(key);
	}

}
