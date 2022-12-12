package com.cisco.c3m.est;

/*
 * ESTClient.java
 *
 *  Created on: July 1, 2014
 *      Author: foleyj
 *
 * Copyright (c) 2014 by cisco Systems, Inc.
 * All rights reserved.
 *
 * Note: This class adhers to the javadoc standard.  Be careful when adding comments.
 */

import java.lang.String;
import java.lang.StringBuilder;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.cert.CertificateFactory;
import java.security.CodeSource;
import java.security.KeyPair;
import java.security.InvalidKeyException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;

import com.cisco.c3m.est.PKCS10CertificateRequest.Encoding;

import java.net.URISyntaxException;
import java.nio.charset.Charset;

/**
 * This class is used to perform client-side EST operations as defined in RFC
 * 7030. It provides the ability to retrieve the latest trust anchor from an EST
 * server, enroll a new certificate from the EST server, or renew an existing
 * certificate.
 * 
 * @author foleyj
 * 
 */
public class ESTClient {
	private String mServerName;
	private int mPort;
	private String mHTTPUserName;
	private String mHTTPPassword;
	private String mSRPUserName;
	private String mSRPPassword;
	private X509Certificate[] mTACerts;
	private X509Certificate mAuthCert;
	private KeyPair mAuthKey;
	private static int mMaxCertLength;
	
	private static String CISCO_JEST_VER_MAJOR = "0";
	private static String CISCO_JEST_VER_MINOR = "0";
	private static String CISCO_JEST_VER_MICRO = "0";
	private static String CISCO_JEST_SVN_REV = "0";
	
	static {
		/*
		 * Attempt to retrieve the version information from the manifest.
		 * This works on Linux, but needs to be tested on Windows/Mac.
		 */
		try {
			CodeSource codeSource = ESTClient.class.getProtectionDomain().getCodeSource();
			File f = new File(codeSource.getLocation().toURI().getPath());
			JarFile jarFile = new JarFile(f);
			Manifest manifest = jarFile.getManifest();
			Attributes manifestContents = manifest.getMainAttributes();
			CISCO_JEST_VER_MAJOR = manifestContents.getValue("Version-Major");
			CISCO_JEST_VER_MINOR = manifestContents.getValue("Version-Minor");
			CISCO_JEST_VER_MICRO = manifestContents.getValue("Version-Micro");
			CISCO_JEST_SVN_REV = manifestContents.getValue("SVN-Revision");
			jarFile.close();
		} catch (IOException | URISyntaxException e) {
			//We will silent discard the error
			//System.out.println("Unable to collect revision info from manifest");
		} 	
	}

	public enum AuthMode {
		/**
		 * Enables only HTTP authentication.  No authentication at the TLS layer will occur.
		 * Both HTTP digest and basic authentication are supported.  The EST server determines
		 * whether basic or digest auth will be used.  The setHTTPCredentials()
		 * method must be invoked prior to enrolling.
		 */
		authHTTPonly,
		/**
		 * Enables SRP authentication at the TLS layer.  The setSRPCredentials()
		 * method must be invoked prior to enrolling.  HTTP authentication is optional in this mode.
		 * Use setHTTPCredentials() to enable HTTP auth when using this mode.  Please
		 * note that SRP is not allowed in FIPS mode, which is a constraint imposed by OpenSSL.
		 */
		authSRP,
		/**
		 * Enables certificate based authentication at the TLS layer.  The setTLSAuthenticationCredentials() 
		 * method must be invoked prior to enrolling.  HTTP authentication is optional in this mode.
		 * Use setHTTPCredentials() to enable HTTP auth when using this mode.  
		 */
		authTLS
	};

	public enum NativeLogLevel {
		/**
		 * Enable logging of only errors in the native code
		 */
		logErrors,
		/**
		 * Enables logging of errors and warnings in the native code
		 */
		logWarnings,
		/**
		 * Enables logging of errors, warnings, and informational messages in
		 * the native code
		 */
		logFull
	};

	/**
	 * This constructor creates an instance of the this class.
	 */
	public ESTClient() {
	}

	/**
	 * This method returns a String that contains the JEST version. 
	 */
	public static String getVersion () {
		return new String("JEST " + CISCO_JEST_VER_MAJOR + "." + CISCO_JEST_VER_MINOR + "." + 
					CISCO_JEST_VER_MICRO + " [SVN Revision: " + CISCO_JEST_SVN_REV + "]");
	}
	
	/**
	 * This method enables FIPS mode in the underlying crypto module. Be aware
	 * that FIPS mode may already be enabled if your JVM is using the CiscoJ JCA
	 * crypto provider. This class leverages OpenSSL for crypto operations and
	 * FIPS compliance. CiscoJ leverages OpenSSL as well. When linked as a
	 * shared library, only one copy of OpenSSL will be resident in memory
	 * under the JVM. Once OpenSSL is put into FIPS mode, it will impact all
	 * users within the process address space. This may impact crypto operations outside
	 * the scope of EST since FIPS mode disallows certain algorithms, such as
	 * MD5, RC4, SRP, and others.
	 */
	public void enableFIPS() throws Exception {
		int rv;

		rv = enable_fips();
		if (rv != 0) {
			throw new Exception(
					"FIPS mode failed, crypto module is not operating in FIPS mode");
		}
	}

	/**
	 * This method sets the native layer logging level output generated by
	 * libEST. Three possible values may be configured: errors, warnings, or
	 * full. The default logging level output is errors if this method is not
	 * invoked. The log output from the native layer is sent to stderr.
	 * 
	 * @param lvl
	 *            The logging level desired by the application.
	 */
	public void setNativeLogLevel(NativeLogLevel lvl) {
		switch (lvl) {
		case logErrors:
			enable_logs_errors();
			break;
		case logWarnings:
			enable_logs_warnings();
			break;
		case logFull:
			enable_logs_info();
			break;
		default:
			/* do nothing since it's impossible to hit this path */
		}
	}
	
	/**
	 * This method is used to get the maximum length for a certificate
	 * allowed by the native layer (in bytes).  
	 * 
	 * @return
	 * 		The maximum certificate size (in bytes) that is allowed.
	 */
	public static int getNativeMaxCertLength() {
		return (mMaxCertLength);
	}
	
	/**
	 * This method is used to change the maximum length of certificate allowed
	 * at the native layer.  Normally the default maximum value is sufficient.
	 * Some use cases may require the maximum allowed length to be increased.
	 * Changes to this value will apply to all instances of the ESTClient class.
	 * This maximum value is used for the certificate returned when invoking
	 * sendSimpleEnrollRequest().  It's also used for the certificates returned
	 * when invoking fetchLatestCACerts().
	 * 
	 * @param value
	 * 			The maximum length in bytes allowed for the certificate response
	 */
	public static void setNativeMaxCertLength(int value) {
		int rc;
		
		rc = set_max_cert_length(value);
		if (rc == 0) {
			mMaxCertLength = value;
		}
	}

	/**
	 * This method configures the trust anchor that will be used by EST to
	 * verify the identity of the EST server when performing operations against
	 * the server.  This should be invoked prior to any EST operations, such as
	 * invoking fetchLatestCACerts() or sendSimpleEnrollRequest().
	 * 
	 * @param certs
	 *            Array of X509Certificate objects that are trusted. These are
	 *            typically exported from the Java Keystore used by the
	 *            application.
	 */
	public void setTrustAnchor(X509Certificate[] certs) {
		mTACerts = certs;
	}
	
	/**
	 * This method configures the trust anchor that will be used by EST to
	 * verify the identity of the EST server when performing operations against
	 * the server.  This should be invoked prior to any EST operations, such as
	 * invoking fetchLatestCACerts() or sendSimpleEnrollRequest().
	 * 
	 * @param certs
	 *            ArrayList of X509Certificate objects that are trusted. These are
	 *            typically exported from the Java Keystore used by the
	 *            application.
	 */
	public void setTrustAnchor(ArrayList<X509Certificate> certs) {
		if (certs != null) {
			mTACerts = new X509Certificate[certs.size()];
			int i = 0;
			for (X509Certificate c: certs) {
				mTACerts[i++] = c;
			}
		} else {
			mTACerts = null;
		}
	}

	/**
	 * This method configures the name of the EST server.  The IP address of the server can
	 * be used in place of the server name if desired.
	 * 
	 * @param server
	 *            Either the IP address for domain name of the EST server.
	 */
	public void setServerName(String server) {
		mServerName = server;
	}

	/**
	 * This method configures the TCP port number used by the EST server.
	 * 
	 * @param port
	 *      The TCP port number used by the EST server.
	 *          
	 * @throws IllegalArgumentException
	 * 		Thrown when an invalid TCP port number is requested.  The maximum allowed TCP port number is 65535.
	 */
	public void setServerPort(int port) {
		if (port > 65535 || port <= 0) {
			throw new IllegalArgumentException("Invalid TCP port number");
		}
		mPort = port;
	}

	/**
	 * This method configures the user name and password to use for HTTP authentication.
	 * JEST supports both basic and digest authentication.  The EST server 
	 * determines whether basic or digest auth is used.
	 * 
	 * @param user
	 *            The user name to use for HTTP authentication.
	 * @param password
	 *            The password to to use for HTTP authentication.
	 */
	public void setHTTPCredentials(String user, String password) {
		if (user == null || password == null) {
			throw new IllegalArgumentException("User name and password may not be null");
		}
		mHTTPUserName = user;
		mHTTPPassword = password;
	}

	/**
	 * This method configures the user name and password to use for SRP authentication.
	 * 
	 * @param user
	 *            The user name to use for SRP authentication.
	 * @param password
	 *            The password to to use for SRP authentication.
	 */
	public void setSRPCredentials(String user, String password) {
		if (user == null || password == null) {
			throw new IllegalArgumentException("User name and password may not be null");
		}
		mSRPUserName = user;
		mSRPPassword = password;
	}

	/**
	 * This method configures the certificate and private key that will be
	 * used for TLS client authentication.  This method must be invoked
	 * prior to enrolling when the authTLS mode is used. 
	 * 
	 * @param cert
	 *            The certificate to use for TLS client authentication.
	 * @param key
	 *            The private key associated with the certificate.
	 */
	public void setTLSAuthenticationCredentials (X509Certificate cert, KeyPair key) {
		if (cert == null || key == null) {
			throw new IllegalArgumentException("Certificate and key may not be null");
		}
		if (key.getPrivate() == null) {
			throw new IllegalArgumentException("keypair contains null private key");			
		}
		mAuthCert = cert;
		mAuthKey = key;
	}
	
	private String convertX509CertToPEM(X509Certificate cert) 
			throws CertificateEncodingException, IOException {
		StringBuilder lCert = new StringBuilder();
		byte[] encodedCert = cert.getEncoded();
		byte[] pem = Base64.encode(encodedCert);
		String pemstr = new String(pem, Charset.forName("UTF-8"));
		lCert.append("-----BEGIN CERTIFICATE-----");
		lCert.append(System.getProperty("line.separator"));
		lCert.append(pemstr);
		lCert.append(System.getProperty("line.separator"));
		lCert.append("-----END CERTIFICATE-----");
		lCert.append(System.getProperty("line.separator"));	
		return (lCert.toString());
	}
	
	
	/*
	 * This function converts the array of X509 certs in the mTACerts member to
	 * a PEM encoded representation. Java uses DER encoding for X509
	 * certificates. We convert these to PEM to allow the certs to be used by
	 * libEST in the JNI layer.
	 */
	private byte[] convertTrustAnchorCertsToPEM()
			throws CertificateEncodingException, IOException {
		StringBuilder lCerts = new StringBuilder();
		int i;
		
		if (mTACerts == null || mTACerts.length <= 0) {
			return new byte[1];
		}

		for (i = 0; i < mTACerts.length; i++) {
			lCerts.append(convertX509CertToPEM(mTACerts[i]));
		}
		lCerts.append(0);
		/*
		 * We append an extra byte to allow room for a NULL terminator
		 * to be added at the JNI layer
		 */
		lCerts.append(0);
		
		return lCerts.toString().getBytes(Charset.forName("UTF-8"));
	}

	/*
	 * This method implements RFC 7030 /simpleenroll using either SRP or HTTP
	 * authentication
	 */
	/**
	 * This method implements the /simpleenroll operation as defined in RFC 7030
	 * when HTTP and/or SRP authentication is desired.
	 * 
	 * @param csr
	 *            This is a reference to the PKCS10 certificate request that
	 *            should have been created earlier.
	 * @param mode
	 *            This specifies the EST authentication method to enable when
	 *            performing the enroll operation.
	 * @param key
	 *            This is the public/private key pair that will be used to sign
	 *            the PKCS10 request.
	 * @param disablePoP
	 *            This parameter allows the application to disable linking of the
	 *            Proof-of-Possession. See section 3.5 of RFC 7030 for more detail.
	 *            
	 * @return Returns the X.509 certificate provided by the EST server.
	 * 
	 * @throws CertificateEncodingException
	 * 			  This exception is thrown by Java when the X509 certificate can not 
	 *            be DER encoded.  See the JDK documentation for more details on 
	 *            this exception.
	 * @throws IOException
	 * 			  This exception is thrown when base64 encoding of the certificate fails.
	 * @throws CertificateException
	 *            This exception indicates one of a variety of certificate problems.
	 * @throws EncodingException
	 * 	 		  This exception is thrown when DER or PEM encoding is requested.  Only
	 * 			  DER and PEM encoding are supported.
	 * @throws InvalidKeyException
	 *            This exception occurs when there is a problem with the private key used
	 *            to sign the CSR.  Check stderr for details on the cause of the error.	 
	 * @throws EnrollException
	 *            This exception indicates a problem occurred in the native libest library when
	 *            attempting to enroll the CSR.  Check stderr for details of the error generated
	 *            by libest.  
	 * @throws EnrollRetryAfterException
	 *            This exception indicates the EST server is not automatically approving the
	 *            enrollment of the CSR.  The EST server is requesting the EST client retry
	 *            the enrollment request at a later time to give the security officer time
	 *            to approve the request.  The PKCS10 CSR and key pair should be persisted and
	 *            retained for later use when the retry enroll is attempted.
	 * @throws BufferSizeException
	 * 			  This exception indicates the size of the new certificate buffer was too 
	 *            small.  Use setNativeMaxCertLength() to increase the buffer size.
	 */
	public X509Certificate sendSimpleEnrollRequest(
			PKCS10CertificateRequest csr, AuthMode mode, KeyPair key,
			Boolean disablePoP) throws CertificateEncodingException,
			IOException, CertificateException, EncodingException,
			InvalidKeyException, EnrollException, EnrollRetryAfterException,
			BufferSizeException {

		int lNewCertLength;
		byte certsPEM[];
		int PoPOFF = 0;
		byte[] tempCert = new byte[mMaxCertLength];

		if (disablePoP.equals(Boolean.TRUE)) {
			PoPOFF = 1;
		}

		/*
		 * Get PEM encoded Trust Anchor and make
		 * sure it's null terminated.  
		 */
		certsPEM = convertTrustAnchorCertsToPEM();
		certsPEM[certsPEM.length-1] = 0;
		

		/*
		 *  Convert keypair to DER encoded byte array
		 */
		byte[] encodedKey = key.getPrivate().getEncoded();

		/*
		 *  Invoke the JNI enroll method
		 */
		switch (mode) {
		case authHTTPonly:
			lNewCertLength = send_http_auth_enroll_request(certsPEM,
				encodedKey, mServerName, mPort, mHTTPUserName, mHTTPPassword,
				PoPOFF, csr.getBytes(Encoding.DER), tempCert);
			break;
		case authSRP:
			if (mSRPUserName == null || mSRPPassword == null) {
				throw new EnrollException("SRP user and password may not be null.");
			} else { 
				lNewCertLength = send_srp_auth_enroll_request(certsPEM,
					encodedKey, mServerName, mPort, mSRPUserName, mSRPPassword,
					mHTTPUserName, mHTTPPassword,
					PoPOFF, csr.getBytes(Encoding.DER), tempCert);
			}
			break;
		case authTLS:
			if (mAuthCert == null) {
				throw new EnrollException("TLS authentiation cert is null, invoke setTLSAuthenticationCredentials() to resolve.");
			}
			if (mAuthKey == null) {
				throw new EnrollException("TLS authentiation key is null, invoke setTLSAuthenticationCredentials() to resolve.");
			}
			lNewCertLength = send_tls_auth_enroll_request(certsPEM, encodedKey, 
					mServerName, mPort, 
					mAuthCert.getEncoded(), mAuthKey.getPrivate().getEncoded(),
					mHTTPUserName, mHTTPPassword,
					PoPOFF, csr.getBytes(Encoding.DER), tempCert);
			break;
		default:
			lNewCertLength = 0;
			break;
		}
		if (lNewCertLength > 0) {
			byte[] newCert = Arrays.copyOf(tempCert, lNewCertLength);
			/*
			 * The cert is returned to us as a PEM encoded cert. Convert it to
			 * an X509Certificate object.
			 */
			InputStream inStream = new ByteArrayInputStream(newCert);
			CertificateFactory cf = CertificateFactory.getInstance("X.509");
			X509Certificate cert = (X509Certificate) cf
					.generateCertificate(inStream);
			inStream.close();
			return cert;
		} else {
			return null;
		}
	}
	
	/*
	 * This method implements RFC 7030 /simplereenroll using either SRP or HTTP
	 * authentication
	 */
	/**
	 * This method implements the /simplereenroll operation as defined in RFC 7030
	 * when HTTP and/or SRP authentication is desired.
	 * 
	 * @param oldCert
	 *            This is a reference to the X509 certificate that will be renewed. 
	 * @param mode
	 *            This specifies the EST authentication method to enable when
	 *            performing the reenroll operation.
	 * @param key
	 *            This is the public/private key pair that will be used to sign
	 *            the PKCS10 request.
	 * @param disablePoP
	 *            This parameter allows the application to disable linking of the
	 *            Proof-of-Possession. See section 3.5 of RFC 7030 for more detail.
	 *            
	 * @return Returns the X.509 certificate provided by the EST server.
	 * 
	 * @throws CertificateEncodingException
	 * 			  This exception is thrown by Java when the X509 certificate can not 
	 *            be DER encoded.  See the JDK documentation for more details on 
	 *            this exception.
	 * @throws IOException
	 * 			  This exception is thrown when base64 encoding of the certificate fails.
	 * @throws CertificateException
	 *            This exception indicates one of a variety of certificate problems.
	 * @throws EncodingException
	 * 	 		  This exception is thrown when DER or PEM encoding is requested.  Only
	 * 			  DER and PEM encoding are supported.
	 * @throws InvalidKeyException
	 *            This exception occurs when there is a problem with the private key used
	 *            to sign the CSR.  Check stderr for details on the cause of the error.	 
	 * @throws EnrollException
	 *            This exception indicates a problem occurred in the native libest library when
	 *            attempting to reenroll the certificate.  Check stderr for details of the error generated
	 *            by libest.  
	 * @throws EnrollRetryAfterException
	 *            This exception indicates the EST server is not automatically approving the
	 *            enrollment of the CSR.  The EST server is requesting the EST client retry
	 *            the enrollment request at a later time to give the security officer time
	 *            to approve the request.  The PKCS10 CSR and key pair should be persisted and
	 *            retained for later use when the retry reenroll is attempted.
	 * @throws BufferSizeException
	 * 			  This exception indicates the size of the new certificate buffer was too 
	 *            small.  Use setNativeMaxCertLength() to increase the buffer size.
	 */
	public X509Certificate sendSimpleReenrollRequest(
			X509Certificate oldCert, AuthMode mode, KeyPair key,
			Boolean disablePoP) throws CertificateEncodingException,
			IOException, CertificateException, EncodingException,
			InvalidKeyException, EnrollException, EnrollRetryAfterException,
			BufferSizeException {
		int lNewCertLength;
		byte certsPEM[];
		int PoPOFF = 0;
		byte[] tempCert = new byte[mMaxCertLength];

		if (disablePoP.equals(Boolean.TRUE)) {
			PoPOFF = 1;
		}

		/*
		 * Get PEM encoded Trust Anchor and make
		 * sure it's null terminated.  
		 */
		certsPEM = convertTrustAnchorCertsToPEM();
		certsPEM[certsPEM.length-1] = 0;
		

		/*
		 *  Convert keypair to DER encoded byte array
		 */
		byte[] encodedKey = key.getPrivate().getEncoded();

		/*
		 *  Invoke the JNI enroll method
		 */
		switch (mode) {
		case authHTTPonly:
			lNewCertLength = send_http_auth_reenroll_request(certsPEM,
				encodedKey, mServerName, mPort, mHTTPUserName, mHTTPPassword,
				PoPOFF, oldCert.getEncoded(), tempCert);
			break;
		case authSRP:
			if (mSRPUserName == null || mSRPPassword == null) {
				throw new EnrollException("SRP user and password may not be null.");
			} else { 
				lNewCertLength = send_srp_auth_reenroll_request(certsPEM,
					encodedKey, mServerName, mPort, mSRPUserName, mSRPPassword,
					mHTTPUserName, mHTTPPassword,
					PoPOFF, oldCert.getEncoded(), tempCert);
			}
			break;
		case authTLS:
			if (mAuthCert == null) {
				throw new EnrollException("TLS authentiation cert is null, invoke setTLSAuthenticationCredentials() to resolve.");
			}
			if (mAuthKey == null) {
				throw new EnrollException("TLS authentiation key is null, invoke setTLSAuthenticationCredentials() to resolve.");
			}
			lNewCertLength = send_tls_auth_reenroll_request(certsPEM, encodedKey, 
					mServerName, mPort, 
					mAuthCert.getEncoded(), mAuthKey.getPrivate().getEncoded(),
					mHTTPUserName, mHTTPPassword,
					PoPOFF, oldCert.getEncoded(), tempCert);
			break;
		default:
			throw new IllegalArgumentException("Invalid AuthMode");
		}
		if (lNewCertLength > 0) {
			byte[] newCert = Arrays.copyOf(tempCert, lNewCertLength);
			/*
			 * The cert is returned to us as a PEM encoded cert. Convert it to
			 * an X509Certificate object.
			 */
			InputStream inStream = new ByteArrayInputStream(newCert);
			CertificateFactory cf = CertificateFactory.getInstance("X.509");
			X509Certificate cert = (X509Certificate) cf
					.generateCertificate(inStream);
			inStream.close();
			return cert;
		} else {
			return null;
		}
		
	}
	/**
	 * This method implements the /cacerts operation as defined in RFC 7030.
     * This retrieves the latest CA certificates from the EST server and
     * returns them as an ArrayList of X509Certificate objects.  The user of this
     * method should persist the CA certificates for future use.
     * 
     * The following methods should be invoked prior to this method:
     * setServerName(), setServerPort(), setTrustAnchor().
	 *            
	 * @return Returns ArrayList of X509Certificate objects containing trusted certs from the CA.
	 *         Returns null if no CA certs were received from the EST server.
	 * 
	 * @throws CertificateEncodingException
	 * 			  This exception is thrown by Java when the X509 certificate can not 
	 *            be DER encoded.  See the JDK documentation for more details on 
	 *            this exception.
	 * @throws IOException
	 * 			  This exception is thrown when base64 encoding/decoding of the certificate fails.
	 * @throws CACertsException
	 *            This exception indicates a problem occurred in the native libest library when
	 *            attempting to get the CA certificates.  Check stderr for details of the error generated
	 *            by libest.  
	 * @throws BufferSizeException
	 * 			  This exception indicates the size of the new certificates buffer was too 
	 *            small.  Use setNativeMaxCertLength() to increase the buffer size.
	 */
	public ArrayList<X509Certificate> fetchLatestCACerts() 
			throws IOException, CertificateEncodingException, CertificateException, CACertsException, BufferSizeException {
		byte certsPEM[];
		int lCertsLength;
		byte[] tempCert = new byte[mMaxCertLength];  
		ArrayList<X509Certificate> CACerts = new ArrayList<X509Certificate>();
		
		/*
		 * Get PEM encoded Trust Anchor and make
		 * sure it's null terminated.  
		 */
		certsPEM = convertTrustAnchorCertsToPEM();
		if (certsPEM.length >= 1) {
			certsPEM[certsPEM.length-1] = 0;
		} 

		/*
		 * Invoke JNI method to retrieve /cacerts using libest.
		 * This will return a byte array contain PEM
		 * encoded certs.
		 */
		lCertsLength = send_cacerts_request(certsPEM, mServerName, mPort, mSRPUserName, mSRPPassword, tempCert);
		if (lCertsLength > 0) {
			String newCerts = new String(tempCert);
			ByteArrayInputStream bs = new ByteArrayInputStream(newCerts.getBytes("UTF-8"));
			BufferedInputStream bis = new BufferedInputStream(bs);

			CertificateFactory cf = CertificateFactory.getInstance("X.509");

			/*
			 * Read each cert out of the response from the server
			 */
			while ((bis.available() > 0) && ((tempCert.length - bis.available()) < (lCertsLength - 1))) {
				X509Certificate cert = (X509Certificate) cf.generateCertificate(bis);
				CACerts.add(cert);
			}
			return CACerts;			
		} else {
			return null;			
		}
	}
	

	/*
	 * Everything below is the JNI layer definitions and mgmt
	 */
	static {
		System.loadLibrary("jest");
		/*
		 * Get the maximum allowed certificate length from the JNI layer and
		 * cache it locally.  This is needed later when allocating the byte
		 * array that will hold the new certificate.
		 */
		mMaxCertLength = get_max_cert_length();
	}

	private static native int send_http_auth_enroll_request(
			byte[] trusted_certs, byte[] keypair, String server_name,
			int port_num, String user_name, String user_pwd, int disable_pop,
			byte[] csr, byte[] new_cert);

	private static native int send_srp_auth_enroll_request(
			byte[] trusted_certs, byte[] keypair, String server_name,
			int port_num, String srp_user, String srp_pwd,
			String http_user, String http_pwd, int disable_pop,
			byte[] csr, byte[] new_cert);

	private static native int send_tls_auth_enroll_request(
			byte[] trusted_certs, byte[] keypair, 
			String server_name,	int port_num,
			byte[] auth_cert, byte[] auth_key,
			String http_user, String http_pwd, int disable_pop,
			byte[] csr, byte[] new_cert);

	private static native int send_http_auth_reenroll_request(
			byte[] trusted_certs, byte[] keypair, String server_name,
			int port_num, String user_name, String user_pwd, int disable_pop,
			byte[] old_cert, byte[] new_cert);

	private static native int send_srp_auth_reenroll_request(
			byte[] trusted_certs, byte[] keypair, String server_name,
			int port_num, String srp_user, String srp_pwd,
			String http_user, String http_pwd, int disable_pop,
			byte[] old_cert, byte[] new_cert);

	private static native int send_tls_auth_reenroll_request(
			byte[] trusted_certs, byte[] keypair, 
			String server_name,	int port_num,
			byte[] auth_cert, byte[] auth_key,
			String http_user, String http_pwd, int disable_pop,
			byte[] old_cert, byte[] new_cert);

	private static native int send_cacerts_request(
			byte[] trusted_certs, String server_name,
			int port_num, String srp_user, String srp_pwd,
			byte[] new_cert);
	
	private static native int enable_fips();

	private static native int enable_logs_errors();

	private static native int enable_logs_warnings();

	private static native int enable_logs_info();
	
	private static native int get_max_cert_length();

	private static native int set_max_cert_length(int value);

}
