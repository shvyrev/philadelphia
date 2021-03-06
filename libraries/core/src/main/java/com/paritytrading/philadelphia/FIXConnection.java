package com.paritytrading.philadelphia;

import static com.paritytrading.philadelphia.FIX.*;
import static com.paritytrading.philadelphia.FIXMsgTypes.*;
import static com.paritytrading.philadelphia.FIXTags.*;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import org.joda.time.DateTimeZone;
import org.joda.time.MutableDateTime;

/**
 * A connection.
 */
public class FIXConnection implements Closeable {

    private Clock clock;

    private SocketChannel channel;

    private FIXConfig config;

    private FIXValue beginString;
    private FIXValue bodyLength;
    private FIXValue checkSum;

    private String senderCompId;
    private String targetCompId;

    private long rxMsgSeqNum;
    private long txMsgSeqNum;

    private ByteBuffer rxBuffer;

    private ByteBuffer txHeaderBuffer;
    private ByteBuffer txBodyBuffer;

    private ByteBuffer[] txBuffers;

    /*
     * This variable is written on data reception and read on connection
     * keep-alive. These two functions can run on different threads
     * without locking.
     */
    private volatile long lastRxMillis;

    /*
     * This variable is written on data transmission and read on connection
     * keep-alive. These two functions can run on different threads but
     * require locking.
     */
    private long lastTxMillis;

    private long testRequestTxMillis;

    private final long heartbeatMillis;

    private final long testRequestMillis;

    private FIXMessageParser parser;

    private FIXConnectionStatusListener statusListener;

    private FIXMessage txMessage;

    private long currentTimeMillis;

    private MutableDateTime currentTime;

    private StringBuilder currentTimestamp;

    /**
     * Create a connection. The underlying socket channel can be either
     * blocking or non-blocking.
     *
     * @param clock the clock
     * @param channel the underlying socket channel
     * @param config the connection configuration
     * @param listener the inbound message listener
     * @param statusListener the inbound status event listener
     */
    public FIXConnection(Clock clock, SocketChannel channel, FIXConfig config, FIXMessageListener listener,
            FIXConnectionStatusListener statusListener) {
        this.clock = clock;

        this.channel = channel;

        this.config = config;

        this.beginString = new FIXValue(BEGIN_STRING_FIELD_CAPACITY);
        this.bodyLength  = new FIXValue(BODY_LENGTH_FIELD_CAPACITY);
        this.checkSum    = new FIXValue(CHECK_SUM_FIELD_CAPACITY);

        this.beginString.setString(config.getVersion().getBeginString());

        this.senderCompId = config.getSenderCompID();
        this.targetCompId = config.getTargetCompID();

        this.parser = new FIXMessageParser(new MessageHandler(listener),
                new FIXMessage(config.getMaxFieldCount(), config.getFieldCapacity()),
                config.isCheckSumEnabled());

        this.statusListener = statusListener;

        this.rxMsgSeqNum = config.getIncomingMsgSeqNum();
        this.txMsgSeqNum = config.getOutgoingMsgSeqNum();

        this.rxBuffer = ByteBuffer.allocateDirect(config.getRxBufferCapacity());

        this.txHeaderBuffer = ByteBuffer.allocateDirect(config.getTxBufferCapacity());
        this.txBodyBuffer = ByteBuffer.allocateDirect(config.getTxBufferCapacity());

        this.txBuffers = new ByteBuffer[2];

        this.txBuffers[0] = txHeaderBuffer;
        this.txBuffers[1] = txBodyBuffer;

        this.lastRxMillis = clock.currentTimeMillis();
        this.lastTxMillis = clock.currentTimeMillis();

        this.heartbeatMillis = config.getHeartBtInt() * 1000;

        this.testRequestMillis = config.getHeartBtInt() * 1100;

        this.testRequestTxMillis = 0;

        this.txMessage = new FIXMessage(config.getMaxFieldCount(), config.getFieldCapacity());

        this.currentTimeMillis = clock.currentTimeMillis();

        this.currentTime = new MutableDateTime(this.currentTimeMillis, DateTimeZone.UTC);

        this.currentTimestamp = new StringBuilder(config.getFieldCapacity());

        FIXTimestamps.append(this.currentTime, this.currentTimestamp);
    }

    /**
     * Create a connection. The underlying socket channel can be either
     * blocking or non-blocking.
     *
     * @param channel the underlying socket channel
     * @param config the connection configuration
     * @param listener the inbound message listener
     * @param statusListener the inbound status event listener
     */
    public FIXConnection(SocketChannel channel, FIXConfig config, FIXMessageListener listener,
            FIXConnectionStatusListener statusListener) {
        this(System::currentTimeMillis, channel, config, listener, statusListener);
    }

    /**
     * Get the underlying socket channel.
     *
     * @return the underlying socket channel
     */
    public SocketChannel getChannel() {
        return channel;
    }

    /**
     * Get the next incoming MsgSeqNum(34).
     *
     * @return the next incoming MsgSeqNum(34)
     */
    public long getIncomingMsgSeqNum() {
        return rxMsgSeqNum;
    }

    void setIncomingMsgSeqNum(long incomingMsgSeqNum) {
        rxMsgSeqNum = incomingMsgSeqNum;
    }

    /**
     * Get the next outgoing MsgSeqNum(34).
     *
     * @return the next outgoing MsgSeqNum(34)
     */
    public long getOutgoingMsgSeqNum() {
        return txMsgSeqNum;
    }

    /**
     * Get the SenderCompID(49).
     *
     * @return the SenderCompID(49)
     */
    public String getSenderCompID() {
        return senderCompId;
    }

    /**
     * Get the TargetCompID(56).
     *
     * @return the TargetCompID(56)
     */
    public String getTargetCompID() {
        return targetCompId;
    }

    /**
     * Create a message container.
     *
     * @return a message container
     */
    public FIXMessage create() {
        return new FIXMessage(config.getMaxFieldCount(), config.getFieldCapacity());
    }

    /**
     * <p>Prepare a message. When preparing a message, the following mandatory
     * fields are added:</p>
     *
     * <ul>
     *   <li>MsgType(35)</li>
     *   <li>SenderCompID(49)</li>
     *   <li>TargetCompID(56)</li>
     *   <li>MsgSeqNum(34)</li>
     *   <li>SendingTime(52)</li>
     * </ul>
     *
     * @param message a message
     * @param msgType the MsgType(35)
     */
    public void prepare(FIXMessage message, char msgType) {
        message.reset();

        message.addField(MsgType).setChar(msgType);

        prepare(message);
    }

    /**
     * <p>Prepare a message.</p>
     *
     * @param message a message
     * @param msgType the MsgType(35)
     * @see #prepare(FIXMessage, char)
     */
    public void prepare(FIXMessage message, CharSequence msgType) {
        message.reset();

        message.addField(MsgType).setString(msgType);

        prepare(message);
    }

    private void prepare(FIXMessage message) {
        message.addField(SenderCompID).setString(senderCompId);
        message.addField(TargetCompID).setString(targetCompId);
        message.addField(MsgSeqNum).setInt(txMsgSeqNum);
        message.addField(SendingTime).setString(currentTimestamp);
    }

    /**
     * <p>Update a message. When updating a message, the following mandatory
     * fields are updated:</p>
     *
     * <ul>
     *   <li>MsgSeqNum(34)</li>
     *   <li>SendingTime(52)</li>
     * </ul>
     *
     * @param message a message
     * @throws IllegalStateException if MsgSeqNum(34) or SendingTime(52) is
     *   not found
     */
    public void update(FIXMessage message) {
        FIXValue msgSeqNum = message.valueOf(MsgSeqNum);
        if (msgSeqNum == null)
            throw new IllegalStateException("MsgSeqNum(34) not found");

        msgSeqNum.setInt(txMsgSeqNum);

        FIXValue sendingTime = message.valueOf(SendingTime);
        if (sendingTime == null)
            throw new IllegalStateException("SendingTime(52) not found");

        sendingTime.setString(currentTimestamp);
    }

    /**
     * Update SenderCompID(49) and TargetCompID(56).
     *
     * @param message a message
     * @throws IllegalStateException if SenderCompID(49) or TargetCompID(56)
     *   is not found
     */
    public void updateCompID(FIXMessage message) {
        FIXValue value;

        value = message.valueOf(SenderCompID);
        if (value == null)
            throw new IllegalStateException("SenderCompID(49) not found");

        value.setString(senderCompId);

        value = message.valueOf(TargetCompID);
        if (value == null)
            throw new IllegalStateException("TargetCompID(56) not found");

        value.setString(targetCompId);
    }

    /**
     * <p>Update the current timestamp. The current timestamp is used for the
     * following purposes:</p>
     *
     * <ul>
     *   <li>SendingTime(52)</li>
     *   <li>the connection keep-alive mechanism</li>
     * </ul>
     */
    public void updateCurrentTimestamp() {
        currentTimeMillis = clock.currentTimeMillis();

        currentTime.setMillis(currentTimeMillis);

        currentTimestamp.setLength(0);

        FIXTimestamps.append(currentTime, currentTimestamp);
    }

    /**
     * Get the current timestamp.
     *
     * @return the current timestamp
     */
    public CharSequence getCurrentTimestamp() {
        return currentTimestamp;
    }

    /**
     * Keep this connection alive.
     *
     * <p>If the duration indicated by HeartBtInt(108) has passed since
     * sending a message, send a Heartbeat(0) message.</p>
     *
     * <p>If the duration indicated by HeartBtInt(108) amended with a
     * reasonable transmission time has passed since receiving a message,
     * send a TestRequest(1) message.</p>
     *
     * <p>If a TestRequest(1) message has been sent and no Heartbeat(0)
     * message has been received in the duration indicated by HeartBtInt(108)
     * amended with a reasonable transmission time, trigger a status event
     * indicating heartbeat timeout.</p>
     *
     * @throws IOException if an I/O error occurs
     */
    public void keepAlive() throws IOException {
        if (currentTimeMillis - lastTxMillis > heartbeatMillis)
            sendHeartbeat();

        if (testRequestTxMillis == 0) {
            if (currentTimeMillis - lastRxMillis > testRequestMillis) {
                sendTestRequest(currentTimestamp);

                testRequestTxMillis = currentTimeMillis;
            }
        } else {
            if (currentTimeMillis - testRequestTxMillis > testRequestMillis) {
                statusListener.heartbeatTimeout(this);

                testRequestTxMillis = 0;
            }
        }
    }

    /**
     * Close the underlying socket channel.
     *
     * @throws IOException if an I/O error occurs
     */
    @Override
    public void close() throws IOException {
        channel.close();
    }

    /**
     * Receive data from the underlying socket channel. For each message
     * received, invoke the message listener if applicable.
     *
     * @return the number of bytes read, possibly zero, or -1 if the channel
     *   has reached end-of-stream
     * @throws IOException if an I/O error occurs
     */
    public int receive() throws IOException {
        int bytes = channel.read(rxBuffer);

        if (bytes <= 0)
            return bytes;

        rxBuffer.flip();

        while (parser.parse(rxBuffer));

        rxBuffer.compact();

        if (rxBuffer.position() == rxBuffer.capacity())
            throw new FIXMessageOverflowException("Too long message");

        lastRxMillis = currentTimeMillis;

        return bytes;
    }

    /**
     * Send a message.
     *
     * @param message a message
     * @throws IOException if an I/O error occurs
     */
    public void send(FIXMessage message) throws IOException {
        txBodyBuffer.clear();
        message.put(txBodyBuffer);

        bodyLength.setInt(txBodyBuffer.position());

        txHeaderBuffer.clear();
        FIXTags.put(txHeaderBuffer, BeginString);
        beginString.put(txHeaderBuffer);
        FIXTags.put(txHeaderBuffer, BodyLength);
        bodyLength.put(txHeaderBuffer);

        checkSum.setCheckSum(FIXCheckSums.sum(txHeaderBuffer, 0, txHeaderBuffer.position()) +
                FIXCheckSums.sum(txBodyBuffer, 0, txBodyBuffer.position()));

        FIXTags.put(txBodyBuffer, CheckSum);
        checkSum.put(txBodyBuffer);

        txHeaderBuffer.flip();
        txBodyBuffer.flip();

        int remaining = txHeaderBuffer.remaining() + txBodyBuffer.remaining();

        do {
            remaining -= channel.write(txBuffers, 0, txBuffers.length);
        } while (remaining > 0);

        txMsgSeqNum++;

        lastTxMillis = currentTimeMillis;
    }

    /**
     * Send a Reject(3) message.
     *
     * @param refSeqNum the RefSeqNum(45)
     * @param sessionRejectReason the SessionRejectReason(373)
     * @param text the Text(58)
     * @throws IOException if an I/O error occurs
     */
    public void sendReject(long refSeqNum, long sessionRejectReason, CharSequence text) throws IOException {
        prepare(txMessage, Reject);

        txMessage.addField(RefSeqNum).setInt(refSeqNum);
        txMessage.addField(SessionRejectReason).setInt(sessionRejectReason);
        txMessage.addField(Text).setString(text);

        send(txMessage);
    }

    /**
     * Send a Logout(5) message.
     *
     * @throws IOException if an I/O error occurs
     */
    public void sendLogout() throws IOException {
        prepare(txMessage, Logout);

        send(txMessage);
    }

    /**
     * Send a Logout(5) message.
     *
     * @param text the Text(58)
     * @throws IOException if an I/O error occurs
     */
    public void sendLogout(CharSequence text) throws IOException {
        prepare(txMessage, Logout);

        txMessage.addField(Text).setString(text);

        send(txMessage);
    }

    /**
     * Send a Logon(A) message. Set EncryptMethod(98) to 0 and HeartBtInt(108)
     * according to the connection configuration.
     *
     * @param resetSeqNum if true set ResetSeqNumFlag(141) to true, otherwise
     *   omit ResetSeqNumFlag(141)
     * @throws IOException if an I/O error occurs
     */
    public void sendLogon(boolean resetSeqNum) throws IOException {
        prepare(txMessage, Logon);

        txMessage.addField(EncryptMethod).setInt(0);
        txMessage.addField(HeartBtInt).setInt(config.getHeartBtInt());

        if (resetSeqNum)
            txMessage.addField(ResetSeqNumFlag).setChar('Y');

        send(txMessage);
    }

    private class MessageHandler implements FIXMessageListener {

        private FIXMessageListener downstream;

        private StringBuilder string;

        MessageHandler(FIXMessageListener downstream) {
            this.downstream = downstream;

            this.string = new StringBuilder(config.getFieldCapacity());
        }

        @Override
        public void message(FIXMessage message) throws IOException {
            long msgSeqNum = message.getMsgSeqNum();
            if (msgSeqNum == 0) {
                sendLogout("MsgSeqNum(34) not found");
                return;
            }

            FIXValue msgType = message.getMsgType();
            if (msgType == null) {
                statusListener.close(FIXConnection.this, "MsgType(35) not found");
                return;
            }

            if (msgType.length() == 1 && msgType.asChar() == SequenceReset) {
                if (handleSequenceReset(message))
                    return;
            }

            if (msgSeqNum > rxMsgSeqNum) {
                sendResendRequest(rxMsgSeqNum);
                return;
            }

            if (msgSeqNum < rxMsgSeqNum) {
                handleTooLowMsgSeqNum(message, msgType, msgSeqNum);
                return;
            }

            rxMsgSeqNum++;

            if (msgType.length() != 1) {
                downstream.message(message);
                return;
            }

            switch (msgType.byteAt(0)) {
            case Heartbeat:
                handleHeartbeat();
                break;
            case TestRequest:
                handleTestRequest(message);
                break;
            case ResendRequest:
                handleResendRequest(message);
                break;
            case Reject:
                handleReject(message);
                break;
            case SequenceReset:
                handleSequenceReset(message);
                break;
            case Logout:
                handleLogout(message);
                break;
            case Logon:
                handleLogon(message);
                break;
            default:
                downstream.message(message);
                break;
            }
        }

        private void handleTooLowMsgSeqNum(FIXMessage message, FIXValue msgType, long msgSeqNum) throws IOException {
            if (msgType.length() != 1 || msgType.asChar() != SequenceReset) {
                FIXValue possDupFlag = message.valueOf(PossDupFlag);

                if (possDupFlag == null || possDupFlag.asChar() != 'Y')
                    statusListener.tooLowMsgSeqNum(FIXConnection.this, msgSeqNum, rxMsgSeqNum);
            }
        }

        private void handleHeartbeat() {
            testRequestTxMillis = 0;
        }

        private void handleTestRequest(FIXMessage message) throws IOException {
            FIXValue testReqId = message.valueOf(TestReqID);
            if (testReqId == null) {
                sendReject(message.getMsgSeqNum(), 1, "TestReqID(112) not found");

                return;
            }

            string.setLength(0);

            testReqId.asString(string);

            sendHeartbeat(string);
        }

        private void handleResendRequest(FIXMessage message) throws IOException {
            FIXValue beginSeqNo = message.valueOf(BeginSeqNo);
            if (beginSeqNo == null) {
                sendReject(message.getMsgSeqNum(), 1, "BeginSeqNo(7) not found");

                return;
            }

            FIXValue endSeqNo = message.valueOf(EndSeqNo);
            if (endSeqNo == null) {
                sendReject(message.getMsgSeqNum(), 1, "EndSeqNo(16) not found");
                return;
            }

            sendSequenceReset(beginSeqNo.asInt(), endSeqNo.asInt() + 1);
        }

        private void handleReject(FIXMessage message) throws IOException {
            statusListener.reject(FIXConnection.this, message);
        }

        private boolean handleSequenceReset(FIXMessage message) throws IOException {
            FIXValue value = message.valueOf(NewSeqNo);
            if (value == null) {
                sendReject(message.getMsgSeqNum(), 1, "NewSeqNo(36) not found");
                return true;
            }

            long newSeqNo = value.asInt();
            if (newSeqNo < rxMsgSeqNum) {
                sendReject(message.getMsgSeqNum(), 5, "NewSeqNo(36) too low");
                return true;
            }

            rxMsgSeqNum = newSeqNo;

            FIXValue gapFillFlag = message.valueOf(GapFillFlag);
            boolean reset = gapFillFlag == null || gapFillFlag.asChar() != 'Y';

            if (reset)
                statusListener.sequenceReset(FIXConnection.this);

            return reset;
        }

        private void handleLogout(FIXMessage message) throws IOException {
            statusListener.logout(FIXConnection.this, message);
        }

        private void handleLogon(FIXMessage message) throws IOException {
            if (senderCompId.isEmpty()) {
                FIXValue value = message.valueOf(TargetCompID);
                if (value == null) {
                    statusListener.close(FIXConnection.this, "SenderCompID(49) not found");
                    return;
                }

                senderCompId = value.asString();
            }

            if (targetCompId.isEmpty()) {
                FIXValue value = message.valueOf(SenderCompID);
                if (value == null) {
                    statusListener.close(FIXConnection.this, "TargetCompID(56) not found");
                    return;
                }

                targetCompId = value.asString();
            }

            statusListener.logon(FIXConnection.this, message);
        }

    }

    void sendHeartbeat() throws IOException {
        prepare(txMessage, Heartbeat);

        send(txMessage);
    }

    private void sendHeartbeat(CharSequence testReqId) throws IOException {
        prepare(txMessage, Heartbeat);

        txMessage.addField(TestReqID).setString(testReqId);

        send(txMessage);
    }

    private void sendTestRequest(CharSequence testReqId) throws IOException {
        prepare(txMessage, TestRequest);

        txMessage.addField(TestReqID).setString(testReqId);

        send(txMessage);
    }

    private void sendResendRequest(long beginSeqNo) throws IOException {
        prepare(txMessage, ResendRequest);

        txMessage.addField(BeginSeqNo).setInt(beginSeqNo);
        txMessage.addField(EndSeqNo).setInt(0);

        send(txMessage);
    }

    private void sendSequenceReset(long msgSeqNum, long newSeqNo) throws IOException {
        prepare(txMessage, SequenceReset);

        txMessage.valueOf(MsgSeqNum).setInt(msgSeqNum);
        txMessage.addField(GapFillFlag).setChar('Y');
        txMessage.addField(NewSeqNo).setInt(newSeqNo);

        send(txMessage);
    }

}
