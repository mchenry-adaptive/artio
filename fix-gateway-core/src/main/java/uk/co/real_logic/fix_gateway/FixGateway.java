/*
 * Copyright 2015 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway;

import uk.co.real_logic.aeron.Aeron;
import uk.co.real_logic.aeron.Subscription;
import uk.co.real_logic.aeron.common.AgentRunner;
import uk.co.real_logic.aeron.common.BackoffIdleStrategy;
import uk.co.real_logic.agrona.concurrent.OneToOneConcurrentArrayQueue;
import uk.co.real_logic.agrona.concurrent.Signal;
import uk.co.real_logic.fix_gateway.commands.*;
import uk.co.real_logic.fix_gateway.framer.*;
import uk.co.real_logic.fix_gateway.framer.session.InitiatorSession;
import uk.co.real_logic.fix_gateway.framer.session.SessionManager;
import uk.co.real_logic.fix_gateway.framer.session.SessionProxy;
import uk.co.real_logic.fix_gateway.replication.ReplicationStreams;

import java.net.InetSocketAddress;

public class FixGateway implements AutoCloseable
{
    private final Aeron aeron;
    private final ReplicationStreams streams;

    private final SenderProxy senderProxy;
    private final ReceiverProxy receiverProxy;
    private final SessionManagerProxy sessionManagerProxy;

    private final Sender sender;
    private final Receiver receiver;
    private final SessionManager sessionManager;

    private final AgentRunner senderRunner;
    private final AgentRunner receiverRunner;
    private final AgentRunner sessionManagerRunner;

    private final Signal signal = new Signal();
    private final long connectionTimeout;

    private InitiatorSession addedSession;

    FixGateway(final StaticConfiguration configuration)
    {
        connectionTimeout = configuration.connectionTimeout();

        Aeron.Context context = new Aeron.Context();
        aeron = Aeron.connect(context);
        // TODO: aeron channel configuration
        streams = new ReplicationStreams("udp://localhost:9998", aeron);

        // TODO: MPSC queue?
        final OneToOneConcurrentArrayQueue<SenderCommand> senderCommands = new OneToOneConcurrentArrayQueue<>(10);
        final OneToOneConcurrentArrayQueue<ReceiverCommand> receiverCommands = new OneToOneConcurrentArrayQueue<>(10);
        final OneToOneConcurrentArrayQueue<SessionManagerCommand> sessionManagerCommands =
            new OneToOneConcurrentArrayQueue<>(10);

        senderProxy = new SenderProxy(senderCommands);
        receiverProxy = new ReceiverProxy(receiverCommands);
        sessionManagerProxy = new SessionManagerProxy(sessionManagerCommands);

        final Multiplexer multiplexer = new Multiplexer();
        final Subscription dataSubscription = streams.dataSubscription(multiplexer);
        final SessionProxy sessionProxy = new SessionProxy(configuration.encoderBufferSize(),
            streams.dataPublication(), configuration.sessionIdStrategy());
        final MessageHandler messageHandler = (buffer, offset, length, sessionId) ->
        {
            System.out.printf("Message received from %d\n", sessionId);
        };

        final ConnectionHandler handler = new ConnectionHandler(
            System::currentTimeMillis,
            sessionProxy,
            configuration.receiverBufferSize(),
            configuration.defaultHeartbeatInterval(),
            configuration.sessionIdStrategy(),
            messageHandler);

        sender = new Sender(senderCommands, handler, receiverProxy, sessionManagerProxy, this, multiplexer,
            dataSubscription);

        receiver = new Receiver(configuration.bindAddress(), handler, receiverCommands, senderProxy);
        sessionManager = new SessionManager(sessionManagerCommands);

        senderRunner = new AgentRunner(backoffIdleStrategy(), Throwable::printStackTrace, null, sender);
        receiverRunner = new AgentRunner(backoffIdleStrategy(), Throwable::printStackTrace, null, receiver);
        sessionManagerRunner = new AgentRunner(backoffIdleStrategy(), Throwable::printStackTrace, null, sessionManager);
    }

    private BackoffIdleStrategy backoffIdleStrategy()
    {
        return new BackoffIdleStrategy(0, 0, 1, 1000);
    }

    public static FixGateway launch(final StaticConfiguration configuration)
    {
        return new FixGateway(configuration).start();
    }

    private FixGateway start()
    {
        start(senderRunner);
        start(receiverRunner);
        start(sessionManagerRunner);
        return this;
    }

    private void start(final AgentRunner runner)
    {
        Thread thread = new Thread(runner);
        thread.setName(runner.agent().roleName());
        thread.start();
    }

    // TODO: figure out correct type for dictionary
    public synchronized InitiatorSession initiate(final SessionConfiguration configuration, final Object dictionary)
    {
        final InetSocketAddress address = new InetSocketAddress(configuration.host(), configuration.port());
        senderProxy.connect(address, configuration);
        signal.await(connectionTimeout);
        if (addedSession == null)
        {
            throw new ConnectionTimeoutException("Connection timed out whilst connecting to: " + address);
        }
        return addedSession;
    }

    public synchronized void close() throws Exception
    {
        senderRunner.close();
        receiverRunner.close();
        sessionManagerRunner.close();

        sender.onClose();
        receiver.onClose();

        streams.dataPublication().close();
        streams.controlPublication().close();
        aeron.close();
    }

    public void onInitiatorSessionActive(final InitiatorSession session)
    {
        addedSession = session;
        signal.signal();
    }
}
