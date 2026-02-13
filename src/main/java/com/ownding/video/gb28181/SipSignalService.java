package com.ownding.video.gb28181;

import com.ownding.video.config.AppProperties;
import com.ownding.video.device.DeviceService;
import gov.nist.javax.sip.header.CSeq;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.DialogState;
import javax.sip.InvalidArgumentException;
import javax.sip.ListeningPoint;
import javax.sip.ObjectInUseException;
import javax.sip.PeerUnavailableException;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.SipFactory;
import javax.sip.SipListener;
import javax.sip.SipProvider;
import javax.sip.SipStack;
import javax.sip.TimeoutEvent;
import javax.sip.Transaction;
import javax.sip.TransactionAlreadyExistsException;
import javax.sip.TransactionDoesNotExistException;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.CSeqHeader;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.ExpiresHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.Header;
import javax.sip.header.HeaderFactory;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.ToHeader;
import javax.sip.header.UserAgentHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SipSignalService implements SipListener {

    private static final Logger log = LoggerFactory.getLogger(SipSignalService.class);
    private static final Pattern XML_TAG_PATTERN = Pattern.compile("<%s>([^<]+)</%s>", Pattern.CASE_INSENSITIVE);

    private final AppProperties appProperties;
    private final DeviceService deviceService;

    private final AtomicLong cSeq = new AtomicLong(System.currentTimeMillis() % 100000000L);
    private final Map<String, CompletableFuture<InviteResult>> pendingInviteByCallId = new ConcurrentHashMap<>();
    private final Map<String, Dialog> dialogByCallId = new ConcurrentHashMap<>();

    private volatile SipFactory sipFactory;
    private volatile SipStack sipStack;
    private volatile SipProvider sipProvider;
    private volatile AddressFactory addressFactory;
    private volatile HeaderFactory headerFactory;
    private volatile MessageFactory messageFactory;

    public SipSignalService(AppProperties appProperties, DeviceService deviceService) {
        this.appProperties = appProperties;
        this.deviceService = deviceService;
    }

    @PostConstruct
    public void init() {
        if (!appProperties.getGb28181().isEnabled()) {
            log.info("GB28181 SIP is disabled by config");
            return;
        }
        try {
            this.sipFactory = SipFactory.getInstance();
            this.sipFactory.setPathName("gov.nist");

            Properties properties = new Properties();
            properties.setProperty("javax.sip.STACK_NAME", "video-gb28181-stack");
            properties.setProperty("javax.sip.IP_ADDRESS", appProperties.getGb28181().getLocalIp());
            properties.setProperty("gov.nist.javax.sip.TRACE_LEVEL", "0");
            properties.setProperty("gov.nist.javax.sip.REENTRANT_LISTENER", "true");

            this.sipStack = this.sipFactory.createSipStack(properties);
            this.headerFactory = this.sipFactory.createHeaderFactory();
            this.addressFactory = this.sipFactory.createAddressFactory();
            this.messageFactory = this.sipFactory.createMessageFactory();

            ListeningPoint udpPoint = this.sipStack.createListeningPoint(
                    appProperties.getGb28181().getLocalIp(),
                    appProperties.getGb28181().getLocalPort(),
                    ListeningPoint.UDP
            );
            this.sipProvider = this.sipStack.createSipProvider(udpPoint);
            this.sipProvider.addSipListener(this);
            log.info(
                    "GB28181 SIP started on {}:{}",
                    appProperties.getGb28181().getLocalIp(),
                    appProperties.getGb28181().getLocalPort()
            );
        } catch (Exception ex) {
            log.error("Failed to initialize GB28181 SIP stack: {}", ex.getMessage(), ex);
            destroy();
        }
    }

    @PreDestroy
    public void destroy() {
        pendingInviteByCallId.forEach((callId, future) ->
                future.complete(InviteResult.failed(callId, 500, "SIP服务关闭")));
        pendingInviteByCallId.clear();
        dialogByCallId.clear();

        if (sipProvider != null) {
            try {
                sipProvider.removeSipListener(this);
                if (sipStack != null) {
                    sipStack.deleteSipProvider(sipProvider);
                }
            } catch (ObjectInUseException ignored) {
                // ignore
            }
        }

        if (sipStack != null) {
            try {
                var iterator = sipStack.getListeningPoints();
                while (iterator.hasNext()) {
                    ListeningPoint listeningPoint = (ListeningPoint) iterator.next();
                    sipStack.deleteListeningPoint(listeningPoint);
                }
            } catch (ObjectInUseException ignored) {
                // ignore
            }
            sipStack.stop();
        }

        sipProvider = null;
        sipStack = null;
        addressFactory = null;
        headerFactory = null;
        messageFactory = null;
        sipFactory = null;
    }

    public InviteResult invite(InviteCommand command) {
        if (!appProperties.getGb28181().isEnabled()) {
            return InviteResult.skipped("SIP信令未启用，已跳过INVITE");
        }
        ensureSipReady();

        try {
            InviteBuildResult built = buildInviteRequest(command);
            CompletableFuture<InviteResult> future = new CompletableFuture<>();
            pendingInviteByCallId.put(built.callId(), future);

            ClientTransaction clientTransaction = sipProvider.getNewClientTransaction(built.request());
            clientTransaction.sendRequest();

            return future.get(appProperties.getGb28181().getInviteTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            return InviteResult.failed(null, 408, "INVITE等待超时");
        } catch (Exception ex) {
            return InviteResult.failed(null, 500, "INVITE发送失败: " + ex.getMessage());
        }
    }

    public void bye(String callId) {
        if (callId == null || callId.isBlank()) {
            return;
        }
        if (!appProperties.getGb28181().isEnabled()) {
            return;
        }
        Dialog dialog = dialogByCallId.remove(callId);
        if (dialog == null) {
            return;
        }
        if (dialog.getState() == DialogState.TERMINATED) {
            return;
        }
        try {
            Request byeRequest = dialog.createRequest(Request.BYE);
            ClientTransaction transaction = sipProvider.getNewClientTransaction(byeRequest);
            dialog.sendRequest(transaction);
        } catch (Exception ex) {
            log.warn("Send BYE failed for callId={}, reason={}", callId, ex.getMessage());
        }
    }

    @Override
    public void processRequest(RequestEvent requestEvent) {
        Request request = requestEvent.getRequest();
        String method = request.getMethod();
        try {
            if (Request.REGISTER.equals(method)) {
                handleRegister(requestEvent);
            } else if (Request.MESSAGE.equals(method)) {
                handleMessage(requestEvent);
            } else if (Request.NOTIFY.equals(method) || Request.OPTIONS.equals(method) || Request.SUBSCRIBE.equals(method)) {
                sendResponse(requestEvent, Response.OK);
            } else if (Request.BYE.equals(method)) {
                handleIncomingBye(requestEvent);
            } else if (Request.INVITE.equals(method)) {
                sendResponse(requestEvent, Response.NOT_ACCEPTABLE_HERE);
            } else {
                sendResponse(requestEvent, Response.METHOD_NOT_ALLOWED);
            }
        } catch (Exception ex) {
            log.warn("processRequest failed, method={}, reason={}", method, ex.getMessage());
            try {
                sendResponse(requestEvent, Response.SERVER_INTERNAL_ERROR);
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    @Override
    public void processResponse(ResponseEvent responseEvent) {
        Response response = responseEvent.getResponse();
        CSeqHeader cSeqHeader = (CSeqHeader) response.getHeader(CSeqHeader.NAME);
        CallIdHeader callIdHeader = (CallIdHeader) response.getHeader(CallIdHeader.NAME);
        if (cSeqHeader == null || callIdHeader == null) {
            return;
        }

        String method = cSeqHeader.getMethod();
        String callId = callIdHeader.getCallId();
        int statusCode = response.getStatusCode();

        if (Request.INVITE.equals(method)) {
            CompletableFuture<InviteResult> future = pendingInviteByCallId.get(callId);
            if (future == null) {
                return;
            }

            if (statusCode >= 100 && statusCode < 200) {
                return;
            }

            if (statusCode >= 200 && statusCode < 300) {
                Dialog dialog = responseEvent.getDialog();
                if (dialog == null && responseEvent.getClientTransaction() != null) {
                    dialog = responseEvent.getClientTransaction().getDialog();
                }
                if (dialog != null) {
                    try {
                        Request ack = dialog.createAck(cSeqHeader.getSeqNumber());
                        dialog.sendAck(ack);
                        dialogByCallId.put(callId, dialog);
                    } catch (Exception ex) {
                        log.warn("Send ACK failed, callId={}, reason={}", callId, ex.getMessage());
                    }
                }
                future.complete(InviteResult.success(callId, statusCode, response.getReasonPhrase()));
                pendingInviteByCallId.remove(callId);
                return;
            }

            future.complete(InviteResult.failed(callId, statusCode, response.getReasonPhrase()));
            pendingInviteByCallId.remove(callId);
            return;
        }

        if (Request.BYE.equals(method) && statusCode >= 200 && statusCode < 300) {
            dialogByCallId.remove(callId);
        }
    }

    @Override
    public void processTimeout(TimeoutEvent timeoutEvent) {
        Transaction transaction = timeoutEvent.isServerTransaction()
                ? timeoutEvent.getServerTransaction()
                : timeoutEvent.getClientTransaction();
        if (transaction == null) {
            return;
        }
        CallIdHeader callIdHeader = (CallIdHeader) transaction.getRequest().getHeader(CallIdHeader.NAME);
        if (callIdHeader == null) {
            return;
        }
        String callId = callIdHeader.getCallId();
        CompletableFuture<InviteResult> future = pendingInviteByCallId.remove(callId);
        if (future != null) {
            future.complete(InviteResult.failed(callId, 408, "SIP事务超时"));
        }
    }

    @Override
    public void processIOException(javax.sip.IOExceptionEvent exceptionEvent) {
        log.warn("SIP IOException: {}", exceptionEvent);
    }

    @Override
    public void processTransactionTerminated(TransactionTerminatedEvent transactionTerminatedEvent) {
        // no-op
    }

    @Override
    public void processDialogTerminated(javax.sip.DialogTerminatedEvent dialogTerminatedEvent) {
        Dialog dialog = dialogTerminatedEvent.getDialog();
        if (dialog != null && dialog.getCallId() != null) {
            dialogByCallId.remove(dialog.getCallId().getCallId());
        }
    }

    private void handleRegister(RequestEvent requestEvent) throws SipException, InvalidArgumentException, ParseException {
        Request request = requestEvent.getRequest();
        String deviceId = extractDeviceIdFromRequest(request).orElse(null);
        boolean online = true;
        ExpiresHeader expiresHeader = (ExpiresHeader) request.getHeader(ExpiresHeader.NAME);
        if (expiresHeader != null && expiresHeader.getExpires() == 0) {
            online = false;
        }

        if (deviceId != null) {
            boolean updated = deviceService.updateDeviceOnlineStatusByCode(deviceId, online);
            if (!updated) {
                log.debug("REGISTER from unknown deviceId={}", deviceId);
            }
        }
        sendResponse(requestEvent, Response.OK);
    }

    private void handleMessage(RequestEvent requestEvent) throws SipException, InvalidArgumentException, ParseException {
        Request request = requestEvent.getRequest();
        String body = getRequestBody(request);
        String cmdType = extractXmlTag(body, "CmdType").orElse(null);
        String deviceId = extractXmlTag(body, "DeviceID")
                .or(() -> extractDeviceIdFromRequest(request))
                .orElse(null);

        if (deviceId != null && "Keepalive".equalsIgnoreCase(cmdType)) {
            boolean updated = deviceService.updateDeviceOnlineStatusByCode(deviceId, true);
            if (!updated) {
                log.debug("Keepalive from unknown deviceId={}", deviceId);
            }
        }
        sendResponse(requestEvent, Response.OK);
    }

    private void handleIncomingBye(RequestEvent requestEvent) throws SipException, InvalidArgumentException, ParseException {
        Request request = requestEvent.getRequest();
        CallIdHeader callIdHeader = (CallIdHeader) request.getHeader(CallIdHeader.NAME);
        if (callIdHeader != null) {
            dialogByCallId.remove(callIdHeader.getCallId());
        }
        sendResponse(requestEvent, Response.OK);
    }

    private void sendResponse(RequestEvent requestEvent, int statusCode)
            throws ParseException, InvalidArgumentException, SipException {
        Request request = requestEvent.getRequest();
        Response response = messageFactory.createResponse(statusCode, request);
        UserAgentHeader userAgentHeader = headerFactory.createUserAgentHeader(List.of(appProperties.getGb28181().getUserAgent()));
        response.setHeader(userAgentHeader);

        ServerTransaction serverTransaction = requestEvent.getServerTransaction();
        if (serverTransaction == null) {
            serverTransaction = sipProvider.getNewServerTransaction(request);
        }
        serverTransaction.sendResponse(response);
    }

    private InviteBuildResult buildInviteRequest(InviteCommand command)
            throws ParseException, InvalidArgumentException, PeerUnavailableException {
        String transport = command.transport().toUpperCase();
        String viaTransport = "TCP".equals(transport) ? ListeningPoint.TCP : ListeningPoint.UDP;

        SipURI requestUri = addressFactory.createSipURI(command.deviceId(), command.deviceHost());
        requestUri.setPort(command.devicePort());
        requestUri.setTransportParam(viaTransport.toLowerCase());

        SipURI fromUri = addressFactory.createSipURI(appProperties.getGb28181().getServerId(), appProperties.getGb28181().getDomain());
        Address fromAddress = addressFactory.createAddress(fromUri);
        FromHeader fromHeader = headerFactory.createFromHeader(fromAddress, randomTag());

        SipURI toUri = addressFactory.createSipURI(command.deviceId(), appProperties.getGb28181().getDomain());
        Address toAddress = addressFactory.createAddress(toUri);
        ToHeader toHeader = headerFactory.createToHeader(toAddress, null);

        List<ViaHeader> viaHeaders = new ArrayList<>(1);
        ViaHeader viaHeader = headerFactory.createViaHeader(
                resolveAnnounceIp(),
                appProperties.getGb28181().getLocalPort(),
                viaTransport,
                null
        );
        viaHeaders.add(viaHeader);

        CallIdHeader callIdHeader = sipProvider.getNewCallId();
        CSeqHeader cSeqHeader = headerFactory.createCSeqHeader(nextCSeq(), Request.INVITE);
        MaxForwardsHeader maxForwardsHeader = headerFactory.createMaxForwardsHeader(70);

        Request request = messageFactory.createRequest(
                requestUri,
                Request.INVITE,
                callIdHeader,
                cSeqHeader,
                fromHeader,
                toHeader,
                viaHeaders,
                maxForwardsHeader
        );

        SipURI contactUri = addressFactory.createSipURI(appProperties.getGb28181().getServerId(), resolveAnnounceIp());
        contactUri.setPort(appProperties.getGb28181().getLocalPort());
        Address contactAddress = addressFactory.createAddress(contactUri);
        ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);
        request.addHeader(contactHeader);
        request.addHeader(headerFactory.createUserAgentHeader(List.of(appProperties.getGb28181().getUserAgent())));
        request.addHeader(headerFactory.createHeader("Subject", command.channelId() + ":" + command.ssrc() + "," + appProperties.getGb28181().getServerId() + ":0"));

        String sdp = buildInviteSdp(command);
        ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("APPLICATION", "SDP");
        request.setContent(sdp, contentTypeHeader);
        return new InviteBuildResult(request, callIdHeader.getCallId());
    }

    private String buildInviteSdp(InviteCommand command) {
        boolean tcpMode = "TCP".equalsIgnoreCase(command.transport());
        String mediaIp = appProperties.getGb28181().getMediaIp();
        StringBuilder builder = new StringBuilder(256);
        builder.append("v=0").append("\r\n");
        builder.append("o=").append(appProperties.getGb28181().getServerId()).append(" 0 0 IN IP4 ").append(mediaIp).append("\r\n");
        builder.append("s=Play").append("\r\n");
        builder.append("c=IN IP4 ").append(mediaIp).append("\r\n");
        builder.append("t=0 0").append("\r\n");
        builder.append("m=video ").append(command.rtpPort()).append(" ")
                .append(tcpMode ? "TCP/RTP/AVP" : "RTP/AVP")
                .append(" 96").append("\r\n");
        builder.append("a=recvonly").append("\r\n");
        builder.append("a=rtpmap:96 PS/90000").append("\r\n");
        if (tcpMode) {
            builder.append("a=setup:passive").append("\r\n");
            builder.append("a=connection:new").append("\r\n");
        }
        builder.append("y=").append(command.ssrc()).append("\r\n");
        return builder.toString();
    }

    private Optional<String> extractDeviceIdFromRequest(Request request) {
        ToHeader toHeader = (ToHeader) request.getHeader(ToHeader.NAME);
        String fromTo = extractUserPart(toHeader);
        if (fromTo != null) {
            return Optional.of(fromTo);
        }
        FromHeader fromHeader = (FromHeader) request.getHeader(FromHeader.NAME);
        String from = extractUserPart(fromHeader);
        return Optional.ofNullable(from);
    }

    private String extractUserPart(Header header) {
        if (header instanceof ToHeader toHeader) {
            return extractUserFromAddress(toHeader.getAddress());
        }
        if (header instanceof FromHeader fromHeader) {
            return extractUserFromAddress(fromHeader.getAddress());
        }
        return null;
    }

    private String extractUserFromAddress(Address address) {
        if (address == null) {
            return null;
        }
        if (address.getURI() instanceof SipURI sipUri) {
            return sipUri.getUser();
        }
        return null;
    }

    private Optional<String> extractXmlTag(String xml, String tag) {
        if (xml == null || xml.isBlank()) {
            return Optional.empty();
        }
        String regex = XML_TAG_PATTERN.pattern().formatted(tag, tag);
        Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(xml);
        if (matcher.find()) {
            return Optional.ofNullable(matcher.group(1)).map(String::trim).filter(s -> !s.isBlank());
        }
        return Optional.empty();
    }

    private String getRequestBody(Request request) {
        Object content = request.getContent();
        if (content == null) {
            return "";
        }
        if (content instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(content);
    }

    private String resolveAnnounceIp() {
        String localIp = appProperties.getGb28181().getLocalIp();
        if (localIp == null || localIp.isBlank() || "0.0.0.0".equals(localIp)) {
            return appProperties.getGb28181().getMediaIp();
        }
        return localIp;
    }

    private long nextCSeq() {
        return cSeq.incrementAndGet();
    }

    private String randomTag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private void ensureSipReady() {
        if (sipProvider == null || sipStack == null || headerFactory == null || addressFactory == null || messageFactory == null) {
            throw new IllegalStateException("SIP服务尚未初始化");
        }
    }

    public String generateSsrc() {
        long value = Math.abs(UUID.randomUUID().getMostSignificantBits()) % 1_000_000_000L;
        return appProperties.getGb28181().getSsrcPrefix() + String.format("%09d", value);
    }

    public record InviteCommand(
            String deviceId,
            String deviceHost,
            int devicePort,
            String channelId,
            String transport,
            int rtpPort,
            String ssrc
    ) {
    }

    public record InviteResult(
            boolean success,
            String callId,
            int statusCode,
            String reason
    ) {
        public static InviteResult success(String callId, int statusCode, String reason) {
            return new InviteResult(true, callId, statusCode, reason);
        }

        public static InviteResult failed(String callId, int statusCode, String reason) {
            return new InviteResult(false, callId, statusCode, reason);
        }

        public static InviteResult skipped(String reason) {
            return new InviteResult(true, null, 0, reason);
        }
    }

    private record InviteBuildResult(
            Request request,
            String callId
    ) {
    }
}
