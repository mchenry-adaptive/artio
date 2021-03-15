/*
 * Copyright 2015-2021 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.engine.framer;

import uk.co.real_logic.artio.messages.ConnectionType;

abstract class GatewaySession
{
    protected static final int NO_TIMEOUT = -1;

    protected final ConnectionType connectionType;
    protected final long authenticationTimeoutInMs;

    protected long sessionId;
    protected long connectionId;
    protected String address;
    protected long disconnectTimeInMs = NO_TIMEOUT;

    protected boolean hasStartedAuthentication = false;
    protected int libraryId;

    GatewaySession(
        final long connectionId,
        final long sessionId,
        final String address,
        final ConnectionType connectionType,
        final long authenticationTimeoutInMs)
    {
        this.connectionId = connectionId;
        this.sessionId = sessionId;
        this.address = address;
        this.connectionType = connectionType;
        this.authenticationTimeoutInMs = authenticationTimeoutInMs;
    }

    public long connectionId()
    {
        return connectionId;
    }

    public abstract String address();

    public long sessionId()
    {
        return sessionId;
    }

    abstract int poll(long timeInMs, long timeInNs);

    void startAuthentication(final long timeInMs)
    {
        hasStartedAuthentication = true;
        disconnectTimeInMs = timeInMs + authenticationTimeoutInMs;
    }

    void onAuthenticationResult()
    {
        disconnectTimeInMs = NO_TIMEOUT;
    }

    ConnectionType connectionType()
    {
        return connectionType;
    }

    void disconnectAt(final long disconnectTimeout)
    {
        this.disconnectTimeInMs = disconnectTimeout;
    }

    public void libraryId(final int libraryId)
    {
        this.libraryId = libraryId;
    }

    public int libraryId()
    {
        return libraryId;
    }

    public void consumeOfflineSession(final GatewaySession oldGatewaySession)
    {
        libraryId(oldGatewaySession.libraryId());
    }

    abstract long lastLogonTime();

    abstract void acceptorSequenceNumbers(
        int lastSentSequenceNumber, int lastReceivedSequenceNumber);

    abstract void onDisconnect();

    abstract void close();
}
