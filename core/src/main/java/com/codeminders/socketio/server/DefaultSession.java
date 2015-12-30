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
package com.codeminders.socketio.server;

import com.codeminders.socketio.common.SocketIOException;
import com.codeminders.socketio.util.JSON;
import com.codeminders.socketio.common.ConnectionState;
import com.codeminders.socketio.common.DisconnectReason;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Mathieu Carbou (mathieu.carbou@gmail.com)
 */
class DefaultSession implements SocketIOSession {

    private static final int SESSION_ID_LENGTH = 20;
    private static final Logger LOGGER = Logger.getLogger(DefaultSession.class.getName());

    private final SocketIOSessionManager socketIOSessionManager;
    private final String sessionId;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    //TODO: rename to something more telling
    //This is callback/listener interface set by library user
    private SocketIOInbound     inbound;

    private TransportConnection connection;
    private ConnectionState state = ConnectionState.CONNECTING;
    private long hbDelay;
    private SessionTask hbDelayTask;
    private long timeout;
    private SessionTask timeoutTask;
    private boolean timedout;
    private String closeId;

    DefaultSession(SocketIOSessionManager socketIOSessionManager, SocketIOInbound inbound, String sessionId)
    {
        this.socketIOSessionManager = socketIOSessionManager;
        this.inbound = inbound;
        this.sessionId = sessionId;
    }

    @Override
    public void setAttribute(String key, Object val) {
        attributes.put(key, val);
    }

    @Override
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public ConnectionState getConnectionState() {
        return state;
    }

    @Override
    public SocketIOInbound getInbound() {
        return inbound;
    }

    @Override
    public TransportConnection getConnection() {
        return connection;
    }

    private void onTimeout() {
        if (LOGGER.isLoggable(Level.FINE))
            LOGGER.log(Level.FINE, "Session[" + sessionId + "]: onTimeout");
        if (!timedout) {
            timedout = true;
            state = ConnectionState.CLOSED;
            onDisconnect(DisconnectReason.TIMEOUT);
            connection.abort();
        }
    }

    @Override
    public void startTimeoutTimer() {
        clearTimeoutTimer();
        if (!timedout && timeout > 0) {
            timeoutTask = scheduleTask(new Runnable() {
                @Override
                public void run() {
                    DefaultSession.this.onTimeout();
                }
            }, timeout);
        }
    }

    @Override
    public void clearTimeoutTimer() {
        if (timeoutTask != null) {
            timeoutTask.cancel();
            timeoutTask = null;
        }
    }

    //TODO: remove heartbeat from server. in SocketI.IO client is sending periodic pings
    private void sendHeartBeat() {
        if (LOGGER.isLoggable(Level.FINE))
            LOGGER.log(Level.FINE, "Session[" + sessionId + "]: send heartbeat ");
        try {
            connection.sendMessage(new SocketIOFrame(SocketIOFrame.FrameType.HEARTBEAT, 0, ""));
        } catch (SocketIOException e) {
            if (LOGGER.isLoggable(Level.FINE))
                LOGGER.log(Level.FINE, "connection.sendMessage failed: ", e);
            connection.abort();
        }
        startTimeoutTimer();
    }

    @Override
    public void startHeartbeatTimer() {
        clearHeartbeatTimer();
        if (!timedout && hbDelay > 0) {
            hbDelayTask = scheduleTask(new Runnable() {
                @Override
                public void run() {
                    sendHeartBeat();
                }
            }, hbDelay);
        }
    }

    @Override
    public void clearHeartbeatTimer() {
        if (hbDelayTask != null) {
            hbDelayTask.cancel();
            hbDelayTask = null;
        }
    }

    @Override
    public void setHeartbeat(long delay) {
        hbDelay = delay;
    }

    @Override
    public long getHeartbeat() {
        return hbDelay;
    }

    @Override
    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    @Override
    public long getTimeout() {
        return timeout;
    }

    @Override
    public void startClose() {
        state = ConnectionState.CLOSING;
        closeId = "server";
        try {
            connection.sendMessage(new SocketIOFrame(SocketIOFrame.FrameType.CLOSE, 0, closeId));
        } catch (SocketIOException e) {
            if (LOGGER.isLoggable(Level.FINE))
                LOGGER.log(Level.FINE, "connection.sendMessage failed: ", e);
            connection.abort();
        }
    }

    @Override
    public void onPacket(EngineIOPacket packet)
    {
        switch (packet.getType())
        {
            case OPEN:
            case PONG:
                // ignore. OPEN and PONG are server -> client
                return;
            case MESSAGE:
                startTimeoutTimer();
                try
                {
                    onPacket(SocketIOProtocol.decode(packet.getData()));
                }
                catch (SocketIOProtocolException e)
                {
                    if(LOGGER.isLoggable(Level.WARNING))
                        LOGGER.log(Level.WARNING, "Invalid SIO packet: " + packet.getData(), e);
                }
                return;

            case PING:
                startTimeoutTimer();
                onPing(packet.getData());
                return;

            case CLOSE:
                startClose();
                return;

            default:
                throw new UnsupportedOperationException("EIO Packet " + packet + " is not implemented yet");

        }
    }

    @Override
    public void onPacket(SocketIOPacket packet)
    {
        switch (packet.getType())
        {
            case CONNECT:
                // ignore. server -> client
                return;
            case EVENT:
                Object json = JSON.parse(packet.getData());
                if(!(json instanceof Object[]) || ((Object[])json).length == 0)
                {
                    if (LOGGER.isLoggable(Level.WARNING))
                        LOGGER.log(Level.WARNING, "Invalid JSON in EVENT message packet: " + packet.getData());
                    return;
                }

                Object[] args = (Object[])json;
                if(!(args[0] instanceof String))
                {
                    if (LOGGER.isLoggable(Level.WARNING))
                        LOGGER.log(Level.WARNING, "Invalid JSON in EVENT message packet. First argument must be string: " + packet.getData());
                    return;
                }
                onEvent(args[0].toString(), Arrays.copyOfRange(args, 1, args.length-1));

                return;

            default:
                throw new UnsupportedOperationException("SocketIO packet " + packet.getType() + " is not implemented yet");

        }
    }

    //TODO: remove. old method
    @Override
    public void onMessage(SocketIOFrame message) {
        switch (message.getFrameType()) {
            case CONNECT:
                onPing(message.getData());
            case HEARTBEAT:
                // Ignore this message type as they are only intended to be from server to client.
                //TODO: socket.io client echoes heartbeat message back
                startHeartbeatTimer();
                break;
            case CLOSE:
                if (LOGGER.isLoggable(Level.FINE))
                    LOGGER.log(Level.FINE, "Session[" + sessionId + "]: onClose: " + message.getData());
                onClose(message.getData());
                break;
            case MESSAGE:
            case JSON_MESSAGE:
                if (LOGGER.isLoggable(Level.FINE))
                    LOGGER.log(Level.FINE, "Session[" + sessionId + "]: onMessage: " + message.getData());
                onMessage(message.getData());
                break;
            case EVENT:
                if (LOGGER.isLoggable(Level.FINE))
                    LOGGER.log(Level.FINE, "Session[" + sessionId + "]: onEvent: " + message.getData());
                try {
                    Map json = (Map) JSON.parse(message.getData());
                    String name = json.get("name").toString();
                    Object args = json.get("args");
                    if(args.getClass().isArray()) {
                        for(Object o: (Object[])args)
                            onEvent(name, new String[] { o.toString() });
                    } else
                        onEvent(name, new String[] { JSON.toString(args) });

                } catch(ClassCastException | NullPointerException e) {
                    LOGGER.log(Level.WARNING, "Invalid payload format: ", e);
                }
                break;
            default:
                // Ignore unknown message types
                break;
        }
    }

    @Override
    public void onPing(String data)
    {
        try
        {
            connection.send(EngineIOProtocol.createPongPacket(data));
        }
        catch (SocketIOException e)
        {
            if (LOGGER.isLoggable(Level.FINE))
                LOGGER.log(Level.FINE, "connection.send failed: ", e);

            connection.abort();
        }
    }

    @Override
    public void onClose(String data) {
        if (state == ConnectionState.CLOSING) {
            if (closeId != null && closeId.equals(data)) {
                state = ConnectionState.CLOSED;
                onDisconnect(DisconnectReason.CLOSED);
                connection.abort();
            } else {
                try {
                    connection.sendMessage(new SocketIOFrame(SocketIOFrame.FrameType.CLOSE, 0, data));
                } catch (SocketIOException e) {
                    if (LOGGER.isLoggable(Level.FINE))
                        LOGGER.log(Level.FINE, "connection.sendMessage failed: ", e);
                    connection.abort();
                }
            }
        } else {
            state = ConnectionState.CLOSING;
            try {
                connection.sendMessage(new SocketIOFrame(SocketIOFrame.FrameType.CLOSE, 0, data));
                connection.disconnectWhenEmpty();
                if ("client".equals(data))
                    onDisconnect(DisconnectReason.CLOSED_REMOTELY);
            } catch (SocketIOException e) {
                if (LOGGER.isLoggable(Level.FINE))
                    LOGGER.log(Level.FINE, "connection.sendMessage failed: ", e);
                connection.abort();
            }
        }
    }

    @Override
    public SessionTask scheduleTask(Runnable task, long delay) {
        final Future<?> future = socketIOSessionManager.executor.schedule(task, delay, TimeUnit.MILLISECONDS);
        return new SessionTask() {
            @Override
            public boolean cancel() {
                return future.cancel(false);
            }
        };
    }

    @Override
    public void onConnect(TransportConnection connection) {
        if (connection == null) {
            state = ConnectionState.CLOSED;
            inbound = null;
            socketIOSessionManager.socketIOSessions.remove(sessionId);
        } else if (this.connection == null) {
            this.connection = connection;
            if (inbound == null) {
                state = ConnectionState.CLOSED;
                connection.abort();
            } else {
                try {
                    state = ConnectionState.CONNECTED;
                    inbound.onConnect(connection);
                    startHeartbeatTimer();
                } catch (Throwable e) {
                    if (LOGGER.isLoggable(Level.WARNING))
                        LOGGER.log(Level.WARNING, "Session[" + sessionId + "]: Exception thrown by SocketIOInbound.onConnect()", e);
                    state = ConnectionState.CLOSED;
                    connection.abort();
                }
            }
        } else {
            connection.abort();
        }
    }

    @Override
    public void onMessage(String message) {
        if (inbound != null) {
            try {
                inbound.onMessage(SocketIOFrame.TEXT_MESSAGE_TYPE, message);
            } catch (Throwable e) {
                if (LOGGER.isLoggable(Level.WARNING))
                    LOGGER.log(Level.WARNING, "Session[" + sessionId + "]: Exception thrown by SocketIOInbound.onMessage()", e);
            }
        }
    }

    @Override
    public void onEvent(String name, Object[] args) {
        if (inbound != null) {
            try {
                inbound.onEvent(name, args);
            } catch (Throwable e) {
                if (LOGGER.isLoggable(Level.WARNING))
                    LOGGER.log(Level.WARNING, "Session[" + sessionId + "]: Exception thrown by SocketIOInbound.onEvent()", e);
            }
        }
    }


    @Override
    public void onDisconnect(DisconnectReason reason) {
        if (LOGGER.isLoggable(Level.FINE))
            LOGGER.log(Level.FINE, "Session[" + sessionId + "]: onDisconnect: " + reason);
        clearTimeoutTimer();
        clearHeartbeatTimer();
        if (inbound != null) {
            state = ConnectionState.CLOSED;
            try {
                inbound.onDisconnect(reason, null);
            } catch (Throwable e) {
                if (LOGGER.isLoggable(Level.WARNING))
                    LOGGER.log(Level.WARNING, "Session[" + sessionId + "]: Exception thrown by SocketIOInbound.onDisconnect()", e);
            }
            inbound = null;
        }
    }

    @Override
    public void onShutdown()
    {
        if (LOGGER.isLoggable(Level.FINE))
            LOGGER.log(Level.FINE, "Session[" + sessionId + "]: onShutdown");

        if (inbound != null)
        {
            if (state == ConnectionState.CLOSING)
            {
                if (closeId != null)
                    onDisconnect(DisconnectReason.CLOSE_FAILED);
                else
                    onDisconnect(DisconnectReason.CLOSED_REMOTELY);
            }
            else
                onDisconnect(DisconnectReason.ERROR);
        }
        socketIOSessionManager.socketIOSessions.remove(sessionId);
    }
}