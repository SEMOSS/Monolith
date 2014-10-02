package prerna.auth;

import java.security.Principal;

public class SEMOSSPrincipal implements Principal {
	public static enum LOGIN_TYPES {Google, Facebook, Twitter, CAC};
	
	private String ID = "";
	private String NAME = "";
	private LOGIN_TYPES LOGIN_TYPE;
	private String EMAIL = "";
	private String PICTURE = "";
	
	public SEMOSSPrincipal(String id, String name, LOGIN_TYPES type, String email) {
		this.ID = id;
		this.NAME = name;
		this.LOGIN_TYPE = type;
		this.EMAIL = email;
	}
	
	public SEMOSSPrincipal(String id, String name, LOGIN_TYPES type, String email, String picture) {
		this.ID = id;
		this.NAME = name;
		this.LOGIN_TYPE = type;
		this.EMAIL = email;
		this.PICTURE = picture;
	}
	
	public String getId() {
		return this.ID;
	}
	
	@Override
	public String getName() {
		return this.NAME;
	}
	
	public String getLoginType() {
		return this.LOGIN_TYPE.toString();
	}
	
	public String getEmail() {
		return this.EMAIL;
	}
	
	public String getPicture() {
		return this.PICTURE;
	}
}
