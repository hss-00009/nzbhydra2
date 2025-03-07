/*
 *  (C) Copyright 2017 TheOtherP (theotherp@posteo.net)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.nzbhydra.auth;

import jakarta.servlet.ServletException;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.logging.log4j.util.Strings;
import org.apache.tomcat.util.buf.MessageBytes;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Sets the correct scheme when behind a reverse proxy. According to https://docs.spring.io/spring-boot/docs/current-SNAPSHOT/reference/htmlsingle/#howto-use-tomcat-behind-a-proxy-server this
 * should aready work when server.use-forward-headers is true but it doesn't
 */
@Component
public class HydraEmbeddedServletContainer implements WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> {

    private static final Logger logger = LoggerFactory.getLogger(HydraEmbeddedServletContainer.class);

    @Override
    public void customize(ConfigurableServletWebServerFactory factory) {
        if (!(factory instanceof TomcatServletWebServerFactory containerFactory)) {
            return; //Is the case in tests
        }
        containerFactory.addContextValves(new ValveBase() {
            @Override
            public void invoke(Request request, Response response) throws IOException, ServletException {
                Valve nextValve = getNext();
                Result result = parseRequest(request);
                try {
                    nextValve.invoke(request, response);
                } finally {
                    if (result.originallySecure() != null) {
                        request.setSecure(result.originallySecure());
                    }
                    if (result.forwardedHost() != null) {
                        result.serverNameMB().setString(result.originalServerName());
                    }
                    if (result.forwardedPort() != null) {
                        request.setServerPort(result.originalPort());
                    }

                }
            }


        });
        containerFactory.addContextCustomizers(context -> context.setMapperContextRootRedirectEnabled(true));
    }

    @NotNull
    static Result parseRequest(Request request) {
        int originalPort = -1;
        final String forwardedPort = request.getHeader("X-Forwarded-Port");
        if (forwardedPort != null) {
            try {
                originalPort = request.getServerPort();
                request.setServerPort(Integer.parseInt(forwardedPort));
            } catch (final NumberFormatException e) {
                logger.debug("ignoring forwarded port {}", forwardedPort);
            }
        }

        final MessageBytes serverNameMB = request.getCoyoteRequest().serverName();
        String originalServerName = null;
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        if (forwardedHost == null) {
            forwardedHost = request.getHeader("host");
        }
        if (Strings.isNotBlank(forwardedHost)) {
            String[] split = forwardedHost.split("[ ,]");
            forwardedHost = split[0];
            int colonIndex = forwardedHost.endsWith("]") ? -1 : forwardedHost.lastIndexOf(":");
            if (colonIndex > -1) {
                if (originalPort == -1) {
                    originalPort = request.getServerPort();
                }
                request.setServerPort(Integer.parseInt(forwardedHost.substring(colonIndex + 1)));
                forwardedHost = forwardedHost.substring(0, colonIndex);
            }
            originalServerName = serverNameMB.getString();
            serverNameMB.setString(forwardedHost);
        }

        Boolean originallySecure = null;
        final String forwardedProto = request.getHeader("X-Forwarded-Proto");
        if (forwardedProto != null) {
            originallySecure = request.isSecure();
            request.setSecure(forwardedProto.equalsIgnoreCase("https"));
        }
        Result result = new Result(originalPort, forwardedPort, serverNameMB, originalServerName, forwardedHost, originallySecure);
        return result;
    }

    record Result(int originalPort, String forwardedPort, MessageBytes serverNameMB, String originalServerName, String forwardedHost, Boolean originallySecure) {
    }


}
