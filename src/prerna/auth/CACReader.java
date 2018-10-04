package prerna.auth;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.smartcardio.Card;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.TerminalFactory;

import prerna.test.TestUtilityMethods;
import prerna.util.DIHelper;
import sun.security.pkcs11.SunPKCS11;

public class CACReader {

	public static void main(String[] args) {
		TestUtilityMethods.loadDIHelper("C:\\workspace\\Semoss_Dev\\RDF_Map.prop");

		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
			System.out.print("Enter PIN: ");
			String pin = reader.readLine();
			reader.close();
			
			CACReader t = new CACReader();
			
			t.showTerminals();
			t.processCAC(pin);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public void processCAC(String pin) {
		if(pin == null || pin.isEmpty()) {
			return;
		}
		
		try {
			/*
			 * Load the information to get the CAC data
			 */
			String config = "showInfo = true" 
					+ "\nlibrary = " + DIHelper.getInstance().getProperty("BaseFolder") + "\\config\\CACauth\\opensc-pkcs11.dll"
					+ "\nname = SmartCard";
			SunPKCS11 providerMSCAPI = new SunPKCS11(new ByteArrayInputStream(config.getBytes("UTF-8")));
			Provider p = providerMSCAPI;
			Security.addProvider(p);
			
			KeyStore keyStore = KeyStore.getInstance("PKCS11", p);
			KeyStore.PasswordProtection pp = new KeyStore.PasswordProtection(pin.toCharArray());
			keyStore.load(null, pp.getPassword());
			
			System.out.println("-----------------------");
			System.out.println("-----------------------");
			System.out.println("-----------------------");
			System.out.println("STARTING PROCESS");
			System.out.println("-----------------------");
			System.out.println("-----------------------");
			System.out.println("-----------------------");
			Enumeration<String> xaliasesEnum = keyStore.aliases();
			while (xaliasesEnum.hasMoreElements()) {
				Object alias = xaliasesEnum.nextElement();
				try {
					X509Certificate cert = (X509Certificate) keyStore.getCertificate(alias.toString());

					String fullName = cert.getSubjectX500Principal().getName();
					System.out.println("-----------------------");
					System.out.println("PROCESSING CERT >>>");
					System.out.println("REQUEST COMING FROM " + fullName);
					LdapName ldapDN;
					try {
						ldapDN = new LdapName(fullName);
						for(Rdn rdn: ldapDN.getRdns()) {
							if(rdn.getType().equals("CN")) {
								String value = rdn.getValue().toString();

								String[] split = value.split("\\.");
								if(split.length == 3 || split.length == 4) {
									// no idea if middle name is always there or not
									// just gonna validate the cac has length 10
									String cacId = split[split.length-1];
									if(cacId.length() >= 10) {
										// valid CAC!!!
										System.out.println("I HAVE CAC ID  :::: " +  cacId);
										System.out.println("I HAVE NAME  :::: " +  Stream.of(split).limit(split.length-2).collect(Collectors.joining(" ")));
										System.out.println("I HAVE TYPE  :::: " +  cert.getIssuerDN().getName());
										System.out.println("I HAVE EXPIRATION  :::: " +  (int) cert.getNotAfter().getTime());

										// try to get the email
										try {
											EMAIL_LOOP : for(List<?> altNames : cert.getSubjectAlternativeNames()) {
												for(Object alternative : altNames) {
													if(alternative instanceof String) {
														String altStr = alternative.toString();
														// really simple email check...
														if(altStr.contains("@")) {
															System.out.println("I HAVE EMAIL  :::: " + altStr);
															break EMAIL_LOOP;
														}
													}
												}
											}
										} catch (CertificateParsingException e) {
											e.printStackTrace();
										}
									} else {
										System.out.println("IGNORE THIS VALUE  ::: " + value +  " , WITH CAC ID  :::  " + cacId);
										continue;
									}
								} else {
									System.out.println("IGNORE THIS VALUE  ::: " + value);
									continue;
								}
							}
						}
						System.out.println("COMPLETED PROCESSING CERT >>>");
					} catch (InvalidNameException e) {
						System.out.println("ERROR WITH PARSING CAC INFORMATION!");
						e.printStackTrace();
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		} catch(Exception e) {
			e.printStackTrace();
		}
		
		System.out.println("-----------------------");
		System.out.println("-----------------------");
		System.out.println("-----------------------");
		System.out.println("FINISHED PROCESS");
	}
	
	public void showTerminals() {
		try {
			// Display the list of terminals
			TerminalFactory factory = TerminalFactory.getDefault();
			List<CardTerminal> terminals = factory.terminals().list();
			System.out.println("Terminals: " + terminals);

			// Use the first terminal
			CardTerminal terminal = terminals.get(0);

			// Connect with the card
			Card card = terminal.connect("*");
			System.out.println("Card: " + card);
		} catch(Exception e) {
			e.printStackTrace();
		}
	}
}
