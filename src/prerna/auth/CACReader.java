package prerna.auth;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.List;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.TerminalFactory;

import prerna.util.DIHelper;
import sun.security.pkcs11.SunPKCS11;

public class CACReader {

	public static void main(String[] args) {
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
			System.out.print("Enter PIN: ");
			String pin = reader.readLine();
			reader.close();
			
			CACReader t = new CACReader();
			
			t.showTerminals();
			t.getCACName(pin);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public String getCACName(String pin) {
		String ret = "";
		
		if(pin == null || pin.isEmpty()) {
			return ret;
		}
		
		try {
			KeyStore keyStore;
			String config = "showInfo = true" 
					+ "\nlibrary = " + DIHelper.getInstance().getProperty("BaseFolder") + "\\config\\CACauth\\opensc-pkcs11.dll"
					+ "\nname = SmartCard";
			SunPKCS11 providerMSCAPI = new SunPKCS11(new ByteArrayInputStream(config.getBytes("UTF-8")));
			Provider p = providerMSCAPI;
			Security.addProvider(p);
	
			System.out.println(p.getName());
			
			keyStore = KeyStore.getInstance("PKCS11", p);

			KeyStore.PasswordProtection pp = new KeyStore.PasswordProtection(pin.toCharArray());
			keyStore.load(null, pp.getPassword());
			System.out.println("-----------------------");
			Enumeration xaliasesEnum = keyStore.aliases();
			while (xaliasesEnum.hasMoreElements()) {
				Object alias = xaliasesEnum.nextElement();
				try {
					X509Certificate cert0 = (X509Certificate) keyStore.getCertificate(alias.toString());
					System.out.println("I am: " + cert0.getSubjectDN().getName());
					System.out.println(cert0.getIssuerDN().getName());

					X509Certificate cert = (X509Certificate) keyStore.getCertificate(alias.toString());

					String dn = cert.getSubjectX500Principal().getName();
					LdapName ldapDN = new LdapName(dn);
					for(Rdn rdn: ldapDN.getRdns()) {
						if(rdn.getType().equals("CN")) {
							ret = rdn.getValue().toString();
							System.out.println(rdn.getType() + " -> " + rdn.getValue() + "\n");
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			
//			System.out.println("-----------------------");
//			
//			Enumeration<String> aliasesEnum = keyStore.aliases();
//			if(aliasesEnum.hasMoreElements()) {
//				String alias = (String) aliasesEnum.nextElement();
//				System.out.println("Alias: " + alias);
//				X509Certificate cert = (X509Certificate) keyStore.getCertificate(alias);
//
//				String dn = cert.getSubjectX500Principal().getName();
//				LdapName ldapDN = new LdapName(dn);
//				for(Rdn rdn: ldapDN.getRdns()) {
//					System.out.println(rdn.getType() + " -> " + rdn.getValue());
//				}
//			}
		} catch(Exception e) {
			e.printStackTrace();
		}
		
		return ret;
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
