/**
 * The MIT License
 * Copyright (c) 2010 Tad Glines
 *
 * Contributors: Ovea.com, Mycila.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.codeminders.socketio.server.transport.jetty;

import com.codeminders.socketio.server.*;
import com.codeminders.socketio.common.ConnectionState;
import com.codeminders.socketio.common.DisconnectReason;
import com.codeminders.socketio.common.SocketIOException;
import com.codeminders.socketio.server.transport.AbstractHttpTransport;
import com.codeminders.socketio.util.IO;
import com.codeminders.socketio.util.JSON;
import com.codeminders.socketio.util.URI;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.server.HttpConnection;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Mathieu Carbou
 */

//TODO: do we even need continuation for polling transports?
public final class JettyContinuationTransportConnection
        extends AbstractTransportConnection
        implements ContinuationListener
{
    /**
     * For non persistent connection transports, this is the amount of time to wait
     * for messages before returning empty results.
     */
    private static final long DEFAULT_TIMEOUT = 5 * 1000;

    /**
     * For non persistent connection transports, this is the amount of time to wait
     * for messages before returning empty results.
     */
    private static final long DEFAULT_CONTINUATION_TIMEOUT = 20 * 1000;

    private static final String CONTINUATION_KEY = JettyContinuationTransportConnection.class.getName() + ".Continuation";
    private static final Logger LOGGER = Logger.getLogger(JettyContinuationTransportConnection.class.getName());

    private volatile boolean is_open;
    private volatile Continuation continuation;
    private volatile boolean disconnectWhenEmpty;

    private TransportBuffer buffer;
    private int bufferSize;
    private int maxIdleTime;
    private long continuationTimeout;

    private AbstractHttpTransport transport;

    public JettyContinuationTransportConnection(AbstractHttpTransport transport)
    {
        this.transport = transport;
    }

    @Override
    public Transport getTransport()
    {
        return transport;
    }

    @Override
    protected final void init()
    {
        this.bufferSize = getConfig().getBufferSize();
        this.maxIdleTime = getConfig().getMaxIdle();
        this.buffer = new TransportBuffer(bufferSize);
        getSession().setTimeout(getConfig().getTimeout(DEFAULT_TIMEOUT));
        this.continuationTimeout = getConfig().getLong("continuationTimeout", DEFAULT_CONTINUATION_TIMEOUT);
        if (LOGGER.isLoggable(Level.FINE))
            LOGGER.fine(getConfig().getNamespace() + " transport handler configuration:\n" +
                    " - timeout=" + getSession().getTimeout() + "\n" +
                    " - continuationTimeout=" + this.continuationTimeout);
    }

    @Override
    public void disconnect() {
        getSession().onDisconnect(DisconnectReason.DISCONNECT);
        abort();
    }

    @Override
    public void close() {
        getSession().startClose();
    }

    @Override
    public ConnectionState getConnectionState() {
        return getSession().getConnectionState();
    }

    @Override
    public void sendMessage(SocketIOFrame frame) throws SocketIOException {
        if (LOGGER.isLoggable(Level.FINE))
            LOGGER.log(Level.FINE, "Session[" + getSession().getSessionId() + "]: " + "sendMessage(frame): [" + frame.getFrameType() + "]: " + frame.getData());
        if (is_open) {
            if (continuation != null) {
                List<String> messages = buffer.drainMessages();
                messages.add(frame.encode());
                StringBuilder data = new StringBuilder();
                for (String msg : messages) {
                    data.append(msg);
                }
                try {
                    transport.writeData(getSession(), continuation.getServletResponse(), data.toString());
                } catch (IOException e) {
                    throw new SocketIOException(e);
                }
                if (!continuation.isInitial()) {
                    Continuation cont = continuation;
                    continuation = null;
                    cont.complete();
                } else {
                    getSession().startHeartbeatTimer();
                }
            } else {
                String data = frame.encode();
                if (!buffer.putMessage(data, maxIdleTime)) {
                    getSession().onDisconnect(DisconnectReason.TIMEOUT);
                    abort();
                    throw new SocketIOException();
                }
            }
        } else {
            throw new SocketIOClosedException();
        }
    }

    @Override
    public void sendMessage(String message) throws SocketIOException {
        if (LOGGER.isLoggable(Level.FINE))
            LOGGER.log(Level.FINE, "Session[" + getSession().getSessionId() + "]: " + "sendMessage(String): " + message);
        sendMessage(SocketIOFrame.TEXT_MESSAGE_TYPE, message);
    }

    @Override
    public void sendMessage(int messageType, String message) throws SocketIOException {
        if (LOGGER.isLoggable(Level.FINE))
            LOGGER.log(Level.FINE, "Session[" + getSession().getSessionId() + "]: " + "sendMessage(int, String): [" + messageType + "]: " + message);
        if (is_open && getSession().getConnectionState() == ConnectionState.CONNECTED) {
            sendMessage(new SocketIOFrame(messageType == SocketIOFrame.TEXT_MESSAGE_TYPE ?
                              SocketIOFrame.FrameType.MESSAGE :
                              SocketIOFrame.FrameType.JSON_MESSAGE,
                        messageType, message));
        } else {
            throw new SocketIOClosedException();
        }
    }

    @Override
    public void emitEvent(String name, String args) throws SocketIOException {
        //TODO: code duplication
        if (is_open && getSession().getConnectionState() == ConnectionState.CONNECTED) {
            HashMap<String, Object> map = new HashMap<String, Object>();
            map.put("name", name);
            map.put("args", new Object[] { args });
            sendMessage(new SocketIOFrame(SocketIOFrame.FrameType.EVENT,
                    SocketIOFrame.JSON_MESSAGE_TYPE,
                    JSON.toString(map)));
        } else {
            throw new SocketIOClosedException();
        }

    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, SocketIOSession session) throws IOException {
        if ("GET".equals(request.getMethod()))
        {
            if (!is_open && buffer.isEmpty())
            {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
            }
            else
            {

                if (!buffer.isEmpty())
                {
                    List<String> messages = buffer.drainMessages();
                    if (messages.size() > 0)
                    {
                        StringBuilder data = new StringBuilder();
                        for (String msg : messages)
                            data.append(msg);

                        transport.startSend(getSession(), response);
                        transport.writeData(getSession(), response, data.toString());
                        transport.finishSend(getSession(), response);
                        if (!disconnectWhenEmpty) {
                            getSession().startTimeoutTimer();
                        } else {
                            abort();
                        }
                    }
                } else {
                    getSession().clearTimeoutTimer();
                    if (response.getBufferSize() == 0) {
                        response.setBufferSize(bufferSize);
                    }
                    continuation = ContinuationSupport.getContinuation(request);
                    continuation.addContinuationListener(this);
                    continuation.setTimeout(continuationTimeout);
                    continuation.suspend(response);
                    transport.startSend(getSession(), response);
                }
              }
        } else if ("POST".equals(request.getMethod())) {
            if (is_open) {
                int size = request.getContentLength();
                BufferedReader reader = request.getReader();
                if (size == 0) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST);
                } else {
                    String data = decodePostData(request.getContentType(), IO.toString(reader));
                    if (data != null && data.length() > 0) {
                        List<SocketIOFrame> list = SocketIOFrame.parse(data);
                        for (SocketIOFrame msg : list) {
                            getSession().onMessage(msg);
                        }
                    }

                    // Ensure that the disconnectWhenEmpty flag is obeyed in the case where
                    // it is set during a POST.
                    if (disconnectWhenEmpty && buffer.isEmpty()) {
                        if (getSession().getConnectionState() == ConnectionState.CLOSING) {
                            getSession().onDisconnect(DisconnectReason.CLOSED);
                        }
                        abort();
                    }
                }
            }
        } else {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
        }

    }

    protected String decodePostData(String contentType, String data) {
        if (contentType.startsWith("application/x-www-form-urlencoded")) {
            if (data.startsWith("d=")) {
                data = URI.decodePath(data.substring(2).replace("+", " "));
                // replace("\u005c", "") is'not compiled by maven compiler (92)
                data = data.substring(1, data.length()-1)
                           .replace(Character.toString((char) 92), "");
                return data;
            } else {
                return "";
            }
        } else if (contentType.startsWith("text/plain")) {
            return data;
        } else {
            // TODO: Treat as text for now, maybe error in the future.
            return data;
        }
    }

    @Override
    public void onComplete(Continuation cont)
    {
        if (continuation != null && cont == continuation)
        {
            continuation = null;
            if (!is_open && buffer.isEmpty() && !disconnectWhenEmpty)
            {
                getSession().onDisconnect(DisconnectReason.DISCONNECT);
                abort();
            }
            else
            {
                if (disconnectWhenEmpty)
                    abort();
                else
                    getSession().startTimeoutTimer();
            }
        }
    }

    @Override
    public void onTimeout(Continuation cont)
    {
        if (continuation != null && cont == continuation)
        {
            continuation = null;
            if (!is_open && buffer.isEmpty())
            {
                getSession().onDisconnect(DisconnectReason.DISCONNECT);
                abort();
            }
            else
            {
                try
                {
                    transport.finishSend(getSession(), cont.getServletResponse());
                } catch (IOException e)
                {
                    getSession().onDisconnect(DisconnectReason.DISCONNECT);
                    abort();
                }
            }
            getSession().startTimeoutTimer();
        }
    }

    @Override
    public void connect(HttpServletRequest request, HttpServletResponse response)
            throws IOException, SocketIOProtocolException
    {
        response.setBufferSize(bufferSize);
        continuation = ContinuationSupport.getContinuation(request);
        continuation.addContinuationListener(this);

        // do transport-specific actions on onConnect
        transport.onConnect(getSession(), request, response);

        is_open = true;
        getSession().onConnect(this);

        transport.finishSend(getSession(), response);
        if (continuation != null)
            continuation = null;
    }

    @Override
    public void disconnectWhenEmpty() {
        disconnectWhenEmpty = true;
    }

    @Override
    public void abort() {
        getSession().clearHeartbeatTimer();
        getSession().clearTimeoutTimer();
        is_open = false;
        if (continuation != null) {
            Continuation cont = continuation;
            continuation = null;
            if (cont.isSuspended()) {
                cont.complete();
            }
        }
        buffer.setListener(new TransportBuffer.BufferListener() {
            @Override
            public boolean onMessages(List<String> messages) {
                return false;
            }

            @Override
            public boolean onMessage(String message) {
                return false;
            }
        });
        buffer.clear();
        getSession().onShutdown();
    }

    @Override
    public void send(EngineIOPacket packet) throws SocketIOException
    {
        //TODO: implement
    }

    /**
     * This must be called within the context of an active HTTP request.
     */
    private static ConnectionTimeoutPreventer newTimeoutPreventor() {
        HttpConnection httpConnection = HttpConnection.getCurrentConnection();
        if (httpConnection == null) {
            LOGGER.log(Level.FINE, "No HttpConnection boundto local thread: " + Thread.currentThread().getName());
            return new ConnectionTimeoutPreventer() {
                @Override
                public void connectionActive() {
                }
            };
        } else {
            //call code reflectively because by default we have no access to jetty internal classes from a webapp
            // thus by only using HttpConnection we only need to add "-org.eclipse.jetty.server.HttpConnection" to server classes
            // to allow access to this class from a webapp
            final Object endpoint = httpConnection.getEndPoint();
            // try to cancel IDLE time
            try {
                LOGGER.fine("TimeoutPreventor - Invoking cancelIdle() method on endpoint class " + endpoint.getClass().getName());
                Method cancelIdle = endpoint.getClass().getMethod("cancelIdle");
                cancelIdle.invoke(endpoint);
            } catch (NoSuchMethodException e) {
                LOGGER.fine("TimeoutPreventor - No cancelIdle() method on endpoint class " + endpoint.getClass().getName());
            } catch (IllegalAccessException e) {
                LOGGER.warning("TimeoutPreventor - Cannot access cancelIdle() method on endpoint class " + endpoint.getClass().getName());
            } catch (InvocationTargetException e) {
                LOGGER.log(Level.WARNING, "TimeoutPreventor - Error calling cancelIdle() method on endpoint class " + endpoint.getClass().getName() + ": " + e.getMessage(), e);
            }
            // try to find scheduleIdle() method
            try {
                final Method scheduleIdle = endpoint.getClass().getMethod("scheduleIdle");
                return new ConnectionTimeoutPreventer() {
                    @Override
                    public void connectionActive() {
                        try {
                            LOGGER.fine("TimeoutPreventor - Invoking scheduleIdle() method on endpoint class " + endpoint.getClass().getName());
                            scheduleIdle.invoke(endpoint);
                        } catch (IllegalAccessException e) {
                            LOGGER.warning("TimeoutPreventor - Cannot access scheduleIdle() method on endpoint class " + endpoint.getClass().getName());
                        } catch (InvocationTargetException e) {
                            LOGGER.log(Level.WARNING, "TimeoutPreventor - Error calling scheduleIdle() method on endpoint class " + endpoint.getClass().getName() + ": " + e.getMessage(), e);
                        }
                    }
                };
            } catch (NoSuchMethodException e) {
                // if the method does not exit, do nothing
                return new ConnectionTimeoutPreventer() {
                    @Override
                    public void connectionActive() {
                    }
                };
            }
        }
    }

    @Override
    public void send(SocketIOPacket packet) throws SocketIOException
    {
        //TODO: implement
    }
}