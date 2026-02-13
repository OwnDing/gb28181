import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "../components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "../components/ui/select";
import { Button } from "../components/ui/button";
import { Badge } from "../components/ui/badge";
import { Pause, Play, Link2, RefreshCw, Square } from "lucide-react";
import { toast } from "sonner";
import {
  deviceApi,
  previewApi,
  type Device,
  type DeviceChannel,
  type PreviewStartResponse,
} from "../lib/api";

type PreviewProtocol = "WEBRTC" | "HLS" | "HTTP_FLV";

function supportsH265Browser() {
  if (typeof window === "undefined") {
    return false;
  }
  const video = document.createElement("video");
  const hevcChecks = [
    'video/mp4; codecs="hvc1.1.6.L93.B0"',
    'video/mp4; codecs="hev1.1.6.L93.B0"',
  ];
  return hevcChecks.some((item) => video.canPlayType(item) !== "");
}

export default function VideoPreview() {
  const [devices, setDevices] = useState<Device[]>([]);
  const [channels, setChannels] = useState<DeviceChannel[]>([]);
  const [selectedDevicePk, setSelectedDevicePk] = useState<string>("");
  const [selectedChannelId, setSelectedChannelId] = useState<string>("");
  const [protocol, setProtocol] = useState<PreviewProtocol>("WEBRTC");
  const [loading, setLoading] = useState(true);
  const [starting, setStarting] = useState(false);
  const [sessions, setSessions] = useState<PreviewStartResponse[]>([]);
  const sessionsRef = useRef<PreviewStartResponse[]>([]);

  const browserSupportsH265 = useMemo(() => supportsH265Browser(), []);

  const selectedDevice = devices.find((item) => String(item.id) === selectedDevicePk) || null;
  const selectedChannel = channels.find((item) => item.channelId === selectedChannelId) || null;
  const totalViewers = sessions.reduce((sum, item) => sum + item.viewerCount, 0);

  useEffect(() => {
    sessionsRef.current = sessions;
  }, [sessions]);

  const loadDevices = useCallback(async () => {
    try {
      setLoading(true);
      const list = await deviceApi.list();
      setDevices(list);
      const online = list.find((item) => item.online);
      if (online) {
        setSelectedDevicePk(String(online.id));
      } else if (list.length > 0) {
        setSelectedDevicePk(String(list[0].id));
      }
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "设备加载失败");
    } finally {
      setLoading(false);
    }
  }, []);

  const loadChannels = useCallback(async (devicePk: number) => {
    try {
      const channelList = await deviceApi.channels(devicePk);
      setChannels(channelList);
      if (channelList.length > 0) {
        setSelectedChannelId(channelList[0].channelId);
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
      sessionsRef.current.forEach((item) => {
        previewApi.stop(item.sessionId).catch(() => undefined);
      });
    };
  }, []);

  useEffect(() => {
    if (sessions.length === 0) {
      return;
    }
    const timer = window.setInterval(async () => {
      try {
        const statusList = await previewApi.status();
        setSessions((prev) =>
          prev.map((item) => {
            const matched = statusList.find((status) => status.sessionId === item.sessionId);
            if (!matched) {
              return item;
            }
            return {
              ...item,
              viewerCount: matched.viewerCount,
              codec: matched.codec,
              protocol: matched.protocol,
              playUrl: matched.playUrl,
            };
          }),
        );
      } catch {
        // ignore polling errors
      }
    }, 5000);
    return () => window.clearInterval(timer);
  }, [sessions.length]);

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
      toast.error("该设备没有通道");
      return;
    }
    try {
      setStarting(true);
      const data = await previewApi.start({
        devicePk: selectedDevice.id,
        channelId: selectedChannelId,
        protocol,
        browserSupportsH265,
      });
      setSessions((prev) => {
        const index = prev.findIndex((item) => item.sessionId === data.sessionId);
        if (index < 0) {
          return [data, ...prev];
        }
        const copy = [...prev];
        copy[index] = data;
        return copy;
      });
      toast.success(data.message || "预览启动成功");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "启动预览失败");
    } finally {
      setStarting(false);
    }
  };

  const handleStop = useCallback(async (sessionId: string) => {
    try {
      await previewApi.stop(sessionId);
      setSessions((prev) => prev.filter((item) => item.sessionId !== sessionId));
      toast.success("已停止预览");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "停止预览失败");
    }
  }, []);

  const handleStopAll = async () => {
    if (sessions.length === 0) {
      return;
    }
    const ids = sessions.map((item) => item.sessionId);
    await Promise.allSettled(ids.map((id) => previewApi.stop(id)));
    setSessions([]);
    toast.success("已停止全部预览");
  };

  const handleRefresh = async () => {
    await loadDevices();
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-3xl font-semibold text-slate-900">在线预览</h2>
          <p className="text-slate-500 mt-1">实时查看视频监控画面（H.264 / H.265）</p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" onClick={handleRefresh}>
            <RefreshCw className="w-4 h-4 mr-2" />
            刷新设备
          </Button>
          <Button variant="outline" onClick={handleStopAll} disabled={sessions.length === 0}>
            <Square className="w-4 h-4 mr-2" />
            停止全部
          </Button>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <Card className="lg:col-span-2">
          <CardHeader>
            <div className="flex items-center justify-between">
              <div>
                <CardTitle>视频画面（多路）</CardTitle>
                <CardDescription>
                  {selectedDevice
                    ? `${selectedDevice.name} (${selectedDevice.deviceId})`
                    : "请选择设备"}
                </CardDescription>
              </div>
              {selectedDevice ? (
                <Badge variant={selectedDevice.online ? "default" : "secondary"}>
                  {selectedDevice.online ? "在线" : "离线"}
                </Badge>
              ) : null}
            </div>
          </CardHeader>
          <CardContent className="space-y-4">
            {sessions.length === 0 ? (
              <div className="relative bg-slate-900 rounded-lg overflow-hidden aspect-video">
                <div className="w-full h-full flex items-center justify-center text-slate-300">
                  {loading ? "正在加载设备..." : "点击播放开始预览"}
                </div>
              </div>
            ) : (
              <div
                className={`grid gap-4 ${
                  sessions.length === 1
                    ? "grid-cols-1"
                    : sessions.length <= 4
                      ? "grid-cols-1 md:grid-cols-2"
                      : "grid-cols-1 md:grid-cols-2 xl:grid-cols-3"
                }`}
              >
                {sessions.map((item) => (
                  <div key={item.sessionId} className="space-y-2">
                    <div className="relative bg-slate-900 rounded-lg overflow-hidden aspect-video">
                      {item.protocol === "WEBRTC" ? (
                        <iframe
                          title={`zlm-player-${item.sessionId}`}
                          src={item.webrtcPlayerUrl}
                          className="w-full h-full border-0"
                          allow="autoplay; fullscreen"
                        />
                      ) : (
                        <video src={item.playUrl} className="w-full h-full" autoPlay controls muted />
                      )}
                    </div>
                    <div className="flex items-center justify-between gap-2">
                      <div className="text-xs text-slate-500 truncate">
                        {item.deviceId} / {item.channelId} / {item.codec}
                      </div>
                      <div className="flex items-center">
                        <Button variant="ghost" size="sm" onClick={() => handleStop(item.sessionId)}>
                          <Pause className="w-4 h-4" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => window.open(item.playUrl, "_blank")}
                        >
                          <Link2 className="w-4 h-4" />
                        </Button>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}

            <div className="flex items-center justify-between bg-slate-100 rounded-lg p-3">
              <div className="flex items-center gap-2">
                <Button variant="ghost" size="sm" onClick={handleStart} disabled={starting}>
                  <Play className="w-5 h-5" />
                </Button>
                <span className="text-sm text-slate-600">{starting ? "启动中..." : "新增一路预览"}</span>
              </div>
            </div>
          </CardContent>
        </Card>

        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle>预览参数</CardTitle>
              <CardDescription>选择设备、通道和播放协议</CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <div className="text-sm text-slate-500">设备</div>
                <Select value={selectedDevicePk} onValueChange={setSelectedDevicePk}>
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
              <div className="space-y-2">
                <div className="text-sm text-slate-500">通道</div>
                <Select value={selectedChannelId} onValueChange={setSelectedChannelId}>
                  <SelectTrigger>
                    <SelectValue placeholder="请选择通道" />
                  </SelectTrigger>
                  <SelectContent>
                    {channels.map((channel) => (
                      <SelectItem key={channel.channelId} value={channel.channelId}>
                        {channel.name} ({channel.codec})
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-2">
                <div className="text-sm text-slate-500">协议</div>
                <Select value={protocol} onValueChange={(v: PreviewProtocol) => setProtocol(v)}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="WEBRTC">WebRTC（低延迟）</SelectItem>
                    <SelectItem value="HLS">HLS（高兼容）</SelectItem>
                    <SelectItem value="HTTP_FLV">HTTP-FLV</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>流信息</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3 text-sm">
              <div className="flex justify-between">
                <span className="text-slate-500">浏览器 H.265 支持</span>
                <span>{browserSupportsH265 ? "支持" : "不支持"}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">通道编码</span>
                <span>{selectedChannel?.codec ?? "-"}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">当前协议</span>
                <span>{sessions[0]?.protocol ?? "-"}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">活跃会话</span>
                <span>{sessions.length}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">总观看人数</span>
                <span>{totalViewers}</span>
              </div>
              {sessions[0] ? (
                <div className="pt-2 border-t break-all text-xs text-slate-500">
                  播放地址: {sessions[0].playUrl}
                </div>
              ) : null}
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}
