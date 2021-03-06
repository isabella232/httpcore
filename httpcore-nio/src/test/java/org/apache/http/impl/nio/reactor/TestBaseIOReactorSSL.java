/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.http.impl.nio.reactor;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.nio.DefaultNHttpClientConnection;
import org.apache.http.impl.nio.DefaultNHttpServerConnection;
import org.apache.http.nio.NHttpConnectionFactory;
import org.apache.http.nio.NHttpServiceHandler;
import org.apache.http.nio.protocol.BufferingHttpServiceHandler;
import org.apache.http.nio.protocol.EventListener;
import org.apache.http.nio.reactor.ListenerEndpoint;
import org.apache.http.nio.testserver.HttpCoreNIOTestBase;
import org.apache.http.nio.testserver.HttpServerNio;
import org.apache.http.nio.testserver.LoggingSSLClientConnectionFactory;
import org.apache.http.nio.testserver.LoggingSSLServerConnectionFactory;
import org.apache.http.nio.testserver.SSLTestContexts;
import org.apache.http.nio.testserver.SimpleHttpRequestHandlerResolver;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpExpectationVerifier;
import org.apache.http.protocol.HttpRequestHandler;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

@Deprecated
public class TestBaseIOReactorSSL extends HttpCoreNIOTestBase {

    @Before
    public void setUp() throws Exception {
        initServer();
    }

    @After
    public void tearDown() throws Exception {
        shutDownServer();
    }

    @Override
    protected NHttpConnectionFactory<DefaultNHttpServerConnection> createServerConnectionFactory() throws Exception {
        return new LoggingSSLServerConnectionFactory(SSLTestContexts.createServerSSLContext());
    }

    @Override
    protected NHttpConnectionFactory<DefaultNHttpClientConnection> createClientConnectionFactory() throws Exception {
        return new LoggingSSLClientConnectionFactory(SSLTestContexts.createClientSSLContext());
    }

    private NHttpServiceHandler createHttpServiceHandler(
            final HttpRequestHandler requestHandler,
            final HttpExpectationVerifier expectationVerifier,
            final EventListener eventListener) {
        final BufferingHttpServiceHandler serviceHandler = new BufferingHttpServiceHandler(
                HttpServerNio.DEFAULT_HTTP_PROC,
                DefaultHttpResponseFactory.INSTANCE,
                DefaultConnectionReuseStrategy.INSTANCE,
                new BasicHttpParams());

        serviceHandler.setHandlerResolver(
                new SimpleHttpRequestHandlerResolver(requestHandler));
        serviceHandler.setExpectationVerifier(expectationVerifier);
        serviceHandler.setEventListener(eventListener);

        return serviceHandler;
    }

    private TrustManagerFactory createTrustManagerFactory() throws NoSuchAlgorithmException {
        final String algo = TrustManagerFactory.getDefaultAlgorithm();
        try {
            return TrustManagerFactory.getInstance(algo);
        } catch (final NoSuchAlgorithmException ex) {
            return TrustManagerFactory.getInstance("SunX509");
        }
    }

    @Test
    public void testBufferedInput() throws Exception {
        final int[] result = new int[1];
        final HttpRequestHandler requestHandler = new HttpRequestHandler() {
            public void handle(final HttpRequest request, final HttpResponse response,
                    final HttpContext context) throws HttpException, IOException {
                result[0]++;
                synchronized (result) {
                    result.notify();
                }
            }
        };

        final NHttpServiceHandler serviceHandler = createHttpServiceHandler(
                requestHandler,
                null,
                null);

        this.server.start(serviceHandler);

        final ClassLoader cl = getClass().getClassLoader();
        final URL url = cl.getResource("test.keystore");
        final KeyStore keystore  = KeyStore.getInstance("jks");
        keystore.load(url.openStream(), "nopassword".toCharArray());
        final TrustManagerFactory tmfactory = createTrustManagerFactory();
        tmfactory.init(keystore);
        final TrustManager[] trustmanagers = tmfactory.getTrustManagers();
        final SSLContext sslcontext = SSLContext.getInstance("TLS");
        sslcontext.init(null, trustmanagers, null);

        final ListenerEndpoint endpoint = this.server.getListenerEndpoint();
        endpoint.waitFor();
        final InetSocketAddress serverAddress = (InetSocketAddress) endpoint.getAddress();

        final Socket socket = sslcontext.getSocketFactory().createSocket("localhost", serverAddress.getPort());
        final BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        //            123456789012345678901234567890
        writer.write("GET / HTTP/1.1\r\n");
        writer.write("Header:                   \r\n");
        writer.write("Header:                   \r\n");
        writer.write("Header:                   \r\n");
        writer.write("\r\n");
        writer.flush();

        synchronized (result) {
            result.wait(5000);
        }
        Assert.assertEquals(1, result[0]);
    }

}
