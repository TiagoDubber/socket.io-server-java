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

import com.codeminders.socketio.common.ConnectionState;
import com.codeminders.socketio.common.DisconnectReason;

public interface SocketIOSession {
    void setAttribute(String key, Object val);
    Object getAttribute(String key);

    interface SessionTask {
		/**
		 * @return True if task was or was already canceled, false if the task is executing or has executed.
		 */
		boolean cancel();
	}

	String getSessionId();

	ConnectionState getConnectionState();
	
	SocketIOInbound getInbound();

	TransportConnection getConnection();

	//TODO: EngineIO docs says that server is suppose to send PING and
    //TODO: client to respond with PONG but I'm seeing that client is sending PINGs,
    //TODO: at least for websocket transport. Rigth now I will relay on a client sending pings
	void setHeartbeat(long delay);
	long getHeartbeat();
	void setTimeout(long timeout);
	long getTimeout();

	void startTimeoutTimer();
	void clearTimeoutTimer();

	void startHeartbeatTimer();
	void clearHeartbeatTimer();

	/**
	 * Initiate close.
	 */
	void startClose();

    void onPacket(EngineIOPacket packet);
    void onPacket(SocketIOPacket packet);

	void onMessage(SocketIOFrame message);
	void onPing(String data);

    void onClose(String data);
	void onEvent(String name, Object[] args);

	SessionTask scheduleTask(Runnable task, long delay);
	
	/**
	 * @param connection The connection or null if the connection failed.
	 */
	void onConnect(TransportConnection connection);
	
	/**
	 * Pass message through to contained SocketIOInbound
	 * If a timeout timer is set, then it will be reset.
	 * @param message
	 */
	void onMessage(String message);
	
	void onDisconnect(DisconnectReason reason);

	/**
	 * Called by handler to report that it is done and the session can be cleaned up.
	 * If onDisconnect has not been called yet, then it will be called with DisconnectReason.ERROR.
	 */
	void onShutdown();
}