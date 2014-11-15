/**
 * Copyright (C) 2014 Open WhisperSystems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.whispersystems.websocket;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.SettableFuture;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.websocket.auth.AuthenticationException;
import org.whispersystems.websocket.auth.WebSocketAuthenticator;
import org.whispersystems.websocket.messages.InvalidMessageException;
import org.whispersystems.websocket.messages.WebSocketMessage;
import org.whispersystems.websocket.messages.WebSocketMessageFactory;
import org.whispersystems.websocket.messages.WebSocketRequestMessage;
import org.whispersystems.websocket.messages.WebSocketResponseMessage;
import org.whispersystems.websocket.servlet.NullServletResponse;
import org.whispersystems.websocket.servlet.WebSocketServletRequest;
import org.whispersystems.websocket.servlet.WebSocketServletResponse;
import org.whispersystems.websocket.session.WebSocketSessionContext;
import org.whispersystems.websocket.setup.WebSocketConnectListener;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class WebSocketResourceProvider implements WebSocketListener {

  private static final Logger logger = LoggerFactory.getLogger(WebSocketResourceProvider.class);

  private final Map<Long, SettableFuture<WebSocketResponseMessage>> requestMap = new ConcurrentHashMap<>();

  private final Optional<WebSocketAuthenticator>   authenticator;
  private final WebSocketMessageFactory            messageFactory;
  private final Optional<WebSocketConnectListener> connectListener;
  private final HttpServlet                        servlet;

  private Session                 session;
  private WebSocketSessionContext context;

  public WebSocketResourceProvider(HttpServlet                        servlet,
                                   Optional<WebSocketAuthenticator>   authenticator,
                                   WebSocketMessageFactory            messageFactory,
                                   Optional<WebSocketConnectListener> connectListener)
  {
    this.servlet         = servlet;
    this.authenticator   = authenticator;
    this.messageFactory  = messageFactory;
    this.connectListener = connectListener;
  }

  @Override
  public void onWebSocketConnect(Session session) {
    try {
      this.session = session;
      this.context = new WebSocketSessionContext(new WebSocketClient(session, messageFactory, requestMap));

      this.session.setIdleTimeout(30000);

      logger.debug("onWebSocketConnect(), authenticating: " + (authenticator.isPresent()));

      if (authenticator.isPresent()) {
        Optional authenticated = authenticator.get().authenticate(session.getUpgradeRequest());

        if (authenticated.isPresent()) {
          this.context.setAuthenticated(authenticated.get());
        }
      }

      if (connectListener.isPresent()) {
        connectListener.get().onWebSocketConnect(this.context);
      }

    } catch (AuthenticationException e) {
      logger.warn("Authentication", e);
      close(session, 1011, "Server error");
    }
  }

  @Override
  public void onWebSocketError(Throwable cause) {
    logger.debug("onWebSocketError", cause);
    close(session, 1011, "Server error");
  }

  @Override
  public void onWebSocketBinary(byte[] payload, int offset, int length) {
    try {
      WebSocketMessage webSocketMessage = messageFactory.parseMessage(payload, offset, length);

      switch (webSocketMessage.getType()) {
        case REQUEST_MESSAGE:
          handleRequest(webSocketMessage.getRequestMessage());
          break;
        case RESPONSE_MESSAGE:
          handleResponse(webSocketMessage.getResponseMessage());
          break;
        default:
          close(session, 1018, "Badly formatted");
          break;
      }
    } catch (InvalidMessageException e) {
      logger.debug("Parsing", e);
      close(session, 1018, "Badly formatted");
    }
  }

  @Override
  public void onWebSocketClose(int statusCode, String reason) {
    if (context != null) {
      context.notifyClosed(statusCode, reason);

      for (long requestId : requestMap.keySet()) {
        SettableFuture outstandingRequest = requestMap.remove(requestId);

        if (outstandingRequest != null) {
          outstandingRequest.setException(new IOException("Connection closed!"));
        }
      }
    }
  }

  @Override
  public void onWebSocketText(String message) {
    logger.debug("onWebSocketText!");
  }

  private void handleRequest(WebSocketRequestMessage requestMessage) {
    try {
      HttpServletRequest  servletRequest  = createRequest(requestMessage, context);
      HttpServletResponse servletResponse = createResponse(requestMessage);

      servlet.service(servletRequest, servletResponse);
      servletResponse.flushBuffer();
    } catch (IOException | ServletException e) {
      logger.warn("Servlet Error", e);
      sendErrorResponse(requestMessage, Response.status(500).build());
    }
  }

  private void handleResponse(WebSocketResponseMessage responseMessage) {
    SettableFuture<WebSocketResponseMessage> future = requestMap.remove(responseMessage.getRequestId());

    if (future != null) {
      future.set(responseMessage);
    }
  }

  private void close(Session session, int status, String message) {
    try {
      session.close(status, message);
    } catch (IOException e) {
      logger.debug("Close", e);
    }
  }

  private HttpServletRequest createRequest(WebSocketRequestMessage message,
                                           WebSocketSessionContext context)
  {
    return new WebSocketServletRequest(context, message, servlet.getServletContext());
  }

  private HttpServletResponse createResponse(WebSocketRequestMessage message) {
    if (message.hasRequestId()) {
      return new WebSocketServletResponse(session.getRemote(), message.getRequestId(), messageFactory);
    } else {
      return new NullServletResponse();
    }
  }

  private void sendErrorResponse(WebSocketRequestMessage requestMessage, Response error) {
    if (requestMessage.hasRequestId()) {
      WebSocketMessage response = messageFactory.createResponse(requestMessage.getRequestId(),
                                                                error.getStatus(),
                                                                "Error response",
                                                                Optional.<byte[]>absent());

      session.getRemote().sendBytesByFuture(ByteBuffer.wrap(response.toByteArray()));
    }
  }
}
