import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "react-router";
import { Pause, Play, RefreshCw, Video } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "../../components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "../../components/ui/select";
import JessibucaPlayer from "../../components/JessibucaPlayer";
import {
  deviceApi,
  previewApi,
  type Device,
  type DeviceChannel,
  type PreviewSessionStatus,
  type PreviewStartResponse,
} from "../../lib/api";
import MobilePage from "../components/mobile-page";

type PreviewProtocol = "WEBRTC" | "HLS" | "HTTP_FLV";

function supportsH265Browser() {
  if (typeof window === "undefined") {
    return false;
  }
  if (
    typeof RTCRtpReceiver !== "undefined" &&
    typeof RTCRtpReceiver.getCapabilities === "function"
  ) {
    const caps = RTCRtpReceiver.getCapabilities("video");
    if (caps && Array.isArray(caps.codecs)) {
      return caps.codecs.some((item) => {
        const mime = (item.mimeType || "").toLowerCase();
        const fmtp = (item.sdpFmtpLine || "").toLowerCase();
        return (
          mime.includes("h265") ||
          mime.includes("hevc") ||
          fmtp.includes("h265") ||
          fmtp.includes("hevc")
        );
      });
    }
  }
  const video = document.createElement("video");
  return (
    video.canPlayType('video/mp4; codecs="hvc1.1.6.L93.B0"') !== "" ||
    video.canPlayType('video/mp4; codecs="hev1.1.6.L93.B0"') !== ""
  );
}

function isIosDevice() {
  if (typeof navigator === "undefined") {
    return false;
  }
  return /iphone|ipad|ipod/i.test(navigator.userAgent);
}

function WebRtcPlayer({ sessionId }: { sessionId: string }) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const peerRef = useRef<RTCPeerConnection | null>(null);
  const [errorText, setErrorText] = useState("");

  useEffect(() => {
    let disposed = false;

    if (typeof RTCPeerConnection === "undefined") {
      setErrorText("当前环境不支持 WebRTC");
      return;
    }

    const closePeer = () => {
      const peer = peerRef.current;
      peerRef.current = null;
      if (peer) {
        peer.ontrack = null;
        peer.onconnectionstatechange = null;
        peer.close();
      }
    };

    const startOnce = async (preferredTcp: boolean) => {
      closePeer();
      const peer = new RTCPeerConnection({ iceServers: [] });
      peerRef.current = peer;

      let fallbackStream: MediaStream | null = null;
      let settled = false;
      const streamReady = new Promise<void>((resolve, reject) => {
        const timer = window.setTimeout(() => {
          if (settled) {
            return;
          }
          settled = true;
          reject(new Error("WebRTC 收流超时"));
        }, 10000);

        const resolveOnce = () => {
          if (settled) {
            return;
          }
          settled = true;
          window.clearTimeout(timer);
          resolve();
        };

        const rejectOnce = (message: string) => {
          if (settled) {
            return;
          }
          settled = true;
          window.clearTimeout(timer);
          reject(new Error(message));
        };

        peer.ontrack = (event) => {
          if (disposed || !videoRef.current) {
            return;
          }
          if (event.streams && event.streams.length > 0) {
            videoRef.current.srcObject = event.streams[0];
          } else {
            if (!fallbackStream) {
              fallbackStream = new MediaStream();
            }
            fallbackStream.addTrack(event.track);
            videoRef.current.srcObject = fallbackStream;
          }
          videoRef.current.play().catch(() => undefined);
          resolveOnce();
        };

        peer.onconnectionstatechange = () => {
          if (disposed) {
            return;
          }
          const state = peer.connectionState;
          if (
            state === "failed" ||
            state === "closed" ||
            state === "disconnected"
          ) {
            rejectOnce(`WebRTC 连接失败 (${state})`);
          }
        };
      });
      streamReady.catch(() => undefined);

      peer.addTransceiver("video", { direction: "recvonly" });
      peer.addTransceiver("audio", { direction: "recvonly" });
      const offer = await peer.createOffer();
      await peer.setLocalDescription(offer);
      if (!offer.sdp) {
        throw new Error("未能生成本地 SDP");
      }

      const answer = await previewApi.webrtcPlay({
        sessionId,
        offerSdp: offer.sdp,
        preferredTcp,
      });

      if (disposed) {
        return;
      }

      await peer.setRemoteDescription({
        type: "answer",
        sdp: answer.sdp,
      });
      await streamReady;
    };

    const start = async () => {
      setErrorText("");
      try {
        await startOnce(false);
      } catch {
        if (disposed) {
          return;
        }
        await startOnce(true);
      }
    };

    start().catch((error) => {
      if (disposed) {
        return;
      }
      setErrorText(error instanceof Error ? error.message : "WebRTC 预览失败");
      closePeer();
    });

    return () => {
      disposed = true;
      closePeer();
      if (videoRef.current) {
        videoRef.current.srcObject = null;
      }
    };
  }, [sessionId]);

  return (
    <div className="relative h-full w-full">
      <video
        autoPlay
        className="h-full w-full bg-black object-contain"
        muted
        playsInline
        ref={videoRef}
      />
      {errorText ? (
        <div className="absolute inset-0 flex items-center justify-center bg-slate-950/85 px-4 text-center text-sm text-slate-100">
          {errorText}
        </div>
      ) : null}
    </div>
  );
}

function formatStatus(status?: PreviewSessionStatus | null) {
  if (!status) {
    return "未建立预览会话";
  }
  return `${status.protocol} / ${status.codec} / ${status.viewerCount} 个查看者`;
}

export default function MobilePreview() {
  const [searchParams] = useSearchParams();
  const [devices, setDevices] = useState<Device[]>([]);
  const [channels, setChannels] = useState<DeviceChannel[]>([]);
  const [selectedDevicePk, setSelectedDevicePk] = useState("");
  const [selectedChannelId, setSelectedChannelId] = useState("");
  const [protocol, setProtocol] = useState<PreviewProtocol>(
    isIosDevice() ? "HLS" : "WEBRTC",
  );
  const [loading, setLoading] = useState(true);
  const [starting, setStarting] = useState(false);
  const [session, setSession] = useState<PreviewStartResponse | null>(null);
  const [sessionStatus, setSessionStatus] = useState<PreviewSessionStatus | null>(
    null,
  );
  const browserSupportsH265 = useMemo(() => supportsH265Browser(), []);
  const sessionRef = useRef<PreviewStartResponse | null>(null);

  const selectedDevice =
    devices.find((item) => String(item.id) === selectedDevicePk) || null;

  useEffect(() => {
    sessionRef.current = session;
  }, [session]);

  const loadDevices = useCallback(async () => {
    try {
      setLoading(true);
      const list = await deviceApi.list();
      setDevices(list);
      const preferredDevicePk = searchParams.get("devicePk");
      if (preferredDevicePk && list.some((item) => String(item.id) === preferredDevicePk)) {
        setSelectedDevicePk(preferredDevicePk);
      } else if (!selectedDevicePk && list.length > 0) {
        const onlineDevice = list.find((item) => item.online);
        setSelectedDevicePk(String((onlineDevice || list[0]).id));
      }
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "设备加载失败");
    } finally {
      setLoading(false);
    }
  }, [searchParams, selectedDevicePk]);

  const loadChannels = useCallback(async (devicePk: number) => {
    try {
      const data = await deviceApi.channels(devicePk);
      setChannels(data);
      if (data.length > 0) {
        setSelectedChannelId((current) =>
          current && data.some((item) => item.channelId === current)
            ? current
            : data[0].channelId,
        );
      } else {
        setSelectedChannelId("");
      }
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "通道加载失败");
      setChannels([]);
      setSelectedChannelId("");
    }
  }, []);

  useEffect(() => {
    loadDevices();
  }, [loadDevices]);

  useEffect(() => {
    if (!selectedDevicePk) {
      setChannels([]);
      setSelectedChannelId("");
      return;
    }
    loadChannels(Number(selectedDevicePk));
  }, [loadChannels, selectedDevicePk]);

  useEffect(() => {
    return () => {
      const activeSession = sessionRef.current;
      if (activeSession) {
        previewApi.stop(activeSession.sessionId).catch(() => undefined);
      }
    };
  }, []);

  useEffect(() => {
    if (!session) {
      setSessionStatus(null);
      return;
    }

    const timer = window.setInterval(async () => {
      try {
        const statusList = await previewApi.status();
        const status =
          statusList.find((item) => item.sessionId === session.sessionId) || null;
        setSessionStatus(status);
      } catch {
        // ignore polling errors
      }
    }, 5000);

    return () => window.clearInterval(timer);
  }, [session]);

  const handleStart = async () => {
    if (!selectedDevice) {
      toast.error("请先选择设备");
      return;
    }
    if (!selectedDevice.online) {
      toast.error("设备离线，无法预览");
      return;
    }
    if (!selectedChannelId) {
      toast.error("当前设备没有通道");
      return;
    }

    try {
      setStarting(true);
      if (sessionRef.current) {
        await previewApi.stop(sessionRef.current.sessionId).catch(() => undefined);
      }

      const data = await previewApi.start({
        devicePk: selectedDevice.id,
        channelId: selectedChannelId,
        protocol,
        browserSupportsH265,
      });
      setSession(data);
      setSessionStatus({
        sessionId: data.sessionId,
        devicePk: data.devicePk,
        deviceId: data.deviceId,
        channelId: data.channelId,
        codec: data.codec,
        protocol: data.protocol,
        playUrl: data.playUrl,
        viewerCount: data.viewerCount,
        startedAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      });
      toast.success(data.message || "预览启动成功");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "启动预览失败");
    } finally {
      setStarting(false);
    }
  };

  const handleStop = async () => {
    if (!session) {
      return;
    }
    try {
      await previewApi.stop(session.sessionId);
      setSession(null);
      setSessionStatus(null);
      toast.success("预览已停止");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "停止预览失败");
    }
  };

  const player = session ? (
    session.protocol === "WEBRTC" ? (
      <div className="relative h-full w-full">
        <WebRtcPlayer sessionId={session.sessionId} />
        {!browserSupportsH265 &&
        (session.codec === "h265" || session.codec === "H265") ? (
          <div className="absolute inset-0 z-10 flex items-center justify-center bg-black/85 px-4 text-center text-sm text-slate-100">
            当前环境不支持通过 WebRTC 播放 H.265，请切换到 HLS 或 HTTP-FLV。
          </div>
        ) : null}
      </div>
    ) : session.protocol === "HLS" ? (
      <video
        autoPlay
        className="h-full w-full bg-black object-contain"
        controls
        playsInline
        src={session.hlsUrl || session.playUrl}
      />
    ) : (
      <JessibucaPlayer
        className="h-full w-full"
        isH265={session.codec === "h265" || session.codec === "H265"}
        url={session.playUrl}
      />
    )
  ) : (
    <div className="flex h-full w-full items-center justify-center bg-slate-950 px-4 text-center text-sm text-slate-300">
      选择设备、通道和协议后开始单路预览。
    </div>
  );

  return (
    <MobilePage
      title="实时预览"
      action={
        <Button disabled={loading} onClick={loadDevices} size="sm" variant="outline">
          <RefreshCw className="mr-2 h-4 w-4" />
          刷新
        </Button>
      }
    >
      <Card className="border-slate-200 shadow-sm">
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <Video className="h-5 w-5 text-primary" />
            预览参数
          </CardTitle>
          <CardDescription>优先推荐 HLS 或 WebRTC。</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <div className="text-sm text-slate-500">设备</div>
            <Select onValueChange={setSelectedDevicePk} value={selectedDevicePk}>
              <SelectTrigger>
                <SelectValue placeholder="请选择设备" />
              </SelectTrigger>
              <SelectContent>
                {devices.map((device) => (
                  <SelectItem key={device.id} value={String(device.id)}>
                    {device.name} ({device.online ? "在线" : "离线"})
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-2">
              <div className="text-sm text-slate-500">通道</div>
              <Select
                onValueChange={setSelectedChannelId}
                value={selectedChannelId}
              >
                <SelectTrigger>
                  <SelectValue placeholder="请选择通道" />
                </SelectTrigger>
                <SelectContent>
                  {channels.map((channel) => (
                    <SelectItem key={channel.channelId} value={channel.channelId}>
                      {channel.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-2">
              <div className="text-sm text-slate-500">协议</div>
              <Select
                onValueChange={(value: PreviewProtocol) => setProtocol(value)}
                value={protocol}
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="WEBRTC">WebRTC</SelectItem>
                  <SelectItem value="HLS">HLS</SelectItem>
                  <SelectItem value="HTTP_FLV">HTTP-FLV</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <Button className="flex-1" disabled={starting || loading} onClick={handleStart}>
              <Play className="mr-2 h-4 w-4" />
              {starting ? "启动中..." : "开始预览"}
            </Button>
            <Button
              className="flex-1"
              disabled={!session}
              onClick={handleStop}
              variant="outline"
            >
              <Pause className="mr-2 h-4 w-4" />
              停止
            </Button>
          </div>
        </CardContent>
      </Card>

      <Card className="border-slate-200 shadow-sm">
        <CardContent className="px-0 pt-0">
          <div className="aspect-video overflow-hidden rounded-t-xl bg-black">
            {player}
          </div>
          <div className="space-y-3 px-4 py-4">
            <div className="flex items-center gap-2">
              <Badge variant={selectedDevice?.online ? "default" : "secondary"}>
                {selectedDevice?.online ? "设备在线" : "设备离线"}
              </Badge>
              <Badge variant="outline">{protocol}</Badge>
            </div>
            <p className="text-sm text-slate-600">{formatStatus(sessionStatus)}</p>
            <div className="grid grid-cols-2 gap-3 text-sm">
              <div className="rounded-2xl bg-slate-50 px-3 py-3">
                <p className="text-xs text-slate-500">设备</p>
                <p className="mt-1 font-medium text-slate-900">
                  {selectedDevice?.name || "未选择"}
                </p>
              </div>
              <div className="rounded-2xl bg-slate-50 px-3 py-3">
                <p className="text-xs text-slate-500">通道</p>
                <p className="mt-1 font-medium text-slate-900">
                  {selectedChannelId || "未选择"}
                </p>
              </div>
            </div>
          </div>
        </CardContent>
      </Card>
    </MobilePage>
  );
}
