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

import uk.co.real_logic.artio.fixp.FixPContext;
import uk.co.real_logic.artio.fixp.FixPFirstMessageResponse;
import uk.co.real_logic.artio.messages.FixPProtocolType;

import static uk.co.real_logic.artio.binary_entrypoint.BinaryEntryPointProtocol.unsupported;
import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_INT;
import static uk.co.real_logic.artio.fixp.AbstractFixPSequenceExtractor.NEXT_SESSION_VERSION_ID;
import static uk.co.real_logic.artio.fixp.FixPFirstMessageResponse.*;

public class BinaryEntryPointContext implements FixPContext
{

    // persisted state
    private final long sessionID;
    private final long sessionVerID;
    private final long requestTimestampInNs;
    private final long enteringFirm;
    private boolean ended;

    // Not persisted
    private final boolean fromNegotiate;
    private final BinaryEntryPointKey key;
    private boolean hasUnsentMessagesAtNegotiate;

    private int offset = MISSING_INT;

    public BinaryEntryPointContext(
        final long sessionID,
        final long sessionVerID,
        final long timestampInNs,
        final long enteringFirm,
        final boolean fromNegotiate)
    {
        this.sessionID = sessionID;
        this.sessionVerID = sessionVerID;
        this.requestTimestampInNs = timestampInNs;
        this.enteringFirm = enteringFirm;
        this.fromNegotiate = fromNegotiate;

        ended = false;
        hasUnsentMessagesAtNegotiate = sessionVerID == NEXT_SESSION_VERSION_ID;
        key = new BinaryEntryPointKey(sessionID);
    }

    public static BinaryEntryPointContext forNextSessionVerID(
        final long sessionId, final long nanoTime, final long firmId)
    {
        return new BinaryEntryPointContext(
            sessionId,
            BinaryEntryPointConnection.NEXT_SESSION_VERSION_ID,
            nanoTime,
            firmId,
            true);
    }

    public long sessionID()
    {
        return sessionID;
    }

    public long sessionVerID()
    {
        return sessionVerID;
    }

    public long requestTimestampInNs()
    {
        return requestTimestampInNs;
    }

    public long enteringFirm()
    {
        return enteringFirm;
    }

    public boolean fromNegotiate()
    {
        return fromNegotiate;
    }

    /**
     * {@inheritDoc}
     */
    public BinaryEntryPointKey key()
    {
        return key;
    }

    /**
     * {@inheritDoc}
     */
    public FixPFirstMessageResponse checkAccept(final FixPContext fixPContext, final boolean ignoreFromNegotiate)
    {
        if (fixPContext == null)
        {
            if (ignoreFromNegotiate)
            {
                return OK;
            }
            else
            {
                return checkFirstConnect();
            }
        }

        validateType(fixPContext);

        final BinaryEntryPointContext oldContext = (BinaryEntryPointContext)fixPContext;
        if (sessionID != oldContext.sessionID)
        {
            throw new IllegalArgumentException("Unable to compare: " + sessionID + " to " + oldContext.sessionID);
        }

        offset = oldContext.offset();

        if (ignoreFromNegotiate)
        {
            // cannot re-started an ended session
            return oldContext.ended ? VER_ID_ENDED : OK;
        }
        else
        {
            // negotiations should increment the session ver id
            final long oldSessionVerID = oldContext.sessionVerID;
            if (fromNegotiate)
            {
                if (sessionVerID > oldSessionVerID)
                {
                    // We only copy this once as they will be sent on the next logon and then become part of that
                    // session version id form that point onwards
                    hasUnsentMessagesAtNegotiate = oldSessionVerID == NEXT_SESSION_VERSION_ID;
                    return OK;
                }

                return NEGOTIATE_DUPLICATE_ID;
            }
            // establish messages shouldn't
            else
            {
                // Continue the same sequence
                if (oldSessionVerID == sessionVerID)
                {
                    // cannot re-restablish an ended session
                    return oldContext.ended ? VER_ID_ENDED : OK;
                }

                return ESTABLISH_UNNEGOTIATED;
            }
        }
    }

    private void validateType(final FixPContext fixPContext)
    {
        if (!(fixPContext instanceof BinaryEntryPointContext))
        {
            throw new IllegalArgumentException("Unable to compare protocol: " + this + " to " + fixPContext);
        }
    }

    public int compareVersion(final FixPContext fixPContext)
    {
        validateType(fixPContext);

        final BinaryEntryPointContext oldContext = (BinaryEntryPointContext)fixPContext;
        return Long.compare(sessionVerID, oldContext.sessionVerID);
    }

    /**
     * {@inheritDoc}
     */
    public void initiatorReconnect(final boolean reestablishConnection)
    {
        unsupported();
    }

    public boolean onInitiatorNegotiateResponse()
    {
        return unsupported();
    }

    public void onInitiatorDisconnect()
    {
        unsupported();
    }

    /**
     * {@inheritDoc}
     */
    public FixPProtocolType protocolType()
    {
        return FixPProtocolType.BINARY_ENTRYPOINT;
    }

    /**
     * {@inheritDoc}
     */
    public void onEndSequence()
    {
        ended = true;
    }

    public FixPFirstMessageResponse checkFirstConnect()
    {
        if (!fromNegotiate)
        {
            return ESTABLISH_UNNEGOTIATED;
        }

        return OK;
    }

    void offset(final int offset)
    {
        this.offset = offset;
    }

    int offset()
    {
        return offset;
    }

    boolean ended()
    {
        return ended;
    }

    void ended(final boolean ended)
    {
        this.ended = ended;
    }

    public long surrogateSessionId()
    {
        return sessionID;
    }

    public boolean equals(final Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (o == null || getClass() != o.getClass())
        {
            return false;
        }

        final BinaryEntryPointContext that = (BinaryEntryPointContext)o;

        if (sessionID != that.sessionID)
        {
            return false;
        }
        if (sessionVerID != that.sessionVerID)
        {
            return false;
        }
        if (requestTimestampInNs != that.requestTimestampInNs)
        {
            return false;
        }
        return enteringFirm == that.enteringFirm;
    }

    public int hashCode()
    {
        int result = (int)(sessionID ^ (sessionID >>> 32));
        result = 31 * result + (int)(sessionVerID ^ (sessionVerID >>> 32));
        result = 31 * result + (int)(requestTimestampInNs ^ (requestTimestampInNs >>> 32));
        result = 31 * result + (int)(enteringFirm ^ (enteringFirm >>> 32));
        return result;
    }

    public String toString()
    {
        return "BinaryEntryPointContext{" +
            "sessionID=" + sessionID +
            ", sessionVerID=" + sessionVerID +
            ", requestTimestampInNs=" + requestTimestampInNs +
            ", enteringFirm=" + enteringFirm +
            ", fromNegotiate=" + fromNegotiate +
            '}';
    }

    public boolean hasUnsentMessagesAtNegotiate()
    {
        return hasUnsentMessagesAtNegotiate;
    }
}
