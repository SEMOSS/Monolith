package prerna.web.conf.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.sun.identity.saml2.common.SAML2Utils;
import com.sun.identity.saml2.jaxb.metadata.AssertionConsumerServiceElement;
import com.sun.identity.saml2.jaxb.metadata.IDPSSODescriptorElement;
import com.sun.identity.saml2.jaxb.metadata.SPSSODescriptorElement;
import com.sun.identity.saml2.jaxb.metadata.SingleSignOnServiceElement;
import com.sun.identity.saml2.meta.SAML2MetaException;
import com.sun.identity.saml2.meta.SAML2MetaManager;

import prerna.util.Constants;
import prerna.util.Utility;

/**
 * 
 * This util class is where we do all the configuration.
 * The entire SAML configuration is created here. The 
 * implementation is very much specific to OpenAM.
 *
 */

public class SSOUtil {

	private static final Logger logger = LogManager.getLogger(SSOUtil.class);
	private static String fedletHomeDir = "";
	private static String deployuri = "";
	private static final Map<String, String> ssoMap = new HashMap<String, String>();
	private static final String HTTP_POST_BINDING = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST";
	private static boolean IS_CONFIGURED = false;
	public boolean is_authenticated = false;
	private static SSOUtil _instance;
	public static String SAML_REDIRECT_KEY = "SAML_REDIRECT_KEY";

	static {
		_instance = new SSOUtil();
	}
	
	
	private SSOUtil() {
		
	}

	/**
	 * This method sets up the SAML home directory and other env variables
	 * needed for the handshake to happen correctly between the SP and the 
	 * IDP.
	 */
	
	private void setFedletHomeDir() {
		logger.info("SSO directory setup starting");
		
		// Nice to have these setup in the env.
		System.setProperty("com.iplanet.am.cookie.name", "iPlanetDirectoryPro");
		System.setProperty("com.sun.identity.federation.fedCookieName", "fedCookie");
		
		// this is where we set the SAML home dir by getting the loc from RDF props.
		String confLocation = Utility.getDIHelperProperty(Constants.SAML_PROP_LOC).trim(); 
		logger.info("Directory is set to.. " + confLocation);
		System.getProperties().setProperty("com.sun.identity.fedlet.home", confLocation);
		
		// Check if the deployuri is valid, otherwise throw exception.
		if(deployuri == null || deployuri.isEmpty())
			throw new IllegalArgumentException("Deploy uri is null or empty. Cannot create SSO config.");
		int slashLoc = deployuri.indexOf("/", 1);
		if (slashLoc != -1) {
			deployuri = deployuri.substring(0, slashLoc);
		}
		
		// Set the saml config location
		fedletHomeDir = confLocation;
		if ((fedletHomeDir == null) || (fedletHomeDir.trim().length() == 0)) {
			if (System.getProperty("user.home").equals(File.separator)) {
				fedletHomeDir = File.separator + "fedlet";
			} else {
				fedletHomeDir = System.getProperty("user.home") + File.separator + "fedlet";
			}
		}
		logger.info("SSO directory setup complete.");
	}

	/*
	 * Set all the properties related to SSO in this method.
	 */
	private void setSSOProperties(HttpServletRequest request, HttpServletResponse response) throws IOException {
		
		// First set the SAML home dir.
		setFedletHomeDir();
		
		String spEntityID = null;
		String spMetaAlias = null;
		String idpEntityID = null;
		String idpMetaAlias = null;
		try {

			File dir = new File(Utility.normalizePath( fedletHomeDir ));
			File file = new File(Utility.normalizePath( fedletHomeDir + File.separator + "FederationConfig.properties"));
			logger.info("Fedlet config being used " + file);
			if (!dir.exists() || !dir.isDirectory()) {
				throw new FileNotFoundException("Configuration directory does not exist.");
			} else if (!file.exists()) {
				throw new FileNotFoundException("Configuration files do not exist.");
			} else {
				SAML2MetaManager manager = new SAML2MetaManager();
				List spEntities = manager.getAllHostedServiceProviderEntities("/");
				if ((spEntities != null) && !spEntities.isEmpty()) {
					// get first one
					spEntityID = (String) spEntities.get(0);
				}

				List spMetaAliases = manager.getAllHostedServiceProviderMetaAliases("/");
				if ((spMetaAliases != null) && !spMetaAliases.isEmpty()) {
					// get first one
					spMetaAlias = (String) spMetaAliases.get(0);
				}

				List trustedIDPs = new ArrayList();
				idpEntityID = request.getParameter("idpEntityID");
				if ((idpEntityID == null) || (idpEntityID.length() == 0)) {
					// find out all trusted IDPs
					List idpEntities = manager.getAllRemoteIdentityProviderEntities("/");
					if ((idpEntities != null) && !idpEntities.isEmpty()) {
						int numOfIDP = idpEntities.size();
						for (int j = 0; j < numOfIDP; j++) {
							String idpID = (String) idpEntities.get(j);
							if (manager.isTrustedProvider("/", spEntityID, idpID)) {
								trustedIDPs.add(idpID);
							}
						}
					}
				}

				// get the single IDP entity ID
				if (!trustedIDPs.isEmpty()) {
					idpEntityID = (String) trustedIDPs.get(0);
				}
				if ((spEntityID == null) || (idpEntityID == null)) {
					throw new SAML2MetaException(
							"Fedlet or remote Identity Provider metadata is not configured. Please configure SP/IDP first.");
				} else {
					// IDP base URL
					Map<String, String> idpMap = getIDPBaseUrlAndMetaAlias(idpEntityID, deployuri);
					String idpBaseUrl = (String) idpMap.get("idpBaseUrl");
					idpMetaAlias = (String) idpMap.get("idpMetaAlias");
					String fedletBaseUrl = getFedletBaseUrl(spEntityID, deployuri);
					
					// Put everything in the SSOMap.
					ssoMap.put("idpBaseUrl", idpBaseUrl);
					ssoMap.put("fedletBaseUrl", fedletBaseUrl);
					ssoMap.put("idpMetaAlias", idpMetaAlias);
					ssoMap.put("spEntityID", spEntityID);
					ssoMap.put("metaAlias", spMetaAlias);
					ssoMap.put("idpEntityID", idpEntityID);
					ssoMap.put("binding", HTTP_POST_BINDING);
					
					logger.info(Utility.cleanLogString("Fedlet (SP) Entity ID:" + spEntityID));
					logger.info(Utility.cleanLogString("IDP Entity ID:" + idpEntityID));
					
					//populateSSOMap(spMetaAlias, idpEntityID, HTTP_POST_BINDING);
				}
				IS_CONFIGURED = true;
			}
		} catch (SAML2MetaException se) {
			se.printStackTrace();
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, se.getMessage());
			return;
		}
	}

	/**
	 * This method gets the IDP related details and puts them into the map.
	 * 
	 * @param idpEntityID
	 * @param deployuri
	 * @return
	 */
	private Map<String, String> getIDPBaseUrlAndMetaAlias(String idpEntityID, String deployuri) {
		Map<String, String> returnMap = new HashMap<>();
		if (idpEntityID == null) {
			return returnMap;
		}
		try {
			// find out IDP meta alias
			SAML2MetaManager manager = new SAML2MetaManager();
			IDPSSODescriptorElement idp = manager.getIDPSSODescriptor("/", idpEntityID);
			List ssoServiceList = idp.getSingleSignOnService();
			if ((ssoServiceList != null) && (!ssoServiceList.isEmpty())) {
				Iterator i = ssoServiceList.iterator();
				while (i.hasNext()) {
					SingleSignOnServiceElement sso = (SingleSignOnServiceElement) i.next();
					if ((sso != null) && (sso.getBinding() != null)) {
						String ssoURL = sso.getLocation();
						int loc = ssoURL.indexOf("/metaAlias/");
						if (loc == -1) {
							continue;
						} else {
							returnMap.put("idpMetaAlias", ssoURL.substring(loc + 10));
							String tmp = ssoURL.substring(0, loc);
							loc = tmp.lastIndexOf("/");
							returnMap.put("idpBaseUrl", tmp.substring(0, loc));
							break;
						}
					}
				}
			}
		} catch (Exception e) {
			SAML2Utils.debug.error("Couldn't get IDP base url:", e);
		}
		return returnMap;
	}

	/**
	 * Helper method to read the config files and set the base urls saml style.
	 * 
	 * @param spEntityID
	 * @param deployuri
	 * @return
	 */
	private String getFedletBaseUrl(String spEntityID, String deployuri) {
		if (spEntityID == null) {
			return null;
		}
		String fedletBaseUrl = null;
		try {
			SAML2MetaManager manager = new SAML2MetaManager();
			SPSSODescriptorElement sp = manager.getSPSSODescriptor("/", spEntityID);
			List acsList = sp.getAssertionConsumerService();
			if ((acsList != null) && (!acsList.isEmpty())) {
				Iterator j = acsList.iterator();
				while (j.hasNext()) {
					AssertionConsumerServiceElement acs = (AssertionConsumerServiceElement) j.next();
					if ((acs != null) && (acs.getBinding() != null)) {
						String acsURL = acs.getLocation();
						int loc = acsURL.indexOf(deployuri + "/");
						if (loc == -1) {
							continue;
						} else {
							fedletBaseUrl = acsURL.substring(0, loc + deployuri.length());
							break;
						}
					}
				}
			}
		} catch (Exception e) {
			SAML2Utils.debug.error("Couldn't get fedlet base url:", e);
		}
		return fedletBaseUrl;
	}
	
	private void populateSSOMap(String spMetaAlias, String idpEntityID, String binding) {
		ssoMap.put("metaAlias", spMetaAlias);
		ssoMap.put("idpEntityID", idpEntityID);
		ssoMap.put("binding", binding);
	}
	
	public Map<String, String> getSSOMap(){
		return ssoMap;
	}
	
	public void setSSODeployURI(String uri) {
		deployuri = uri;
	}
	
	public void configureSSO(HttpServletRequest request, HttpServletResponse response) throws IOException {
		setSSOProperties(request, response);
	}
	
	public boolean isConfigured() {
		return IS_CONFIGURED;
	}
	
	public static SSOUtil getInstance() {
		return _instance;
	}
	
}
