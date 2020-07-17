/*
 * Copyright (c) 2011-2019 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.core.http.impl;

import io.netty.handler.codec.http2.Http2Error;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.StreamResetException;
import io.vertx.core.http.impl.headers.Http2HeadersAdaptor;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.impl.PromiseInternal;
import io.vertx.core.net.SocketAddress;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public abstract class HttpClientRequestBase implements HttpClientRequest {

  protected final HttpClientImpl client;
  protected final ContextInternal context;
  protected final HttpClientStream stream;
  protected final io.vertx.core.http.HttpMethod method;
  protected final String uri;
  protected final String host;
  protected final int port;
  protected final SocketAddress server;
  protected final boolean ssl;
  private String path;
  private String query;
  private final PromiseInternal<HttpClientResponse> responsePromise;
  private Handler<HttpClientRequest> pushHandler;
  long currentTimeoutTimerId = -1;
  long currentTimeoutMs;
  private long lastDataReceived;

  HttpClientRequestBase(HttpClientImpl client, HttpClientStream stream, PromiseInternal<HttpClientResponse> responsePromise, boolean ssl, HttpMethod method, SocketAddress server, String host, int port, String uri) {
    this.client = client;
    this.stream = stream;
    this.responsePromise = responsePromise;
    this.context = (ContextInternal) responsePromise.future().context();
    this.uri = uri;
    this.method = method;
    this.server = server;
    this.host = host;
    this.port = port;
    this.ssl = ssl;

    //
    stream.pushHandler(this::handlePush);
    stream.headHandler(resp -> {
      HttpClientResponseImpl response = new HttpClientResponseImpl(this, stream.version(), stream, resp.statusCode, resp.statusMessage, resp.headers);
      stream.chunkHandler(response::handleChunk);
      stream.endHandler(response::handleEnd);
      stream.priorityHandler(response::handlePriorityChange);
      stream.unknownFrameHandler(response::handleUnknownFrame);
      handleResponse(response);
    });
  }

  protected String authority() {
    if ((port == 80 && !ssl) || (port == 443 && ssl)) {
      return host;
    } else {
      return host + ':' + port;
    }
  }

  @Override
  public int streamId() {
    return stream.id();
  }

  @Override
  public String absoluteURI() {
    return (ssl ? "https://" : "http://") + authority() + uri;
  }

  public String query() {
    if (query == null) {
      query = HttpUtils.parseQuery(uri);

    }
    return query;
  }

  public String path() {
    if (path == null) {
      path = uri.length() > 0 ? HttpUtils.parsePath(uri) : "";
    }
    return path;
  }

  public String uri() {
    return uri;
  }

  public String host() {
    return server.host();
  }

  @Override
  public HttpMethod method() {
    return method;
  }

  @Override
  public synchronized HttpClientRequest setTimeout(long timeoutMs) {
    cancelTimeout();
    currentTimeoutMs = timeoutMs;
    currentTimeoutTimerId = client.getVertx().setTimer(timeoutMs, id -> handleTimeout(timeoutMs));
    return this;
  }

  void handleException(Throwable t) {
    fail(t);
  }

  void fail(Throwable t) {
    cancelTimeout();
    responsePromise.tryFail(t);
    HttpClientResponseImpl response = (HttpClientResponseImpl) responsePromise.future().result();
    if (response != null) {
      response.handleException(t);
    }
  }

  void handlePush(HttpClientPush push) {
    String rawMethod = push.headers.method().toString();
    HttpMethod method = HttpMethod.valueOf(rawMethod);
    String uri = push.headers.path().toString();
    String authority = push.headers.authority() != null ? push.headers.authority().toString() : null;
    MultiMap headersMap = new Http2HeadersAdaptor(push.headers);
    int pos = authority.indexOf(':');
    int port;
    String host;
    if (pos == -1) {
      host = authority;
      port = 80;
    } else {
      host = authority.substring(0, pos);
      port = Integer.parseInt(authority.substring(pos + 1));
    }
    HttpClientRequestPushPromise pushReq = new HttpClientRequestPushPromise(push.stream, client, ssl, method, uri, host, port, headersMap);
    if (pushHandler != null) {
      pushHandler.handle(pushReq);
    } else {
      pushReq.reset(Http2Error.CANCEL.code());
    }
  }

  void handleResponse(HttpClientResponse resp) {
    handleResponse(responsePromise, resp, cancelTimeout());
  }

  abstract void handleResponse(Promise<HttpClientResponse> promise, HttpClientResponse resp, long timeoutMs);

  private synchronized long cancelTimeout() {
    long ret;
    if ((ret = currentTimeoutTimerId) != -1) {
      client.getVertx().cancelTimer(currentTimeoutTimerId);
      currentTimeoutTimerId = -1;
      ret = currentTimeoutMs;
      currentTimeoutMs = 0;
    }
    return ret;
  }

  private void handleTimeout(long timeoutMs) {
    synchronized (this) {
      if (lastDataReceived > 0) {
        long now = System.currentTimeMillis();
        long timeSinceLastData = now - lastDataReceived;
        if (timeSinceLastData < timeoutMs) {
          // reschedule
          lastDataReceived = 0;
          setTimeout(timeoutMs - timeSinceLastData);
          return;
        }
      }
    }
    reset(timeoutEx(timeoutMs, method, server, uri));
  }

  static NoStackTraceTimeoutException timeoutEx(long timeoutMs, HttpMethod method, SocketAddress server, String uri) {
    return new NoStackTraceTimeoutException("The timeout period of " + timeoutMs + "ms has been exceeded while executing " + method + " " + uri + " for server " + server);
  }

  synchronized void dataReceived() {
    if (currentTimeoutTimerId != -1) {
      lastDataReceived = System.currentTimeMillis();
    }
  }

  @Override
  public boolean reset(long code) {
    return reset(new StreamResetException(code));
  }

  @Override
  public boolean reset(long code, Throwable cause) {
    return reset(new StreamResetException(code, cause));
  }

  abstract boolean reset(Throwable cause);

  @Override
  public HttpClientRequest onComplete(Handler<AsyncResult<HttpClientResponse>> handler) {
    responsePromise.future().onComplete(handler);
    return this;
  }

  @Override
  public boolean isComplete() {
    return responsePromise.future().isComplete();
  }

  @Override
  public HttpClientResponse result() {
    return responsePromise.future().result();
  }

  @Override
  public Throwable cause() {
    return responsePromise.future().cause();
  }

  @Override
  public boolean succeeded() {
    return responsePromise.future().succeeded();
  }

  @Override
  public boolean failed() {
    return responsePromise.future().failed();
  }

  synchronized Handler<HttpClientRequest> pushHandler() {
    return pushHandler;
  }

  @Override
  public synchronized HttpClientRequest pushHandler(Handler<HttpClientRequest> handler) {
    pushHandler = handler;
    return this;
  }
}
