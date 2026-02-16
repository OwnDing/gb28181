package com.ownding.video.gb28181;

import com.ownding.video.config.AppProperties;
import com.ownding.video.device.Device;
import com.ownding.video.device.DeviceService;
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
import javax.sip.TransactionTerminatedEvent;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.CSeqHeader;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.EventHeader;
import javax.sip.header.ExpiresHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.Header;
import javax.sip.header.HeaderFactory;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.RouteHeader;
import javax.sip.header.ToHeader;
import javax.sip.header.UserAgentHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SipSignalService implements SipListener {

    private static final Logger log = LoggerFactory.getLogger(SipSignalService.class);
    private static final Pattern ITEM_BLOCK_PATTERN = Pattern.compile("<Item>([\\s\\S]*?)</Item>",
            Pattern.CASE_INSENSITIVE);

    private final AppProperties appProperties;
    private final DeviceService deviceService;
    private final Gb28181Repository gb28181Repository;

    private final AtomicLong cSeq = new AtomicLong(System.currentTimeMillis() % 100000000L);
    private final AtomicInteger ssrcSeq = new AtomicInteger();
    private final ConcurrentHashMap<String, CompletableFuture<InviteResult>> pendingInviteByCallId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<SipCommandResult>> pendingCommandByCallId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Dialog> dialogByCallId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DeviceEndpoint> endpointByCallId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> contactHostByDeviceId = new ConcurrentHashMap<>();

    private volatile SipFactory sipFactory;
    private volatile SipStack sipStack;
    private volatile SipProvider sipProvider;
    private volatile AddressFactory addressFactory;
    private volatile HeaderFactory headerFactory;
    private volatile MessageFactory messageFactory;

    public SipSignalService(AppProperties appProperties, DeviceService deviceService,
            Gb28181Repository gb28181Repository) {
        this.appProperties = appProperties;
        this.deviceService = deviceService;
        this.gb28181Repository = gb28181Repository;
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
                    appProperties.getGb28181().getLocalBindIp(),
                    appProperties.getGb28181().getLocalPort(),
                    ListeningPoint.UDP);
            this.sipProvider = this.sipStack.createSipProvider(udpPoint);
            try {
                ListeningPoint tcpPoint = this.sipStack.createListeningPoint(
                        appProperties.getGb28181().getLocalBindIp(),
                        appProperties.getGb28181().getLocalPort(),
                        ListeningPoint.TCP);
                this.sipProvider.addListeningPoint(tcpPoint);
            } catch (Exception ex) {
                log.warn("GB28181 SIP TCP listening point init failed, fallback to UDP only: {}", ex.getMessage());
            }
            this.sipProvider.addSipListener(this);
            log.info(
                    "GB28181 SIP started on {}:{}, transports={}",
                    appProperties.getGb28181().getLocalIp(),
                    appProperties.getGb28181().getLocalPort(),
                    String.join(",", availableSipTransports()));
        } catch (Exception ex) {
            log.error("Failed to initialize GB28181 SIP stack: {}", ex.getMessage(), ex);
            destroy();
        }
    }

    @PreDestroy
    public void destroy() {
        pendingInviteByCallId.forEach((callId, future) -> future.complete(InviteResult.failed(callId, 500, "SIP服务关闭")));
        pendingInviteByCallId.clear();

        pendingCommandByCallId
                .forEach((callId, future) -> future.complete(SipCommandResult.failed(callId, 500, "SIP服务关闭")));
        pendingCommandByCallId.clear();
        dialogByCallId.clear();
        endpointByCallId.clear();
        contactHostByDeviceId.clear();

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

        String sdpProtocol = command.streamMode() == 0 ? "RTP/AVP" : "TCP/RTP/AVP";
        log.info(
                "send INVITE: deviceId={}, deviceHost={}, devicePort={}, channelId={}, sipTransport={}, streamMode={}, sdpProtocol={}, mediaIp={}, rtpPort={}, ssrc={}, streamId={}",
                command.deviceId(),
                command.deviceHost(),
                command.devicePort(),
                command.channelId(),
                command.sipTransport(),
                command.streamMode(),
                sdpProtocol,
                resolveInviteMediaIp(command.announcedMediaIp()),
                command.rtpPort(),
                command.ssrc(),
                command.streamId());

        String callId = null;
        try {
            InviteBuildResult built = buildInviteRequest(command);
            callId = built.callId();
            if (callId == null || callId.isBlank()) {
                return InviteResult.failed(null, 500, "INVITE创建失败: callId为空");
            }
            CompletableFuture<InviteResult> future = new CompletableFuture<>();
            pendingInviteByCallId.put(callId, future);
            endpointByCallId.put(callId, inferEndpointFromRequest(built.request(), command));

            ClientTransaction clientTransaction = sipProvider.getNewClientTransaction(built.request());
            clientTransaction.sendRequest();

            return future.get(appProperties.getGb28181().getInviteTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            if (callId != null) {
                pendingInviteByCallId.remove(callId);
                endpointByCallId.remove(callId);
            }
            return InviteResult.failed(callId, 408, "INVITE等待超时");
        } catch (Exception ex) {
            if (callId != null) {
                pendingInviteByCallId.remove(callId);
                endpointByCallId.remove(callId);
            }
            return InviteResult.failed(callId, 500, "INVITE发送失败: " + ex.getMessage());
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
        DeviceEndpoint endpoint = endpointByCallId.remove(callId);
        if (dialog == null) {
            return;
        }
        if (dialog.getState() == DialogState.TERMINATED) {
            return;
        }
        try {
            Request byeRequest = dialog.createRequest(Request.BYE);
            overrideDialogRequestUriIfNeeded(callId, byeRequest, endpoint);
            ClientTransaction transaction = sipProvider.getNewClientTransaction(byeRequest);
            try {
                dialog.sendRequest(transaction);
            } catch (Exception ex) {
                log.warn("dialog send BYE failed, fallback to stateless. callId={}, reason={}", callId,
                        ex.getMessage());
                sipProvider.sendRequest(byeRequest);
            }
        } catch (Exception ex) {
            log.warn("Send BYE failed for callId={}, reason={}", callId, ex.getMessage());
        }
    }

    public SipCommandResult sendMessage(String deviceId, String xml) {
        if (!appProperties.getGb28181().isEnabled()) {
            return SipCommandResult.skipped("SIP信令未启用，已跳过MESSAGE");
        }
        ensureSipReady();
        try {
            TargetDevice target = resolveTargetDevice(deviceId);
            Request request = createBaseRequest(Request.MESSAGE, target);
            ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("Application", "MANSCDP+xml");
            request.setContent(xml == null ? "" : xml, contentTypeHeader);
            return sendCommandAndWait(request);
        } catch (Exception ex) {
            return SipCommandResult.failed(null, 500, "MESSAGE发送失败: " + ex.getMessage());
        }
    }

    public SipCommandResult sendSubscribe(String deviceId, String eventType, int expires, String xml) {
        if (!appProperties.getGb28181().isEnabled()) {
            return SipCommandResult.skipped("SIP信令未启用，已跳过SUBSCRIBE");
        }
        ensureSipReady();
        try {
            TargetDevice target = resolveTargetDevice(deviceId);
            Request request = createBaseRequest(Request.SUBSCRIBE, target);
            ExpiresHeader expiresHeader = headerFactory.createExpiresHeader(Math.max(0, expires));
            request.setExpires(expiresHeader);
            EventHeader eventHeader = headerFactory.createEventHeader(eventType);
            request.addHeader(eventHeader);

            ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("Application", "MANSCDP+xml");
            request.setContent(xml == null ? "" : xml, contentTypeHeader);
            return sendCommandAndWait(request);
        } catch (Exception ex) {
            return SipCommandResult.failed(null, 500, "SUBSCRIBE发送失败: " + ex.getMessage());
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
            } else if (Request.NOTIFY.equals(method) || Request.OPTIONS.equals(method)
                    || Request.SUBSCRIBE.equals(method)) {
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
                boolean ackSent = false;
                if (dialog != null) {
                    ackSent = sendAckForInvite(callId, responseEvent, dialog, cSeqHeader.getSeqNumber());
                    if (ackSent) {
                        dialogByCallId.put(callId, dialog);
                    }
                }
                if (!ackSent) {
                    endpointByCallId.remove(callId);
                    future.complete(InviteResult.failed(callId, 500, "INVITE成功但ACK发送失败"));
                    pendingInviteByCallId.remove(callId);
                    return;
                }

                future.complete(InviteResult.success(callId, statusCode, response.getReasonPhrase()));
                pendingInviteByCallId.remove(callId);
                return;
            }

            endpointByCallId.remove(callId);
            future.complete(InviteResult.failed(callId, statusCode, response.getReasonPhrase()));
            pendingInviteByCallId.remove(callId);
            return;
        }

        CompletableFuture<SipCommandResult> commandFuture = pendingCommandByCallId.get(callId);
        if (commandFuture != null) {
            if (statusCode >= 100 && statusCode < 200) {
                return;
            }
            if (statusCode >= 200 && statusCode < 300) {
                commandFuture.complete(SipCommandResult.success(callId, statusCode, response.getReasonPhrase()));
            } else {
                commandFuture.complete(SipCommandResult.failed(callId, statusCode, response.getReasonPhrase()));
            }
            pendingCommandByCallId.remove(callId);
            return;
        }

        if (Request.BYE.equals(method) && statusCode >= 200 && statusCode < 300) {
            dialogByCallId.remove(callId);
            endpointByCallId.remove(callId);
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
        CompletableFuture<InviteResult> inviteFuture = pendingInviteByCallId.remove(callId);
        if (inviteFuture != null) {
            inviteFuture.complete(InviteResult.failed(callId, 408, "SIP事务超时"));
            endpointByCallId.remove(callId);
            return;
        }
        CompletableFuture<SipCommandResult> commandFuture = pendingCommandByCallId.remove(callId);
        if (commandFuture != null) {
            commandFuture.complete(SipCommandResult.failed(callId, 408, "SIP事务超时"));
        }
    }

    private boolean sendAckForInvite(String callId, ResponseEvent responseEvent, Dialog dialog, long inviteCSeq) {
        Request ack;
        try {
            ack = dialog.createAck(inviteCSeq);
        } catch (Exception ex) {
            log.warn("Create ACK failed, callId={}, reason={}", callId, ex.getMessage());
            return false;
        }

        overrideDialogRequestUriIfNeeded(callId, ack, endpointByCallId.get(callId), responseEvent);

        Header routeHeader = ack.getHeader(RouteHeader.NAME);
        log.info("send ACK: callId={}, uri={}, route={}", callId, ack.getRequestURI(), routeHeader);

        try {
            dialog.sendAck(ack);
            return true;
        } catch (Exception ex) {
            log.warn("Send ACK failed, callId={}, reason={}, fallback=stateless", callId, ex.getMessage());
            try {
                sipProvider.sendRequest(ack);
                return true;
            } catch (Exception fallbackEx) {
                log.warn("Send ACK fallback failed, callId={}, reason={}", callId, fallbackEx.getMessage());
                return false;
            }
        }
    }

    private DeviceEndpoint inferEndpointFromRequest(Request request, InviteCommand command) {
        if (request == null) {
            return new DeviceEndpoint(command.deviceHost(), command.devicePort(),
                    normalizeDeviceTransport(command.sipTransport()));
        }

        RouteHeader routeHeader = (RouteHeader) request.getHeader(RouteHeader.NAME);
        if (routeHeader != null && routeHeader.getAddress() != null
                && routeHeader.getAddress().getURI() instanceof SipURI routeUri) {
            String host = routeUri.getHost();
            int port = routeUri.getPort() > 0 ? routeUri.getPort() : command.devicePort();
            String transport = firstNonBlank(routeUri.getTransportParam(), command.sipTransport());
            if (host != null && !host.isBlank()) {
                return new DeviceEndpoint(host, port, normalizeDeviceTransport(transport));
            }
        }

        if (request.getRequestURI() instanceof SipURI uri) {
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : command.devicePort();
            String transport = firstNonBlank(uri.getTransportParam(), command.sipTransport());
            if (host != null && !host.isBlank()) {
                return new DeviceEndpoint(host, port, normalizeDeviceTransport(transport));
            }
        }
        return new DeviceEndpoint(command.deviceHost(), command.devicePort(),
                normalizeDeviceTransport(command.sipTransport()));
    }

    private void overrideDialogRequestUriIfNeeded(String callId, Request request, DeviceEndpoint endpoint) {
        overrideDialogRequestUriIfNeeded(callId, request, endpoint, null);
    }

    private void overrideDialogRequestUriIfNeeded(String callId, Request request, DeviceEndpoint endpoint,
            ResponseEvent responseEvent) {
        if (request == null) {
            return;
        }
        if (!(request.getRequestURI() instanceof SipURI currentUri)) {
            return;
        }
        if (!shouldOverrideSipHost(currentUri.getHost())) {
            return;
        }

        SipURI fallbackUri = extractRequestUriFromTransaction(responseEvent);
        if (fallbackUri != null && !shouldOverrideSipHost(fallbackUri.getHost())) {
            request.setRequestURI(fallbackUri);
            log.debug("override dialog request-uri by transaction uri. callId={}, method={}, uri={}", callId,
                    request.getMethod(), fallbackUri);
            return;
        }

        if (endpoint == null || endpoint.host() == null || endpoint.host().isBlank()) {
            return;
        }
        try {
            SipURI uri = addressFactory.createSipURI(currentUri.getUser(), endpoint.host());
            if (endpoint.port() > 0) {
                uri.setPort(endpoint.port());
            }
            if (endpoint.transport() != null && !endpoint.transport().isBlank()) {
                uri.setTransportParam(endpoint.transport().toLowerCase());
            }
            request.setRequestURI(uri);
            log.debug("override dialog request-uri by endpoint. callId={}, method={}, uri={}", callId,
                    request.getMethod(), uri);
        } catch (Exception ex) {
            log.debug("override dialog request-uri failed. callId={}, reason={}", callId, ex.getMessage());
        }
    }

    private SipURI extractRequestUriFromTransaction(ResponseEvent responseEvent) {
        if (responseEvent == null) {
            return null;
        }
        ClientTransaction transaction = responseEvent.getClientTransaction();
        if (transaction == null) {
            return null;
        }
        Request request = transaction.getRequest();
        if (request == null) {
            return null;
        }
        if (!(request.getRequestURI() instanceof SipURI uri)) {
            return null;
        }
        try {
            return (SipURI) uri.clone();
        } catch (Exception ex) {
            return uri;
        }
    }

    private boolean shouldOverrideSipHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String trimmed = host.trim();
        String domain = appProperties.getGb28181().getDomain();
        if (domain != null && !domain.isBlank() && trimmed.equalsIgnoreCase(domain.trim())) {
            return true;
        }
        return trimmed.matches("\\d{10,}");
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

    private void handleRegister(RequestEvent requestEvent)
            throws SipException, InvalidArgumentException, ParseException {
        Request request = requestEvent.getRequest();
        String deviceId = extractDeviceIdFromRequest(request).orElse(null);
        boolean online = true;
        ExpiresHeader expiresHeader = (ExpiresHeader) request.getHeader(ExpiresHeader.NAME);
        if (expiresHeader != null && expiresHeader.getExpires() == 0) {
            online = false;
        }

        // Respond first to avoid device-side REGISTER timeout caused by local DB
        // processing latency.
        sendResponse(requestEvent, Response.OK);
        if (deviceId != null) {
            updateContactHostIfPresent(deviceId, request);
            updateDeviceOnlineState(deviceId, online, request, "REGISTER");
        }
    }

    private void handleMessage(RequestEvent requestEvent)
            throws SipException, InvalidArgumentException, ParseException {
        Request request = requestEvent.getRequest();
        String body = getRequestBody(request);
        String cmdType = extractXmlTag(body, "CmdType").orElse(null);
        String fromDeviceId = extractDeviceIdFromRequest(request).orElse(null);
        String xmlDeviceId = extractXmlTag(body, "DeviceID").orElse(null);
        String deviceId = fromDeviceId == null || fromDeviceId.isBlank() ? xmlDeviceId : fromDeviceId;

        if (deviceId != null) {
            updateContactHostIfPresent(deviceId, request);
        }
        if (deviceId != null && cmdType != null) {
            if ("Keepalive".equalsIgnoreCase(cmdType)) {
                updateDeviceOnlineState(deviceId, true, request, "Keepalive");
            } else if ("DeviceInfo".equalsIgnoreCase(cmdType)) {
                persistDeviceInfo(deviceId, body);
            } else if ("Catalog".equalsIgnoreCase(cmdType)) {
                persistCatalog(deviceId, body);
            } else if ("RecordInfo".equalsIgnoreCase(cmdType)) {
                persistRecordInfo(deviceId, body);
            } else if ("Alarm".equalsIgnoreCase(cmdType)) {
                persistAlarm(deviceId, body);
            } else if ("MobilePosition".equalsIgnoreCase(cmdType)) {
                persistMobilePosition(deviceId, body);
            }
        }
        sendResponse(requestEvent, Response.OK);
    }

    public Optional<String> getLastContactHost(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(contactHostByDeviceId.get(deviceId));
    }

    private void updateContactHostIfPresent(String deviceId, Request request) {
        if (deviceId == null || deviceId.isBlank() || request == null) {
            return;
        }
        ContactHeader contactHeader = (ContactHeader) request.getHeader(ContactHeader.NAME);
        if (contactHeader == null || contactHeader.getAddress() == null) {
            return;
        }
        if (!(contactHeader.getAddress().getURI() instanceof SipURI contactUri)) {
            return;
        }
        String host = contactUri.getHost();
        if (host == null || host.isBlank()) {
            return;
        }
        contactHostByDeviceId.put(deviceId, host.trim());
    }

    private void updateDeviceOnlineState(String deviceId, boolean online, Request request, String source) {
        boolean updated = deviceService.updateDeviceOnlineStatusByCode(deviceId, online);
        if (!updated && online) {
            updated = tryAutoRegisterDevice(deviceId, request);
        }
        if (!updated) {
            log.debug("{} from unknown deviceId={}", source, deviceId);
        }
    }

    private boolean tryAutoRegisterDevice(String deviceId, Request request) {
        if (!appProperties.getGb28181().isAutoRegisterUnknownDevice()) {
            return false;
        }
        if (deviceId == null || !deviceId.matches("\\d{20}")) {
            return false;
        }
        try {
            if (deviceService.findDeviceByCode(deviceId).isPresent()) {
                return deviceService.updateDeviceOnlineStatusByCode(deviceId, true);
            }

            DeviceEndpoint endpoint = extractDeviceEndpoint(request);
            String displayName = "自动接入-" + deviceId.substring(Math.max(0, deviceId.length() - 6));
            deviceService.createDevice(new DeviceService.CreateDeviceCommand(
                    displayName,
                    deviceId,
                    endpoint.host(),
                    endpoint.port(),
                    endpoint.transport(),
                    null,
                    null,
                    "AUTO",
                    1,
                    "H264"));
            boolean updated = deviceService.updateDeviceOnlineStatusByCode(deviceId, true);
            log.info(
                    "auto-registered GB28181 device deviceId={}, host={}, port={}, transport={}",
                    deviceId,
                    endpoint.host(),
                    endpoint.port(),
                    endpoint.transport());
            return updated;
        } catch (Exception ex) {
            log.warn("auto register device failed, deviceId={}, reason={}", deviceId, ex.getMessage());
            return false;
        }
    }

    private DeviceEndpoint extractDeviceEndpoint(Request request) {
        String host = null;
        int port = -1;
        String transport = "UDP";

        ViaHeader viaHeader = (ViaHeader) request.getHeader(ViaHeader.NAME);
        if (viaHeader != null) {
            String viaTransport = viaHeader.getTransport();
            if (viaTransport != null && !viaTransport.isBlank()) {
                transport = normalizeDeviceTransport(viaTransport);
            }
            host = firstNonBlank(viaHeader.getReceived(), viaHeader.getHost());
            int rPort = viaHeader.getRPort();
            if (rPort > 0) {
                port = rPort;
            } else if (viaHeader.getPort() > 0) {
                port = viaHeader.getPort();
            }
        }

        ContactHeader contactHeader = (ContactHeader) request.getHeader(ContactHeader.NAME);
        if (contactHeader != null
                && contactHeader.getAddress() != null
                && contactHeader.getAddress().getURI() instanceof SipURI contactUri) {
            if (host == null || host.isBlank()) {
                host = contactUri.getHost();
            }
            if (port <= 0 && contactUri.getPort() > 0) {
                port = contactUri.getPort();
            }
            String contactTransport = contactUri.getTransportParam();
            if ((transport == null || transport.isBlank()) && contactTransport != null && !contactTransport.isBlank()) {
                transport = normalizeDeviceTransport(contactTransport);
            }
        }

        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        if (port <= 0) {
            port = 5060;
        }
        return new DeviceEndpoint(host, port, normalizeDeviceTransport(transport));
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    private void persistDeviceInfo(String deviceId, String xml) {
        gb28181Repository.upsertDeviceProfile(new Gb28181Repository.UpsertDeviceProfileCommand(
                deviceId,
                extractXmlTag(xml, "DeviceName").or(() -> extractXmlTag(xml, "Name")).orElse(null),
                extractXmlTag(xml, "Manufacturer").orElse(null),
                extractXmlTag(xml, "Model").orElse(null),
                extractXmlTag(xml, "Firmware").orElse(null),
                extractXmlTag(xml, "Result").or(() -> extractXmlTag(xml, "Status")).orElse(null),
                xml));
    }

    private void persistCatalog(String deviceId, String xml) {
        List<Gb28181Repository.UpsertCatalogItemCommand> items = new ArrayList<>();
        Matcher matcher = ITEM_BLOCK_PATTERN.matcher(xml);
        while (matcher.find()) {
            String block = matcher.group(1);
            String channelId = extractXmlTag(block, "DeviceID").orElse(null);
            if (channelId == null || channelId.isBlank()) {
                continue;
            }
            items.add(new Gb28181Repository.UpsertCatalogItemCommand(
                    channelId,
                    extractXmlTag(block, "Name").orElse(channelId),
                    inferCodec(block),
                    normalizeChannelStatus(extractXmlTag(block, "Status").orElse("OFFLINE"))));
        }
        if (!items.isEmpty()) {
            gb28181Repository.syncCatalog(deviceId, items);
        }
    }

    private void persistRecordInfo(String deviceId, String xml) {
        String rootDeviceId = extractXmlTag(xml, "DeviceID").orElse(null);
        String defaultChannelId = normalizeChannelId(deviceId, rootDeviceId);
        List<Gb28181Repository.UpsertRecordItemCommand> items = new ArrayList<>();
        Matcher matcher = ITEM_BLOCK_PATTERN.matcher(xml);
        while (matcher.find()) {
            String block = matcher.group(1);
            String itemChannelId = normalizeChannelId(
                    deviceId,
                    extractXmlTag(block, "DeviceID").orElse(defaultChannelId));
            items.add(new Gb28181Repository.UpsertRecordItemCommand(
                    itemChannelId,
                    extractXmlTag(block, "RecordID").orElse(null),
                    extractXmlTag(block, "Name").orElse(null),
                    extractXmlTag(block, "Address").orElse(null),
                    extractXmlTag(block, "StartTime").orElse(null),
                    extractXmlTag(block, "EndTime").orElse(null),
                    extractXmlTag(block, "Secrecy").orElse(null),
                    extractXmlTag(block, "Type").orElse(null),
                    extractXmlTag(block, "RecorderID").orElse(null),
                    extractXmlTag(block, "FilePath").or(() -> extractXmlTag(block, "FileName")).orElse(null),
                    block));
        }
        if (!items.isEmpty()) {
            gb28181Repository.replaceRecordItems(deviceId, defaultChannelId, items);
        }
    }

    private void persistAlarm(String deviceId, String xml) {
        String alarmDeviceId = extractXmlTag(xml, "DeviceID").orElse(null);
        String channelId = normalizeChannelId(deviceId, alarmDeviceId);
        gb28181Repository.insertAlarm(new Gb28181Repository.UpsertAlarmEventCommand(
                deviceId,
                channelId,
                extractXmlTag(xml, "AlarmMethod").orElse(null),
                extractXmlTag(xml, "AlarmType").orElse(null),
                extractXmlTag(xml, "AlarmPriority").orElse(null),
                extractXmlTag(xml, "AlarmTime").orElse(null),
                extractXmlTag(xml, "Longitude").orElse(null),
                extractXmlTag(xml, "Latitude").orElse(null),
                extractXmlTag(xml, "AlarmDescription").or(() -> extractXmlTag(xml, "Description")).orElse(null),
                xml,
                null,
                null));
    }

    private void persistMobilePosition(String deviceId, String xml) {
        String mobileDeviceId = extractXmlTag(xml, "DeviceID").orElse(null);
        String channelId = normalizeChannelId(deviceId, mobileDeviceId);
        gb28181Repository.insertMobilePosition(new Gb28181Repository.UpsertMobilePositionCommand(
                deviceId,
                channelId,
                extractXmlTag(xml, "Time").orElse(null),
                extractXmlTag(xml, "Longitude").orElse(null),
                extractXmlTag(xml, "Latitude").orElse(null),
                extractXmlTag(xml, "Speed").orElse(null),
                extractXmlTag(xml, "Direction").orElse(null),
                extractXmlTag(xml, "Altitude").orElse(null),
                xml));
    }

    private void handleIncomingBye(RequestEvent requestEvent)
            throws SipException, InvalidArgumentException, ParseException {
        Request request = requestEvent.getRequest();
        CallIdHeader callIdHeader = (CallIdHeader) request.getHeader(CallIdHeader.NAME);
        if (callIdHeader != null) {
            String callId = callIdHeader.getCallId();
            dialogByCallId.remove(callId);
            endpointByCallId.remove(callId);
        }
        sendResponse(requestEvent, Response.OK);
    }

    private void sendResponse(RequestEvent requestEvent, int statusCode)
            throws ParseException, InvalidArgumentException, SipException {
        Request request = requestEvent.getRequest();
        Response response = messageFactory.createResponse(statusCode, request);
        UserAgentHeader userAgentHeader = headerFactory
                .createUserAgentHeader(List.of(appProperties.getGb28181().getUserAgent()));
        response.setHeader(userAgentHeader);

        ServerTransaction serverTransaction = requestEvent.getServerTransaction();
        if (serverTransaction == null) {
            try {
                serverTransaction = sipProvider.getNewServerTransaction(request);
            } catch (TransactionAlreadyExistsException ignored) {
                serverTransaction = requestEvent.getServerTransaction();
            }
        }
        if (serverTransaction != null) {
            serverTransaction.sendResponse(response);
            return;
        }
        // Stateless fallback: improves compatibility for retransmitted UDP
        // REGISTER/MESSAGE requests.
        sipProvider.sendResponse(response);
    }

    private InviteBuildResult buildInviteRequest(InviteCommand command)
            throws ParseException, InvalidArgumentException, PeerUnavailableException {
        TargetDevice target = new TargetDevice(
                command.channelId(),
                command.deviceHost(),
                command.devicePort(),
                command.sipTransport());
        Request request = createBaseRequest(Request.INVITE, target);
        String subjectStreamId = firstNonBlank(command.streamId(), command.ssrc());
        request.addHeader(headerFactory.createHeader(
                "Subject",
                command.channelId() + ":" + subjectStreamId + "," + command.deviceId() + ":" + subjectStreamId));

        String sdp = buildInviteSdp(command);
        ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("Application", "SDP");
        request.setContent(sdp, contentTypeHeader);

        CallIdHeader callIdHeader = (CallIdHeader) request.getHeader(CallIdHeader.NAME);
        return new InviteBuildResult(request, callIdHeader == null ? null : callIdHeader.getCallId());
    }

    private Request createBaseRequest(String method, TargetDevice target)
            throws ParseException, InvalidArgumentException, PeerUnavailableException {
        String transport = resolveSipTransport(target.transport());

        SipURI requestUri = addressFactory.createSipURI(target.deviceId(), appProperties.getGb28181().getDomain());

        SipURI fromUri = addressFactory.createSipURI(appProperties.getGb28181().getServerId(),
                appProperties.getGb28181().getDomain());
        Address fromAddress = addressFactory.createAddress(fromUri);
        FromHeader fromHeader = headerFactory.createFromHeader(fromAddress, randomTag());

        SipURI toUri = addressFactory.createSipURI(target.deviceId(), appProperties.getGb28181().getDomain());
        Address toAddress = addressFactory.createAddress(toUri);
        ToHeader toHeader = headerFactory.createToHeader(toAddress, null);

        List<ViaHeader> viaHeaders = new ArrayList<>(1);
        ViaHeader viaHeader = headerFactory.createViaHeader(
                resolveAnnounceIp(),
                appProperties.getGb28181().getLocalPort(),
                transport,
                null);
        viaHeaders.add(viaHeader);

        CallIdHeader callIdHeader = sipProvider.getNewCallId();
        CSeqHeader cSeqHeader = headerFactory.createCSeqHeader(nextCSeq(), method);
        MaxForwardsHeader maxForwardsHeader = headerFactory.createMaxForwardsHeader(70);

        Request request = messageFactory.createRequest(
                requestUri,
                method,
                callIdHeader,
                cSeqHeader,
                fromHeader,
                toHeader,
                viaHeaders,
                maxForwardsHeader);

        SipURI routeUri = addressFactory.createSipURI(null, target.host());
        routeUri.setPort(target.port());
        routeUri.setTransportParam(transport.toLowerCase());
        routeUri.setLrParam();
        Address routeAddress = addressFactory.createAddress(routeUri);
        RouteHeader routeHeader = headerFactory.createRouteHeader(routeAddress);
        request.addHeader(routeHeader);

        SipURI contactUri = addressFactory.createSipURI(appProperties.getGb28181().getServerId(), resolveAnnounceIp());
        contactUri.setPort(appProperties.getGb28181().getLocalPort());
        Address contactAddress = addressFactory.createAddress(contactUri);
        ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);
        request.addHeader(contactHeader);
        request.addHeader(headerFactory.createUserAgentHeader(List.of(appProperties.getGb28181().getUserAgent())));
        return request;
    }

    private SipCommandResult sendCommandAndWait(Request request) {
        CallIdHeader callIdHeader = (CallIdHeader) request.getHeader(CallIdHeader.NAME);
        String callId = callIdHeader == null ? null : callIdHeader.getCallId();
        CompletableFuture<SipCommandResult> future = new CompletableFuture<>();
        if (callId != null) {
            pendingCommandByCallId.put(callId, future);
        }
        try {
            ClientTransaction clientTransaction = sipProvider.getNewClientTransaction(request);
            clientTransaction.sendRequest();
            return future.get(appProperties.getGb28181().getInviteTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            if (callId != null) {
                pendingCommandByCallId.remove(callId);
            }
            return SipCommandResult.failed(callId, 408, "等待设备响应超时");
        } catch (Exception ex) {
            if (callId != null) {
                pendingCommandByCallId.remove(callId);
            }
            return SipCommandResult.failed(callId, 500, ex.getMessage());
        }
    }

    private TargetDevice resolveTargetDevice(String deviceId) {
        Device device = deviceService.findDeviceByCode(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在: " + deviceId));
        return new TargetDevice(device.deviceId(), device.ip(), device.port(), device.transport());
    }

    private String buildInviteSdp(InviteCommand command) {
        int streamMode = command.streamMode();
        boolean tcpMode = streamMode != 0;
        String mediaIp = resolveInviteMediaIp(command.announcedMediaIp());
        StringBuilder builder = new StringBuilder(256);
        builder.append("v=0").append("\r\n");
        builder.append("o=").append(command.channelId()).append(" 0 0 IN IP4 ").append(mediaIp)
                .append("\r\n");
        builder.append("s=Play").append("\r\n");
        builder.append("c=IN IP4 ").append(mediaIp).append("\r\n");
        builder.append("t=0 0").append("\r\n");
        builder.append("m=video ").append(command.rtpPort()).append(" ")
                .append(tcpMode ? "TCP/RTP/AVP" : "RTP/AVP")
                .append(" 96 97 98").append("\r\n");
        builder.append("a=recvonly").append("\r\n");
        builder.append("a=rtpmap:96 PS/90000").append("\r\n");
        builder.append("a=rtpmap:97 MPEG4/90000").append("\r\n");
        builder.append("a=rtpmap:98 H264/90000").append("\r\n");
        if (streamMode == 1) {
            builder.append("a=setup:passive").append("\r\n");
            builder.append("a=connection:new").append("\r\n");
            // ZLMediaKit openRtpServer (tcp_mode=1) only listens on the RTP port, but some
            // devices
            // will try to establish an extra RTCP connection on port+1. Use rtcp-mux to
            // force
            // single-port transport and improve compatibility (e.g. EasyGBD simulator).
            builder.append("a=rtcp-mux").append("\r\n");
        } else if (streamMode == 2) {
            builder.append("a=setup:active").append("\r\n");
            builder.append("a=connection:new").append("\r\n");
            builder.append("a=rtcp-mux").append("\r\n");
        }
        builder.append("y=").append(command.ssrc()).append("\r\n");
        return builder.toString();
    }

    private String resolveInviteMediaIp(String announcedMediaIp) {
        if (announcedMediaIp != null && !announcedMediaIp.isBlank()) {
            return announcedMediaIp.trim();
        }
        return appProperties.getGb28181().getMediaIp();
    }

    private Optional<String> extractDeviceIdFromRequest(Request request) {
        FromHeader fromHeader = (FromHeader) request.getHeader(FromHeader.NAME);
        String from = extractUserPart(fromHeader);
        if (from != null) {
            return Optional.of(from);
        }
        ToHeader toHeader = (ToHeader) request.getHeader(ToHeader.NAME);
        String to = extractUserPart(toHeader);
        return Optional.ofNullable(to);
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
        Pattern pattern = Pattern.compile("<" + tag + ">([\\s\\S]*?)</" + tag + ">", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(xml);
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

    private String normalizeTransport(String transport) {
        if ("TCP".equalsIgnoreCase(transport)) {
            return ListeningPoint.TCP;
        }
        return ListeningPoint.UDP;
    }

    private String resolveSipTransport(String preferredTransport) {
        String normalized = normalizeTransport(preferredTransport);
        if (sipProvider == null) {
            return normalized;
        }
        ListeningPoint preferredPoint = sipProvider.getListeningPoint(normalized);
        if (preferredPoint != null) {
            return normalized;
        }
        ListeningPoint udpPoint = sipProvider.getListeningPoint(ListeningPoint.UDP);
        if (udpPoint != null) {
            if (!ListeningPoint.UDP.equals(normalized)) {
                log.warn("SIP transport {} unavailable, fallback to UDP", normalized);
            }
            return ListeningPoint.UDP;
        }
        ListeningPoint[] points = sipProvider.getListeningPoints();
        if (points != null && points.length > 0 && points[0] != null) {
            String fallback = points[0].getTransport();
            log.warn("SIP transport {} unavailable, fallback to {}", normalized, fallback);
            return fallback;
        }
        return normalized;
    }

    private List<String> availableSipTransports() {
        if (sipProvider == null) {
            return List.of();
        }
        ListeningPoint[] points = sipProvider.getListeningPoints();
        if (points == null || points.length == 0) {
            return List.of();
        }
        List<String> transports = new ArrayList<>(points.length);
        for (ListeningPoint point : points) {
            if (point != null && point.getTransport() != null) {
                transports.add(point.getTransport());
            }
        }
        return transports;
    }

    private String normalizeDeviceTransport(String transport) {
        if ("TCP".equalsIgnoreCase(transport)) {
            return "TCP";
        }
        return "UDP";
    }

    private String normalizeChannelId(String deviceId, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (deviceId != null && deviceId.equals(normalized)) {
            return null;
        }
        return normalized;
    }

    private String inferCodec(String xml) {
        String codec = extractXmlTag(xml, "Codec").orElse(null);
        if (codec == null) {
            return null;
        }
        String upper = codec.toUpperCase();
        if (upper.contains("265") || upper.contains("HEVC")) {
            return "H265";
        }
        return "H264";
    }

    private String normalizeChannelStatus(String status) {
        if (status == null) {
            return "OFFLINE";
        }
        String value = status.trim().toUpperCase();
        if ("ON".equals(value) || "ONLINE".equals(value) || "1".equals(value) || "OK".equals(value)) {
            return "ONLINE";
        }
        return "OFFLINE";
    }

    private long nextCSeq() {
        return cSeq.incrementAndGet();
    }

    private String randomTag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private void ensureSipReady() {
        if (sipProvider == null || sipStack == null || headerFactory == null || addressFactory == null
                || messageFactory == null) {
            throw new IllegalStateException("SIP服务尚未初始化");
        }
    }

    public String generateSsrc() {
        String prefix = appProperties.getGb28181().getSsrcPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "0";
        }
        prefix = prefix.trim();
        if (prefix.length() > 1) {
            prefix = prefix.substring(0, 1);
        }

        String domain = appProperties.getGb28181().getDomain();
        String mid = "00000";
        if (domain != null) {
            String normalized = domain.trim();
            if (normalized.length() >= 8) {
                mid = normalized.substring(3, 8);
            } else if (!normalized.isBlank()) {
                mid = String.format("%-5s", normalized).replace(' ', '0');
            }
        }

        int seq = ssrcSeq.updateAndGet(current -> current >= 9999 ? 1 : current + 1);
        return prefix + mid + String.format("%04d", seq);
    }

    public record InviteCommand(
            String deviceId,
            String deviceHost,
            int devicePort,
            String channelId,
            String sipTransport,
            int streamMode,
            int rtpPort,
            String ssrc,
            String announcedMediaIp,
            String streamId) {
    }

    public record InviteResult(
            boolean success,
            String callId,
            int statusCode,
            String reason) {
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

    public record SipCommandResult(
            boolean success,
            String callId,
            int statusCode,
            String reason,
            String timestamp) {
        public static SipCommandResult success(String callId, int statusCode, String reason) {
            return new SipCommandResult(true, callId, statusCode, reason, Instant.now().toString());
        }

        public static SipCommandResult failed(String callId, int statusCode, String reason) {
            return new SipCommandResult(false, callId, statusCode, reason, Instant.now().toString());
        }

        public static SipCommandResult skipped(String reason) {
            return new SipCommandResult(true, null, 0, reason, Instant.now().toString());
        }
    }

    private record InviteBuildResult(
            Request request,
            String callId) {
    }

    private record TargetDevice(
            String deviceId,
            String host,
            int port,
            String transport) {
    }

    private record DeviceEndpoint(
            String host,
            int port,
            String transport) {
    }
}
