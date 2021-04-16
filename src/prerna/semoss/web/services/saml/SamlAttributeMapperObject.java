package prerna.semoss.web.services.saml;

/**
 * This class will be used to record any rules that are associated with the saml
 * fields. For example from here we get to know which saml fields are mandatory
 * for the application or need default values. Other rules can also be added to
 * this class as needed. For example, the userId is a mandatory field for the
 * application, or we might need to substitute any field with a default value.
 * This kind of metadata is recorded here so that during actual processing of
 * the saml, dynamic checks can be implemented in the application code.
 * 
 * It also has an enum of the application keys which are specified in the
 * properties file. If any new property is being added to the property file,
 * then make sure to add it in this enum as well.
 *
 */
public class SamlAttributeMapperObject {

	enum SAML_APPLICATION_KEYS {
		firstName, lastName, middleName, uid, mail
	};

	private String applicationKey;
	private String assertionKey;
	private boolean isMandatory;
	private String defaultValue;

	public SamlAttributeMapperObject() {

	}

	public String getApplicationKey() {
		return applicationKey;
	}

	public void setApplicationKey(String applicationKey) {
		this.applicationKey = applicationKey;
	}

	public String getAssertionKey() {
		return assertionKey;
	}

	public void setAssertionKey(String assertionKey) {
		this.assertionKey = assertionKey;
	}

	public boolean isMandatory() {
		return isMandatory;
	}

	public void setMandatory(String isMandatory) {
		if (isMandatory.equalsIgnoreCase("false")) {
			this.isMandatory = false;
		} else {
			this.isMandatory = true;
		}
	}

	public String getDefaultValue() {
		return defaultValue;
	}

	public void setDefaultValue(String defaultValue) {
		if (defaultValue != null && !defaultValue.isEmpty()) {
			this.defaultValue = defaultValue.trim();
		}
	}

}
