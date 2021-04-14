//package prerna.semoss.web.services.saml;
//
//import java.io.IOException;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Map;
//
//import javax.servlet.ServletException;
//import javax.servlet.annotation.WebServlet;
//import javax.servlet.http.HttpServlet;
//import javax.servlet.http.HttpServletRequest;
//import javax.servlet.http.HttpServletResponse;
//
//import org.forgerock.guice.core.InjectorHolder;
//import org.forgerock.openam.audit.AuditEventFactory;
//import org.forgerock.openam.audit.AuditEventPublisher;
//import org.forgerock.openam.saml2.audit.SAML2Auditor;
//
//import com.sun.identity.saml.common.SAMLUtils;
//import com.sun.identity.saml2.common.SAML2Constants;
//import com.sun.identity.saml2.common.SAML2Exception;
//import com.sun.identity.saml2.common.SAML2Utils;
//import com.sun.identity.saml2.meta.SAML2MetaManager;
//import com.sun.identity.saml2.profile.SPCache;
//import com.sun.identity.saml2.profile.SPSSOFederate;
//
//import prerna.web.conf.util.SSOUtil;
//
///**
// * Servlet implementation class for SP Initiated SAML
// */
//@WebServlet("/SPSSOServlet")
//public class SPSSOServlet extends HttpServlet {
//	private static final long serialVersionUID = 1L;
//
//	public SPSSOServlet() {
//		super();
//	}
//
//	/**
//	 * The below doGet is called from the SSOFilter via the samlLogin/index.html page. 
//	 * Once called, it gets all the required openAM params from the SSOUtil class and 
//	 * does a call to the IDP with the SAML assertions.
//	 */
//	protected void doGet(HttpServletRequest request, HttpServletResponse response)
//			throws ServletException, IOException {
//		SSOUtil util = SSOUtil.getInstance();
//		if (!util.isConfigured()) {
//			util.setSSODeployURI((request).getRequestURI());
//			util.configureSSO(request, response);
//		}
//		initiateSSO(request, response);
//	}
//
//	public void initiateSSO(HttpServletRequest request, HttpServletResponse response) {
//		AuditEventPublisher aep = InjectorHolder.getInstance(AuditEventPublisher.class);
//		AuditEventFactory aef = InjectorHolder.getInstance(AuditEventFactory.class);
//		SAML2Auditor saml2Auditor = new SAML2Auditor(aep, aef, request);
//
//		saml2Auditor.setMethod("fedletSSOInit");
//		saml2Auditor.setSessionTrackingId(request.getSession().getId());
//		saml2Auditor.auditAccessAttempt();
//
//		// Retreive the Request Query Parameters
//		// metaAlias and idpEntiyID are the required query parameters
//		// metaAlias - Service Provider Entity Id
//		// idpEntityID - Identity Provider Identifier
//		// Query parameters supported will be documented.
//		String idpEntityID = null;
//		String metaAlias = null;
//		Map paramsMap = null;
//		try {
//			String reqID = request.getParameter("requestID");
//			if (reqID != null) {
//				// get the preferred idp
//				idpEntityID = SAML2Utils.getPreferredIDP(request);
//				paramsMap = (Map) SPCache.reqParamHash.get(reqID);
//				metaAlias = (String) paramsMap.get("metaAlias");
//				SPCache.reqParamHash.remove(reqID);
//			} else {
//				// this is an original request check
//				// get the metaAlias ,idpEntityID
//				// if idpEntityID is null redirect to IDP Discovery
//				// Service to retrieve.
//				metaAlias = request.getParameter("metaAlias");
//				if ((metaAlias == null) || (metaAlias.length() == 0)) {
//					SAML2MetaManager manager = new SAML2MetaManager();
//					List spMetaAliases = manager.getAllHostedServiceProviderMetaAliases("/");
//					if ((spMetaAliases != null) && !spMetaAliases.isEmpty()) {
//						// get first one
//						metaAlias = (String) spMetaAliases.get(0);
//					}
//					if ((metaAlias == null) || (metaAlias.length() == 0)) {
//						saml2Auditor.auditAccessFailure(String.valueOf(HttpServletResponse.SC_BAD_REQUEST),
//								SAML2Utils.bundle.getString("nullSPEntityID"));
//						SAMLUtils.sendError(request, response, HttpServletResponse.SC_BAD_REQUEST, "nullSPEntityID",
//								SAML2Utils.bundle.getString("nullSPEntityID"));
//
//						return;
//					}
//				}
//
//				idpEntityID = request.getParameter("idpEntityID");
//				paramsMap = SAML2Utils.getParamsMap(request);
//				// always use transient
//				List list = new ArrayList();
//				list.add(SAML2Constants.NAMEID_TRANSIENT_FORMAT);
//				paramsMap.put(SAML2Constants.NAMEID_POLICY_FORMAT, list);
//				if (paramsMap.get(SAML2Constants.BINDING) == null) {
//					// use POST binding
//					list = new ArrayList();
//					list.add(SAML2Constants.HTTP_POST);
//					paramsMap.put(SAML2Constants.BINDING, list);
//				}
//
//				if ((idpEntityID == null) || (idpEntityID.length() == 0)) {
//					// get reader url
//					String readerURL = SAML2Utils.getReaderURL(metaAlias);
//					if (readerURL != null) {
//						String rID = SAML2Utils.generateID();
//						String redirectURL = SAML2Utils.getRedirectURL(readerURL, rID, request);
//						if (redirectURL != null) {
//							paramsMap.put("metaAlias", metaAlias);
//							SPCache.reqParamHash.put(rID, paramsMap);
//							response.sendRedirect(redirectURL);
//							return;
//						}
//					}
//				}
//			}
//
//			if ((idpEntityID == null) || (idpEntityID.length() == 0)) {
//				SAML2MetaManager manager = new SAML2MetaManager();
//				List idpEntities = manager.getAllRemoteIdentityProviderEntities("/");
//				if ((idpEntities == null) || idpEntities.isEmpty()) {
//					saml2Auditor.auditAccessFailure(String.valueOf(HttpServletResponse.SC_BAD_REQUEST),
//							SAML2Utils.bundle.getString("idpNotFound"));
//					SAMLUtils.sendError(request, response, HttpServletResponse.SC_BAD_REQUEST, "idpNotFound",
//							SAML2Utils.bundle.getString("idpNotFound"));
//					return;
//				} else if (idpEntities.size() == 1) {
//					// only one IDP, just use it
//					idpEntityID = (String) idpEntities.get(0);
//				} else {
//					// multiple IDP configured in fedlet
//					saml2Auditor.auditAccessFailure(String.valueOf(HttpServletResponse.SC_BAD_REQUEST),
//							SAML2Utils.bundle.getString("nullIDPEntityID"));
//					SAMLUtils.sendError(request, response, HttpServletResponse.SC_BAD_REQUEST, "nullIDPEntityID",
//							SAML2Utils.bundle.getString("nullIDPEntityID"));
//					return;
//				}
//			}
//			SPSSOFederate.initiateAuthnRequest(request, response, metaAlias, idpEntityID, paramsMap, saml2Auditor);
//			saml2Auditor.auditAccessSuccess();
//			System.out.println("IDP call initiated successfully. Waiting for IDP callback.");
//		} catch (SAML2Exception sse) {
//			SAML2Utils.debug.error("Error sending AuthnRequest ", sse);
//			saml2Auditor.auditAccessFailure(String.valueOf(HttpServletResponse.SC_BAD_REQUEST),
//					SAML2Utils.bundle.getString("requestProcessingError"));
//			SAMLUtils.sendError(request, response, HttpServletResponse.SC_BAD_REQUEST, "requestProcessingError",
//					SAML2Utils.bundle.getString("requestProcessingError") + " " + sse.getMessage());
//			return;
//		} catch (Exception e) {
//			SAML2Utils.debug.error("Error processing Request ", e);
//			saml2Auditor.auditAccessFailure(String.valueOf(HttpServletResponse.SC_BAD_REQUEST),
//					SAML2Utils.bundle.getString("requestProcessingError"));
//			SAMLUtils.sendError(request, response, HttpServletResponse.SC_BAD_REQUEST, "requestProcessingError",
//					SAML2Utils.bundle.getString("requestProcessingError") + " " + e.getMessage());
//			return;
//		}
//
//	}
//
//}
