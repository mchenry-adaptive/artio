/*
 * Copyright 2021 Monotonic Ltd.
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
package uk.co.real_logic.artio.binary_entrypoint;

import b3.entrypoint.fixp.sbe.*;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.Pressure;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.fixp.FixPContext;
import uk.co.real_logic.artio.fixp.FixPMessageDissector;
import uk.co.real_logic.artio.library.CancelOnDisconnect;
import uk.co.real_logic.artio.library.FixPSessionOwner;
import uk.co.real_logic.artio.library.InternalFixPConnection;
import uk.co.real_logic.artio.messages.CancelOnDisconnectOption;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.messages.ThrottleConfigurationStatus;
import uk.co.real_logic.artio.protocol.GatewayPublication;

import java.nio.ByteOrder;

import static b3.entrypoint.fixp.sbe.CancelOnDisconnectType.DO_NOT_CANCEL_ON_DISCONNECT_OR_TERMINATE;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static uk.co.real_logic.artio.CommonConfiguration.NO_FIXP_MAX_RETRANSMISSION_RANGE;
import static uk.co.real_logic.artio.LogTag.FIXP_SESSION;
import static uk.co.real_logic.artio.engine.EngineConfiguration.MAX_COD_TIMEOUT_IN_MS;
import static uk.co.real_logic.artio.engine.SessionInfo.UNK_SESSION;
import static uk.co.real_logic.artio.engine.logger.ReplayIndexDescriptor.FOR_NEXT_SESSION_VERSION;
import static uk.co.real_logic.artio.engine.logger.SequenceNumberIndexWriter.NO_REQUIRED_POSITION;
import static uk.co.real_logic.artio.fixp.AbstractFixPSequenceExtractor.FORCE_START_REPLAY_CORR_ID;
import static uk.co.real_logic.artio.fixp.FixPConnection.State.*;

/**
 * External users should never rely on this API.
 */
class InternalBinaryEntryPointConnection
    extends InternalFixPConnection implements BinaryEntryPointConnection
{
    private static final UnsafeBuffer EMPTY_BUFFER = new UnsafeBuffer(new byte[0]);
    private static final int THROTTLE_REASON = 99;

    private final BinaryEntryPointProxy proxy;
    private final long maxFixPKeepaliveTimeoutInMs;
    private final int maxRetransmissionRange;
    private final CancelOnDisconnect cancelOnDisconnect;

    private TerminationCode resendTerminationCode;

    private long sessionId;
    private long sessionVerId;
    private CancelOnDisconnectType cancelOnDisconnectType;
    private long codTimeoutWindowInMs;
    // true iff we've sent a redact then got back-pressured sending a message after
    private boolean suppressRedactResend = false;
    private boolean suppressInboundValidResend = false;
    private boolean suppressRetransmissionResend = false;
    private boolean replaying = false;
    private BinaryEntryPointContext context;
    private boolean retransmitOfflineNextSessionMessages = false;

    InternalBinaryEntryPointConnection(
        final BinaryEntryPointProtocol protocol,
        final long connectionId,
        final GatewayPublication outboundPublication,
        final GatewayPublication inboundPublication,
        final int libraryId,
        final FixPSessionOwner owner,
        final long lastReceivedSequenceNumber,
        final long lastSentSequenceNumber,
        final long lastConnectPayload,
        final CommonConfiguration configuration,
        final BinaryEntryPointContext context,
        final FixPMessageDissector dissector)
    {
        this(
            connectionId,
            outboundPublication,
            inboundPublication,
            libraryId,
            owner,
            lastReceivedSequenceNumber,
            lastSentSequenceNumber,
            lastConnectPayload,
            configuration,
            context,
            new BinaryEntryPointProxy(
            protocol, dissector,
            connectionId, outboundPublication.dataPublication(), configuration.epochNanoClock()),
            dissector);
    }

    InternalBinaryEntryPointConnection(
        final long connectionId,
        final GatewayPublication outboundPublication,
        final GatewayPublication inboundPublication,
        final int libraryId,
        final FixPSessionOwner owner,
        final long lastReceivedSequenceNumber,
        final long lastSentSequenceNumber,
        final long lastConnectPayload,
        final CommonConfiguration configuration,
        final BinaryEntryPointContext context,
        final BinaryEntryPointProxy proxy,
        final FixPMessageDissector dissector)
    {
        super(
            connectionId,
            outboundPublication,
            inboundPublication,
            libraryId,
            configuration.epochNanoClock(),
            owner,
            proxy,
            dissector);

        this.maxFixPKeepaliveTimeoutInMs = configuration.maxFixPKeepaliveTimeoutInMs();
        this.context = context;
        this.proxy = (BinaryEntryPointProxy)super.proxy;
        initialState(context);

        final long timeInMs = System.currentTimeMillis();
        nextSendMessageTimeInMs = nextReceiveMessageTimeInMs = timeInMs + configuration.noEstablishFixPTimeoutInMs();
        // default this to the max to suppress accidentally sending sequence messages during the logon process
        requestedKeepAliveIntervalInMs = maxFixPKeepaliveTimeoutInMs;
        maxRetransmissionRange = configuration.fixPAcceptedSessionMaxRetransmissionRange();

        nextRecvSeqNo(adjustSeqNo(lastReceivedSequenceNumber));
        nextSentSeqNo(adjustSeqNo(lastSentSequenceNumber));
        retransmitOfflineNextSessionMessages = lastConnectPayload == FOR_NEXT_SESSION_VERSION;
        cancelOnDisconnect = new CancelOnDisconnect(
            configuration.epochNanoClock(),
            true,
            deadlineInNs -> !Pressure.isBackPressured(outboundPublication.saveCancelOnDisconnectTrigger(
                context.sessionID(), deadlineInNs)));
        cancelOnDisconnect.enqueueTask(owner::enqueueTask);
    }

    private void initialState(final BinaryEntryPointContext context)
    {
        state(context.fromNegotiate() ? State.ACCEPTED : State.NEGOTIATED_REESTABLISH);
    }

    private long adjustSeqNo(final long lastReceivedSequenceNumber)
    {
        if (lastReceivedSequenceNumber == UNK_SESSION)
        {
            return 1;
        }

        return lastReceivedSequenceNumber + 1;
    }

    public long sessionId()
    {
        return sessionId;
    }

    public long sessionVerId()
    {
        return sessionVerId;
    }

    public BinaryEntryPointKey key()
    {
        return context.key();
    }

    public CancelOnDisconnectType cancelOnDisconnectType()
    {
        return cancelOnDisconnectType;
    }

    public long codTimeoutWindow()
    {
        return codTimeoutWindowInMs;
    }

    protected void keepAliveExpiredTerminate()
    {
        terminate(TerminationCode.UNSPECIFIED);
    }

    public void terminate(final TerminationCode terminationCode)
    {
        validateCanSend();

        internalTerminateInclResend(terminationCode);
    }

    // Handles resends
    private void internalTerminateInclResend(final TerminationCode terminationCode)
    {
        sendTerminate(terminationCode, State.UNBINDING, State.RESEND_TERMINATE);
    }

    public long trySendSequence()
    {
        final long position = proxy.sendSequence(sessionId, nextSentSeqNo);
        if (position > 0)
        {
            // Must check back-pressure here or we threated to suppress our retry
            onAttemptedToSendMessage();
        }
        return position;
    }

    // --------------------------------------------------
    // Internal Methods below, not part of the public API
    // --------------------------------------------------

    protected int poll(final long timeInMs)
    {
        switch (state)
        {
            case ACCEPTED:
            case SENT_NEGOTIATE_RESPONSE:
            case RETRY_NEGOTIATE_RESPONSE:
            case NEGOTIATED_REESTABLISH:
                if (timeInMs > nextReceiveMessageTimeInMs)
                {
                    fullyUnbind(DisconnectReason.AUTHENTICATION_TIMEOUT);
                }
                return 1;

            case REPLIED_FINISHED_SENDING:
            case SENT_FINISHED_SENDING:
                if (timeInMs > nextSendMessageTimeInMs)
                {
                    finishSending();
                }
                return 1;

            case RETRY_FINISHED_SENDING:
            case RETRY_REPLY_FINISHED_SENDING:
                finishSending();
                return 1;

            default:
                return commonPoll(state, timeInMs);
        }
    }

    protected int pollExtraEstablished(final long timeInMs)
    {
        return 0;
    }

    protected long sendSequence(final boolean lapsed)
    {
        return trySendSequence();
    }

    protected void onReplayComplete()
    {
        replaying = false;
    }

    public boolean isReplaying()
    {
        return replaying;
    }

    protected void onOfflineReconnect(final long connectionId, final FixPContext fixPContext)
    {
        final BinaryEntryPointContext context = (BinaryEntryPointContext)fixPContext;

        retransmitOfflineNextSessionMessages = sessionVerId == NEXT_SESSION_VERSION_ID;

        this.context = context;
        sessionVerId = context.sessionVerID();
        initialState(context);

        this.connectionId = connectionId;
        proxy.ids(connectionId, sessionId);
    }

    public Action onNegotiate(
        final long sessionId,
        final long sessionVerID,
        final long timestamp,
        final long enteringFirm,
        final long onbehalfFirm,
        final String senderLocation)
    {
        final State state = state();
        if (state == UNBOUND)
        {
            // Offline session
            onSessionId(sessionId, sessionVerID);
            return CONTINUE;
        }

        if (!(state == State.ACCEPTED || state == State.SENT_NEGOTIATE_RESPONSE))
        {
            // TODO: validation error

            if (checkFinishedSending(state))
            {
                return CONTINUE;
            }
        }

        onSessionId(sessionId, sessionVerID);

        // Reset sequence numbers upon successful negotiate
        nextRecvSeqNo = 1;
        if (!retransmitOfflineNextSessionMessages)
        {
            nextSentSeqNo = 1;
        }

        // Notify inbound sequence number index
        final long inboundPos = inboundPublication.saveRedactSequenceUpdate(
            sessionId, 0, NO_REQUIRED_POSITION);
        if (inboundPos < 0)
        {
            return ABORT;
        }

        final long position = proxy.sendNegotiateResponse(sessionId, sessionVerID, timestamp, enteringFirm);
        onAttemptedToSendMessage();
        return Pressure.apply(checkState(position, State.SENT_NEGOTIATE_RESPONSE, State.RETRY_NEGOTIATE_RESPONSE));
    }

    private void onSessionId(final long sessionId, final long sessionVerID)
    {
        this.sessionId = sessionId;
        this.sessionVerId = sessionVerID;
        proxy.ids(connectionId, sessionId);
    }

    private long checkState(final long position, final State success, final State backPressured)
    {
        if (position > 0)
        {
            state(success);
        }
        else
        {
            state(backPressured);
        }
        return position;
    }

    public Action onEstablish(
        final long sessionID,
        final long sessionVerID,
        final long timestamp,
        final long keepAliveIntervalInMs,
        final long nextSeqNo,
        final CancelOnDisconnectType cancelOnDisconnectType,
        final long codTimeoutWindow)
    {
        final State state = state();
        if (state == State.NEGOTIATED_REESTABLISH)
        {
            onSessionId(sessionID, sessionVerID);
        }
        else
        {
            checkSession(sessionID, sessionVerID);

            if (state != State.SENT_NEGOTIATE_RESPONSE)
            {
                onAttemptedToSendMessage();
                return Pressure.apply(proxy.sendEstablishReject(
                    sessionID,
                    sessionVerID,
                    timestamp,
                    EstablishRejectCode.ALREADY_ESTABLISHED));
            }
            else if (keepAliveIntervalInMs > maxFixPKeepaliveTimeoutInMs)
            {
                onAttemptedToSendMessage();
                final long position = proxy.sendEstablishReject(
                    sessionID,
                    sessionVerID,
                    timestamp,
                    EstablishRejectCode.KEEPALIVE_INTERVAL);
                if (position > 0)
                {
                    return fullyUnbind();
                }

                return Pressure.apply(position);
            }
        }

        // Notify the inbound sequence number
        if (!suppressRedactResend)
        {
            onAttemptedToSendMessage();
            final int correctSequenceNumber = (int)nextSeqNo - 1;
            final long inboundPos = inboundPublication.saveRedactSequenceUpdate(
                sessionId, correctSequenceNumber, NO_REQUIRED_POSITION);

            if (inboundPos > 0)
            {
                suppressRedactResend = true;
            }
            else
            {
                return ABORT;
            }
        }

        final long position = proxy.sendEstablishAck(
            sessionID,
            sessionVerID,
            timestamp,
            keepAliveIntervalInMs,
            nextSeqNo,
            nextRecvSeqNo - 1);

        this.requestedKeepAliveIntervalInMs = keepAliveIntervalInMs;
        onAttemptedToSendMessage();
        onReceivedMessage();

        if (position > 0)
        {
            if (retransmitOfflineNextSessionMessages)
            {
                if (nextSentSeqNo > 1)
                {
                    if (!retransmitOfflineNextSessionMessages(sessionID))
                    {
                        return ABORT;
                    }
                }
                else
                {
                    // If no offline messages were sent whilst the next session version id was used then we don't need
                    // to retransmit anything.
                    retransmitOfflineNextSessionMessages = false;
                }
            }

            setupCancelOnDisconnect(cancelOnDisconnectType, codTimeoutWindow);
            this.nextRecvSeqNo = nextSeqNo;
            suppressRedactResend = false;

            state(ESTABLISHED);
            return CONTINUE;
        }
        else
        {
            return ABORT;
        }
    }

    private boolean retransmitOfflineNextSessionMessages(final long sessionID)
    {
        // Handle the retransmit of any messages written into an offline session that
        // were supposed to be sent with the next connect

        final long endSequenceNumber = nextSentSeqNo - 1;

        if (!suppressInboundValidResend)
        {
            final long resendRequestPosition = saveValidResendRequest(
                sessionID, 1, endSequenceNumber, NEXT_SESSION_VERSION_ID,
                FORCE_START_REPLAY_CORR_ID);

            if (Pressure.isBackPressured(resendRequestPosition))
            {
                return false;
            }

            suppressInboundValidResend = true;
        }

        final long outboundPosition = saveValidResendRequest(
            outboundPublication, sessionID, 1, endSequenceNumber,
            NEXT_SESSION_VERSION_ID, FORCE_START_REPLAY_CORR_ID);

        if (Pressure.isBackPressured(outboundPosition))
        {
            return false;
        }

        replaying = true;
        retransmitOfflineNextSessionMessages = false;
        suppressInboundValidResend = false;

        return true;
    }

    private void setupCancelOnDisconnect(
        final CancelOnDisconnectType type, final long codTimeoutWindow)
    {
        // If they're using a null time value then we won't cod them.
        if (type != DO_NOT_CANCEL_ON_DISCONNECT_OR_TERMINATE &&
            codTimeoutWindow == DeltaInMillisDecoder.timeNullValue())
        {
            setupCancelOnDisconnect(DO_NOT_CANCEL_ON_DISCONNECT_OR_TERMINATE, DeltaInMillisDecoder.timeNullValue());
        }
        else
        {
            this.cancelOnDisconnectType = type;
            final CancelOnDisconnectOption option;
            switch (type)
            {
                case CANCEL_ON_DISCONNECT_ONLY:
                    option = CancelOnDisconnectOption.CANCEL_ON_DISCONNECT_ONLY;
                    break;

                case CANCEL_ON_TERMINATE_ONLY:
                    option = CancelOnDisconnectOption.CANCEL_ON_LOGOUT_ONLY;
                    break;

                case CANCEL_ON_DISCONNECT_OR_TERMINATE:
                    option = CancelOnDisconnectOption.CANCEL_ON_DISCONNECT_OR_LOGOUT;
                    break;

                case DO_NOT_CANCEL_ON_DISCONNECT_OR_TERMINATE:
                case NULL_VAL:
                default:
                    option = CancelOnDisconnectOption.DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT;
                    break;
            }

            cancelOnDisconnect.cancelOnDisconnectOption(option);

            this.codTimeoutWindowInMs = Math.min(MAX_COD_TIMEOUT_IN_MS, codTimeoutWindow);
            cancelOnDisconnect.cancelOnDisconnectTimeoutWindowInNs(MILLISECONDS.toNanos(this.codTimeoutWindowInMs));
        }
    }

    public Action onTerminate(
        final long sessionID, final long sessionVerID, final TerminationCode terminationCode)
    {
        cancelOnDisconnect.checkCancelOnDisconnectLogout(clock.nanoTime());

        // We initiated termination
        if (state == State.UNBINDING)
        {
            fullyUnbind();
        }
        // The counter-party initiated termination
        else
        {
            DebugLogger.log(FIXP_SESSION, "Terminated: ", terminationCode.name());

            sendTerminateAck(terminationCode);
        }

        checkSession(sessionID, sessionVerID);

        return CONTINUE;
    }

    protected Action unbindState(final DisconnectReason reason)
    {
        cancelOnDisconnect.checkCancelOnDisconnectDisconnect();

        super.unbindState(reason);
        return null;
    }

    private void checkSession(final long sessionID, final long sessionVerID)
    {
        // TODO: log error if incorrect
    }

    private void sendTerminateAck(final TerminationCode terminationCode)
    {
        final long position = sendTerminate(terminationCode, State.UNBOUND, State.RESEND_TERMINATE_ACK);
        if (position > 0)
        {
            fullyUnbind();
        }
    }

    private long sendTerminate(
        final TerminationCode terminationCode, final State finalState, final State resendState)
    {
        final long position = proxy.sendTerminate(
            sessionId,
            sessionVerId,
            terminationCode,
            requestTimestampInNs());

        if (position > 0)
        {
            state(finalState);
            resendTerminationCode = null;
        }
        else
        {
            state(resendState);
            resendTerminationCode = terminationCode;
        }

        return position;
    }

    public Action onSequence(final long nextSeqNo)
    {
        onReceivedMessage();

        if (checkFinishedSending(state))
        {
            return CONTINUE;
        }

        return checkSeqNo(nextSeqNo);
    }

    private Action checkSeqNo(final long nextSeqNo)
    {
        final long nextRecvSeqNo = this.nextRecvSeqNo;

        if (nextSeqNo > nextRecvSeqNo)
        {
            final long position = proxy.sendNotApplied(
                nextRecvSeqNo, nextSeqNo - nextRecvSeqNo, requestTimestampInNs());

            if (position > 0)
            {
                this.nextRecvSeqNo = nextSeqNo;
            }

            return Pressure.apply(position);
        }
        else if (nextSeqNo < nextRecvSeqNo)
        {
            internalTerminateInclResend(TerminationCode.FINISHED);
        }

        return CONTINUE;
    }

    public Action onMessage(
        final DirectBuffer buffer,
        final int offset,
        final int templateId,
        final int blockLength,
        final int version,
        final int sofhMessageSize)
    {
        onReceivedMessage();

        final State state = state();
        if (canReceiveMessage(state))
        {
            dissector.onBusinessMessage(templateId, buffer, offset, blockLength, version, true);

            final Action action = handler.onBusinessMessage(
                this,
                templateId,
                buffer,
                offset,
                blockLength,
                version,
                false);

            if (action != ABORT)
            {
                nextRecvSeqNo++;
            }

            return action;
        }
        else
        {
            checkFinishedSending(state);

            return CONTINUE;
        }
    }

    private boolean canReceiveMessage(final State state)
    {
        return state == ESTABLISHED || state == RETRANSMITTING || state == AWAITING_KEEPALIVE;
    }

    private boolean checkFinishedSending(final State state)
    {
        if (state == RECV_FINISHED_SENDING ||
            state == REPLIED_FINISHED_SENDING ||
            state == RETRY_REPLY_FINISHED_SENDING)
        {
            internalTerminateInclResend(TerminationCode.UNSPECIFIED);

            return true;
        }

        return false;
    }

    public void finishSending()
    {
        final State state = state();
        final boolean weInitiatedFinishedSending = state == ESTABLISHED;

        final long position = proxy.sendFinishedSending(
            sessionId,
            sessionVerId,
            nextRecvSeqNo - 1,
            requestTimestampInNs());

        onAttemptedToSendMessage();

        if (weInitiatedFinishedSending)
        {
            checkState(position, SENT_FINISHED_SENDING, RETRY_FINISHED_SENDING);
        }
        else // theyInitiatedFinishedSending
        {
            checkState(position, REPLIED_FINISHED_SENDING, RETRY_REPLY_FINISHED_SENDING);
        }
    }

    public Action onFinishedSending(final long sessionID, final long sessionVerID, final long lastSeqNo)
    {
        final State state = this.state;
        final boolean weInitiatedFinishedSending = state == RECV_FINISHED_RECEIVING_ONLY;
        if (state != ESTABLISHED && !weInitiatedFinishedSending)
        {
            // TODO: error
        }

        checkSession(sessionID, sessionVerID);

        // it's an abort and retry if we're in these states
        if (state != RECV_FINISHED_SENDING && state != UNBINDING && state != RESEND_TERMINATE)
        {
            final long position = proxy.sendFinishedReceiving(
                sessionID,
                sessionVerID,
                requestTimestampInNs());

            if (position > 0)
            {
                if (weInitiatedFinishedSending)
                {
                    internalTerminateInclResend(TerminationCode.FINISHED);
                }
                else // they initiated finished sending
                {
                    // we're now awaiting a finished receiving or possible retransmit request
                    state(RECV_FINISHED_SENDING);
                }

                // post: state == RECV_FINISHED_SENDING || UNBINDING || RESEND_TERMINATE

                return handler.onFinishedSending(this);
            }

            return ABORT;
        }
        else // Case that we're retrying handler.onFinishedSending() but have already done sendFinishedReceiving
        {
            return handler.onFinishedSending(this);
        }
    }

    public Action onFinishedReceiving(final long sessionID, final long sessionVerID)
    {
        final State state = this.state;
        final boolean weInitiatedFinishedSending = state == SENT_FINISHED_SENDING || state == RETRY_FINISHED_SENDING;
        final boolean theyInitiatedFinishedSending = state == REPLIED_FINISHED_SENDING;
        if (!weInitiatedFinishedSending && !theyInitiatedFinishedSending)
        {
            // TODO: error
        }

        checkSession(sessionID, sessionVerID);

        if (weInitiatedFinishedSending)
        {
            state(RECV_FINISHED_RECEIVING_ONLY);
        }
        else // theyInitiatedFinishedSending and we've replied to them
        {
            internalTerminateInclResend(TerminationCode.FINISHED);
        }

        return CONTINUE;
    }

    public Action onRetransmitRequest(
        final long sessionID, final long timestampInNs, final long fromSeqNo, final long count)
    {
        final State state = this.state;
        if (state != ESTABLISHED && state != AWAITING_KEEPALIVE)
        {
            // TODO: error, actually error should only happen if we haven't established the connection yet
        }

        if (this.sessionId != sessionID)
        {
            return sendRetransmitReject(RetransmitRejectCode.INVALID_SESSION, timestampInNs);
        }

        if (maxRetransmissionRange != NO_FIXP_MAX_RETRANSMISSION_RANGE && count > maxRetransmissionRange)
        {
            return sendRetransmitReject(RetransmitRejectCode.REQUEST_LIMIT_EXCEEDED, timestampInNs);
        }

        final long endSequenceNumber = fromSeqNo + count - 1;
        if (endSequenceNumber >= nextSentSeqNo)
        {
            return sendRetransmitReject(RetransmitRejectCode.OUT_OF_RANGE, timestampInNs);
        }

        if (replaying)
        {
            return sendRetransmitReject(RetransmitRejectCode.REQUEST_LIMIT_EXCEEDED, timestampInNs);
        }

        if (!suppressRetransmissionResend)
        {
            final long position = proxy.sendRetransmissionWithSequence(
                fromSeqNo, count, requestTimestampInNs(), timestampInNs, nextSentSeqNo);
            if (position < 0)
            {
                return ABORT;
            }
        }

        final long position = saveValidResendRequest(sessionID, fromSeqNo, endSequenceNumber, sessionVerId, 0);
        // suppress if we've failed to send the resend request to the replayer as this will cause this handler to be
        // retried
        if (position < 0)
        {
            suppressRetransmissionResend = true;
            return ABORT;
        }
        else
        {
            replaying = true;
            suppressRetransmissionResend = false;
            return CONTINUE;
        }
    }

    private long saveValidResendRequest(
        final long sessionID,
        final long fromSeqNo,
        final long endSequenceNumber,
        final long sessionVerId,
        final int correlationId)
    {
        return saveValidResendRequest(
            inboundPublication, sessionID, fromSeqNo, endSequenceNumber, sessionVerId, correlationId);
    }

    private long saveValidResendRequest(
        final GatewayPublication publication,
        final long sessionID,
        final long fromSeqNo,
        final long endSequenceNumber,
        final long sessionVerId,
        final int correlationId)
    {
        return publication.saveValidResendRequest(
            sessionID,
            connectionId,
            fromSeqNo,
            endSequenceNumber,
            (int)sessionVerId,
            correlationId,
            EMPTY_BUFFER,
            0,
            0);
    }

    private Action sendRetransmitReject(final RetransmitRejectCode rejectCode, final long timestampInNs)
    {
        return Pressure.apply(proxy.sendRetransmitReject(rejectCode, requestTimestampInNs(), timestampInNs));
    }

    public Reply<ThrottleConfigurationStatus> throttleMessagesAt(
        final int throttleWindowInMs, final int throttleLimitOfMessages)
    {
        EngineConfiguration.validateMessageThrottleOptions(throttleWindowInMs, throttleLimitOfMessages);

        return owner.messageThrottle(sessionId, throttleWindowInMs, throttleLimitOfMessages);
    }

    protected boolean onThrottleNotification(
        final long refMsgTypeValue,
        final DirectBuffer rejectRefIDBuffer,
        final int rejectRefIDOffset,
        final int rejectRefIDLength)
    {
        final State state = state();
        if (canReceiveMessage(state))
        {
            final long refSeqNum = nextRecvSeqNo++;
            final MessageType refMsgType = MessageType.get((short)refMsgTypeValue);
            final long rejectRefID = rejectRefIDBuffer.getLong(rejectRefIDOffset, ByteOrder.LITTLE_ENDIAN);
            final boolean sent = proxy.sendBusinessReject(refSeqNum, refMsgType, rejectRefID, THROTTLE_REASON) > 0;
            if (sent)
            {
                nextSentSeqNo++;
                onAttemptedToSendMessage();
            }
            return sent;
        }

        return true;
    }

    public long startEndOfDay()
    {
        final State state = this.state;
        if (state == ESTABLISHED)
        {
            terminate(TerminationCode.FINISHED);
            return 1;
        }
        else
        {
            return requestDisconnect(DisconnectReason.ENGINE_SHUTDOWN);
        }
    }
}
