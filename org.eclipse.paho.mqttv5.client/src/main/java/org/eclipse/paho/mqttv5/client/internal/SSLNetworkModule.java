/*******************************************************************************
 * Copyright (c) 2009, 2019 IBM Corp.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution. 
 *
 * The Eclipse Public License is available at 
 *    https://www.eclipse.org/legal/epl-2.0
 * and the Eclipse Distribution License is available at 
 *   https://www.eclipse.org/org/documents/edl-v10.php
 *
 * Contributors:
 *    Dave Locke - initial API and implementation and/or initial documentation
 */
package org.eclipse.paho.mqttv5.client.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.eclipse.paho.mqttv5.client.logging.Logger;
import org.eclipse.paho.mqttv5.client.logging.LoggerFactory;
import org.eclipse.paho.mqttv5.common.MqttException;

/**
 * A network module for connecting over SSL.
 */
public class SSLNetworkModule extends TCPNetworkModule {
	private static final String CLASS_NAME = SSLNetworkModule.class.getName();
	private Logger log = LoggerFactory.getLogger(LoggerFactory.MQTT_CLIENT_MSG_CAT, CLASS_NAME);

	private String[] enabledCiphers;
	private int handshakeTimeoutSecs;
	private HostnameVerifier hostnameVerifier;
	private boolean httpsHostnameVerificationEnabled = true;

	private String host;
	private int port;

	/**
	 * Constructs a new SSLNetworkModule using the specified host and port. The
	 * supplied SSLSocketFactory is used to supply the network socket.
	 * 
	 * @param factory
	 *            the {@link SSLSocketFactory} to be used in this SSLNetworkModule
	 * @param host
	 *            the Hostname of the Server
	 * @param port
	 *            the Port of the Server
	 * @param resourceContext
	 *            Resource Context
	 */
	public SSLNetworkModule(SSLSocketFactory factory, String host, int port, String resourceContext) {
		super(factory, host, port, resourceContext);
		this.host = host;
		this.port = port;
		log.setResourceName(resourceContext);
	}

	/**
	 * Returns the enabled cipher suites.
	 * 
	 * @return a string array of enabled Cipher suites
	 */
	public String[] getEnabledCiphers() {
		return enabledCiphers;
	}

	/**
	 * Sets the enabled cipher suites on the underlying network socket.
	 * 
	 * @param enabledCiphers
	 *            a String array of cipher suites to enable
	 */
	public void setEnabledCiphers(String[] enabledCiphers) {
		final String methodName = "setEnabledCiphers";
		this.enabledCiphers = enabledCiphers;
		if ((socket != null) && (enabledCiphers != null)) {
			if (log.isLoggable(Logger.FINE)) {
				String ciphers = "";
				for (int i = 0; i < enabledCiphers.length; i++) {
					if (i > 0) {
						ciphers += ",";
					}
					ciphers += enabledCiphers[i];
				}
				// @TRACE 260=setEnabledCiphers ciphers={0}
				log.fine(CLASS_NAME, methodName, "260", new Object[] { ciphers });
			}
			((SSLSocket) socket).setEnabledCipherSuites(enabledCiphers);
		}
	}

	public void setSSLhandshakeTimeout(int timeout) {
		super.setConnectTimeout(timeout);
		this.handshakeTimeoutSecs = timeout;
	}

	public HostnameVerifier getSSLHostnameVerifier() {
		return hostnameVerifier;
	}

	public void setSSLHostnameVerifier(HostnameVerifier hostnameVerifier) {
		this.hostnameVerifier = hostnameVerifier;
	}

	public boolean isHttpsHostnameVerificationEnabled() {
		return httpsHostnameVerificationEnabled;
	}

	public void setHttpsHostnameVerificationEnabled(boolean httpsHostnameVerificationEnabled) {
		this.httpsHostnameVerificationEnabled = httpsHostnameVerificationEnabled;
	}

	public void start() throws IOException, MqttException {
		super.start();
		setEnabledCiphers(enabledCiphers);
		int soTimeout = socket.getSoTimeout();
		// RTC 765: Set a timeout to avoid the SSL handshake being blocked indefinitely
		socket.setSoTimeout(this.handshakeTimeoutSecs * 1000);

		// AVN: javax.net.ssl.SNIHostName and SSLParameters.setEndpointIdentificationAlgorithm
		// only exist from Java 8 / Android 7.0 (API 24). On older Android (CVR155, API 22) set
		// the SNI host name through the platform socket's own setter and check the host name
		// after the handshake instead.
		HostnameVerifier verifier = hostnameVerifier;
		if (SNI_API_AVAILABLE) {
			ModernTls.configure((SSLSocket) socket, host, this.httpsHostnameVerificationEnabled);
		} else {
			setLegacySniHostname((SSLSocket) socket, host);
			if (this.httpsHostnameVerificationEnabled && verifier == null) {
				verifier = HttpsURLConnection.getDefaultHostnameVerifier();
			}
		}

		((SSLSocket) socket).startHandshake();
		if (verifier != null) {
			SSLSession session = ((SSLSocket) socket).getSession();
			if(!verifier.verify(host, session)) {
				session.invalidate();
				socket.close();
				throw new SSLPeerUnverifiedException("Host: " + host + ", Peer Host: " + session.getPeerHost());
			}
		}
		// reset timeout to default value
		socket.setSoTimeout(soTimeout);
	}

	public String getServerURI() {
		return "ssl://" + host + ":" + port;
	}

	private static final boolean SNI_API_AVAILABLE = isSniApiAvailable();

	private static boolean isSniApiAvailable() {
		try {
			Class.forName("javax.net.ssl.SNIHostName");
			SSLParameters.class.getMethod("setEndpointIdentificationAlgorithm", String.class);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Android's TLS sockets before API 24 take the SNI host name through a
	 * {@code setHostname(String)} method on the implementation class. A socket made with
	 * the no-argument {@code createSocket()} has no host name, so without this no SNI is
	 * sent. Best effort: other platforms don't have the method.
	 */
	private static void setLegacySniHostname(SSLSocket socket, String host) {
		try {
			socket.getClass().getMethod("setHostname", String.class).invoke(socket, host);
		} catch (Throwable t) {
			// No setter on this platform; the handshake goes ahead without SNI
		}
	}

	/** Kept in its own class so that nothing here is loaded where the Java 8 TLS API is missing. */
	private static final class ModernTls {
		static void configure(SSLSocket socket, String host, boolean httpsHostnameVerification) {
			// SNI support.  Should be automatic under some circumstances - not all, apparently
			SSLParameters sslParameters = socket.getSSLParameters();
			List<SNIServerName> sniHostNames = new ArrayList<SNIServerName>(1);
			sniHostNames.add(new SNIHostName(host));
			sslParameters.setServerNames(sniHostNames);

			// If default Hostname verification is enabled, use the same method that is used with HTTPS
			if (httpsHostnameVerification) {
				sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
			}

			socket.setSSLParameters(sslParameters);
		}
	}
}
