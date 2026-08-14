#!/bin/bash

# Validate that a SEMOSS container is actually enforcing FIPS 140-3, rather than
# just carrying the config. Checks the provider stack, then exercises the
# primitives to prove the JVM behaves as configured.
#
# Usage:
#   ./fips-check.sh                      # against the running container named "semoss"
#   ./fips-check.sh my-container         # against another running container
#   ./fips-check.sh --image local-monolith-fips   # against an image, no container needed

set -e

if [ "$1" = "--image" ]; then
    RUNNER=(docker run --rm --entrypoint bash "${2:?--image needs an image name}")
else
    RUNNER=(docker exec "${1:-semoss}" bash)
fi

"${RUNNER[@]}" -c '
cat > /tmp/FipsCheck.java <<"EOF"
import java.security.*;
import java.security.cert.CertificateFactory;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.bouncycastle.crypto.CryptoServicesRegistrar;

public class FipsCheck {
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("registered providers, in order:");
        for (Provider p : Security.getProviders()) {
            System.out.println("    " + p.getName() + " " + p.getVersionStr());
        }
        System.out.println();

        check("approved-only mode is on", CryptoServicesRegistrar.isInApprovedOnlyMode());
        check("BCFIPS is provider 1", Security.getProviders()[0].getName().equals("BCFIPS"));
        check("SecureRandom comes from BCFIPS",
                SecureRandom.getInstance("DEFAULT").getProvider().getName().equals("BCFIPS"));
        check("SHA-256 comes from BCFIPS",
                MessageDigest.getInstance("SHA-256").getProvider().getName().equals("BCFIPS"));
        check("X.509 CertificateFactory available", CertificateFactory.getInstance("X.509") != null);
        check("BCFKS keystore type available", KeyStore.getInstance("BCFKS") != null);

        byte[] key = new byte[32], iv = new byte[12];
        SecureRandom rng = new SecureRandom();
        rng.nextBytes(key);
        rng.nextBytes(iv);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        gcm.doFinal("test".getBytes());
        check("AES-256-GCM via " + gcm.getProvider().getName(), true);

        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec("a-password-over-14-chars".toCharArray(), new byte[16], 1000, 256));
        check("PBKDF2WithHmacSHA256 works", true);

        // SP 800-132 floor. This is what rejects a short Postgres password during
        // SCRAM-SHA-256 auth, which surfaces as a confusing connection error.
        check("sub-112-bit password is rejected", throwsOn(() ->
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(new PBEKeySpec("short".toCharArray(), new byte[16], 1000, 256))));

        // Session IDs. Tomcat defaults to secureRandomAlgorithm="SHA1PRNG" with no
        // provider; BCFIPS does not implement SHA1PRNG, so that resolves to SUN and
        // JSESSIONID entropy comes from outside the validated module with nothing
        // logged. Resolve conf/context.xml the same way the Tomcat Digester does, so
        // this validates the whole chain: placeholder present, PROPERTY_SOURCE
        // enabled, and the env vars actually set.
        String ctx = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of(System.getenv("TOMCAT_HOME") + "/conf/context.xml")));
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("secureRandomProvider=\"([^\"]*)\"[\\s\\n]*secureRandomAlgorithm=\"([^\"]*)\"")
                .matcher(ctx);
        if (!m.find()) {
            check("conf/context.xml declares a <Manager> with session random attributes", false);
        } else {
            org.apache.tomcat.util.IntrospectionUtils.PropertySource[] env = {
                new org.apache.tomcat.util.digester.EnvironmentPropertySource() };
            String prov = org.apache.tomcat.util.IntrospectionUtils.replaceProperties(m.group(1), null, env, null);
            String alg = org.apache.tomcat.util.IntrospectionUtils.replaceProperties(m.group(2), null, env, null);
            check("session id provider resolves to BCFIPS (got \"" + prov + "\")", "BCFIPS".equals(prov));
            check("session id algorithm resolves to DEFAULT (got \"" + alg + "\")", "DEFAULT".equals(alg));
            check("EnvironmentPropertySource enabled in setenv.sh",
                    new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                            System.getenv("TOMCAT_HOME") + "/bin/setenv.sh")))
                            .contains("digester.PROPERTY_SOURCE"));
        }

        String trustStore = System.getProperty("javax.net.ssl.trustStore");
        if (trustStore != null) {
            // Resolved exactly the way sun.security.ssl.TrustStoreManager does, so
            // this fails here for the same reason it would fail at handshake time.
            String tsProvider = System.getProperty("javax.net.ssl.trustStoreProvider", "");
            String tsType = System.getProperty("javax.net.ssl.trustStoreType", "BCFKS");
            KeyStore ts = tsProvider.isEmpty()
                    ? KeyStore.getInstance(tsType)
                    : KeyStore.getInstance(tsType, tsProvider);
            ts.load(new java.io.FileInputStream(trustStore), "changeit".toCharArray());
            check("truststore loads via provider \"" + (tsProvider.isEmpty() ? "<search order>" : tsProvider)
                    + "\", " + ts.size() + " certs", ts.size() > 0);
            check("javax.net.ssl.trustStoreProvider is BCFIPS", "BCFIPS".equals(tsProvider));
        } else {
            check("javax.net.ssl.trustStore is set", false);
        }

        // The JDK default is ssl.KeyManagerFactory.algorithm=SunX509, which only
        // SunJSSE implements. With SunJSSE out of the provider list, anything that
        // builds an SSLContext from defaults throws NoSuchAlgorithmException on the
        // first outbound HTTPS call, not at startup. java.security.fips overrides
        // both to PKIX; these checks prove the override is in effect.
        check("KeyManagerFactory default algorithm is PKIX (got \""
                        + javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm() + "\")",
                "PKIX".equals(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()));
        check("TrustManagerFactory default algorithm is PKIX (got \""
                        + javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm() + "\")",
                "PKIX".equals(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()));
        // Reported as FAIL rather than allowed to propagate: a wrong algorithm name
        // throws NoSuchAlgorithmException here, and an uncaught stack trace would
        // skip every remaining check.
        check("default KeyManagerFactory resolves to BCJSSE", () ->
                javax.net.ssl.KeyManagerFactory
                        .getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
                        .getProvider().getName().equals("BCJSSE"));
        check("default TrustManagerFactory resolves to BCJSSE", () ->
                javax.net.ssl.TrustManagerFactory
                        .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
                        .getProvider().getName().equals("BCJSSE"));

        // The end-to-end version of the four checks above: this is the call that
        // HttpsURLConnection and every HTTP client make, and the one that reports
        // "Default SSLContext not available" when any link in the chain is wrong.
        check("default SSLContext comes from BCJSSE", () ->
                javax.net.ssl.SSLContext.getDefault().getProvider().getName().equals("BCJSSE"));
        check("default SSLSocketFactory builds", () ->
                javax.net.ssl.SSLSocketFactory.getDefault() != null);

        // Not a failure: SUN must stay registered because BCFIPS seeds its DRBG
        // from it (drop SUN and provider lookup recurses into StackOverflowError).
        // SUN also exposes MD5, so approved-only mode cannot block MD5 for
        // application code. Non-approved digest use has to be caught by code
        // review, not at runtime.
        String md5 = "unavailable";
        try {
            md5 = MessageDigest.getInstance("MD5").getProvider().getName();
        } catch (NoSuchAlgorithmException e) {
            // left as unavailable
        }
        System.out.println("    [note] MD5 is served by: " + md5
                + (md5.equals("SUN") ? "  <- reachable by app code, audit source for MD5 use" : ""));

        System.out.println();
        System.out.println(failures == 0
                ? "RESULT: FIPS mode active, " + (Security.getProviders().length) + " providers"
                : "RESULT: " + failures + " check(s) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    interface Action { void run() throws Exception; }

    interface Condition { boolean test() throws Exception; }

    static boolean throwsOn(Action a) {
        try {
            a.run();
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    // A throwing condition is a failed condition, and the exception is what
    // explains it - printing it here keeps the run going instead of aborting.
    static void check(String label, Condition c) {
        try {
            check(label, c.test());
        } catch (Throwable t) {
            check(label + " -> " + t, false);
        }
    }

    static void check(String label, boolean ok) {
        System.out.println((ok ? "    [PASS] " : "    [FAIL] ") + label);
        if (!ok) {
            failures++;
        }
    }
}
EOF
. $TOMCAT_HOME/bin/setenv.sh
# Tomcat libs are needed for IntrospectionUtils / EnvironmentPropertySource so the
# session id check resolves conf/context.xml exactly the way the Digester does.
exec java -cp "$CLASSPATH:$TOMCAT_HOME/lib/*:$TOMCAT_HOME/bin/tomcat-juli.jar" $CATALINA_OPTS /tmp/FipsCheck.java
'
