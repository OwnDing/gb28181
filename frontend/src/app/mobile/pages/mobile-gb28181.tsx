import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Compass,
  MapPin,
  Pause,
  Play,
  RefreshCw,
  SatelliteDish,
  SkipForward,
  Square,
  Video,
} from "lucide-react";
import { toast } from "sonner";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "../../components/ui/select";
import { Slider } from "../../components/ui/slider";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../../components/ui/tabs";
import {
  deviceApi,
  gb28181Api,
  type Device,
  type GbAlarmEvent,
  type GbCatalogItem,
  type GbDeviceProfile,
  type GbMobilePosition,
  type GbPlaybackSession,
  type GbRecordItem,
  type GbSubscription,
  type SipCommandResult,
} from "../../lib/api";
import MobilePage from "../components/mobile-page";

type SubscribeEvent = "Catalog" | "Alarm" | "MobilePosition";

const DEFAULT_LIMIT = 100;

function formatDateTime(value?: string | null) {
  if (!value) {
    return "暂无";
  }
  return new Date(value).toLocaleString("zh-CN");
}

function formatRecordRange(item: GbRecordItem) {
  return `${item.startTime || "-"} 至 ${item.endTime || "-"}`;
}

export default function MobileGb28181() {
  const [devices, setDevices] = useState<Device[]>([]);
  const [selectedDeviceId, setSelectedDeviceId] = useState("");
  const [loading, setLoading] = useState(true);
  const [running, setRunning] = useState(false);
  const [activeTab, setActiveTab] = useState("overview");

  const [channelId, setChannelId] = useState("");
  const [recordStartTime, setRecordStartTime] = useState("");
  const [recordEndTime, setRecordEndTime] = useState("");
  const [eventType, setEventType] = useState<SubscribeEvent>("Catalog");
  const [expires, setExpires] = useState("3600");
  const [ptzSpeed, setPtzSpeed] = useState([128]);
  const [presetNo, setPresetNo] = useState("1");
  const [playbackSpeed, setPlaybackSpeed] = useState("2");
  const [seekSeconds, setSeekSeconds] = useState("60");

  const [profile, setProfile] = useState<GbDeviceProfile | null>(null);
  const [catalog, setCatalog] = useState<GbCatalogItem[]>([]);
  const [records, setRecords] = useState<GbRecordItem[]>([]);
  const [alarms, setAlarms] = useState<GbAlarmEvent[]>([]);
  const [mobilePositions, setMobilePositions] = useState<GbMobilePosition[]>([]);
  const [subscriptions, setSubscriptions] = useState<GbSubscription[]>([]);
  const [playbackSessions, setPlaybackSessions] = useState<GbPlaybackSession[]>([]);
  const [lastResult, setLastResult] = useState<SipCommandResult | null>(null);

  const selectedDevice = useMemo(
    () => devices.find((item) => item.deviceId === selectedDeviceId) ?? null,
    [devices, selectedDeviceId],
  );

  const loadDevices = useCallback(async () => {
    try {
      setLoading(true);
      const list = await deviceApi.list();
      setDevices(list);
      setSelectedDeviceId((current) => current || list[0]?.deviceId || "");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "设备加载失败");
    } finally {
      setLoading(false);
    }
  }, []);

  const loadSnapshots = useCallback(
    async (deviceId: string) => {
      if (!deviceId) {
        return;
      }

      try {
        const [
          profileData,
          catalogData,
          recordData,
          alarmData,
          mobileData,
          subData,
          playbackData,
        ] = await Promise.all([
          gb28181Api.profile(deviceId),
          gb28181Api.catalog(deviceId),
          gb28181Api.records(deviceId, {
            channelId: channelId || undefined,
            limit: DEFAULT_LIMIT,
          }),
          gb28181Api.alarms({ deviceId, limit: DEFAULT_LIMIT }),
          gb28181Api.mobilePositions({ deviceId, limit: DEFAULT_LIMIT }),
          gb28181Api.subscriptions(deviceId),
          gb28181Api.playbackSessions(),
        ]);

        setProfile(profileData);
        setCatalog(catalogData);
        setRecords(recordData);
        setAlarms(alarmData);
        setMobilePositions(mobileData);
        setSubscriptions(subData);
        setPlaybackSessions(
          playbackData.filter((item) => item.deviceId === deviceId),
        );
      } catch (error) {
        toast.error(error instanceof Error ? error.message : "国标数据加载失败");
      }
    },
    [channelId],
  );

  useEffect(() => {
    loadDevices();
  }, [loadDevices]);

  useEffect(() => {
    if (selectedDeviceId) {
      loadSnapshots(selectedDeviceId);
    }
  }, [loadSnapshots, selectedDeviceId]);

  const runAction = useCallback(
    async (action: () => Promise<SipCommandResult>, okMessage: string) => {
      try {
        setRunning(true);
        const result = await action();
        setLastResult(result);
        if (!result.success) {
          toast.error(result.reason || "请求失败");
          return;
        }
        toast.success(okMessage);
        if (selectedDeviceId) {
          await loadSnapshots(selectedDeviceId);
        }
      } catch (error) {
        toast.error(error instanceof Error ? error.message : "请求失败");
      } finally {
        setRunning(false);
      }
    },
    [loadSnapshots, selectedDeviceId],
  );

  const handleSubscribe = async () => {
    if (!selectedDeviceId) {
      toast.error("请先选择设备");
      return;
    }

    try {
      setRunning(true);
      const result = await gb28181Api.subscribe(selectedDeviceId, {
        eventType,
        expires: Number(expires),
      });
      setLastResult(result.sipResult);
      toast.success("订阅已发送");
      await loadSnapshots(selectedDeviceId);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "订阅失败");
    } finally {
      setRunning(false);
    }
  };

  const handlePtz = async (action: string) => {
    if (!selectedDeviceId) {
      toast.error("请先选择设备");
      return;
    }

    await runAction(
      () =>
        gb28181Api.ptzControl(selectedDeviceId, {
          channelId: channelId || undefined,
          action,
          speed: ptzSpeed[0],
          presetNo: Number(presetNo),
        }),
      `云台 ${action} 指令已发送`,
    );
  };

  const handleStartPlayback = async () => {
    if (!selectedDeviceId) {
      toast.error("请先选择设备");
      return;
    }
    if (!recordStartTime || !recordEndTime) {
      toast.error("请先填写回放开始和结束时间");
      return;
    }

    try {
      setRunning(true);
      const result = await gb28181Api.startPlayback(selectedDeviceId, {
        channelId: channelId || undefined,
        startTime: recordStartTime,
        endTime: recordEndTime,
      });
      toast.success(`回放已开始: ${result.session.streamId}`);
      await loadSnapshots(selectedDeviceId);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "回放启动失败");
    } finally {
      setRunning(false);
    }
  };

  const handlePlaybackControl = async (
    sessionId: string,
    action: "PAUSE" | "RESUME" | "SPEED" | "SEEK",
  ) => {
    await runAction(
      () =>
        gb28181Api.controlPlayback(sessionId, {
          action,
          speed: action === "SPEED" ? Number(playbackSpeed) : undefined,
          seekSeconds: action === "SEEK" ? Number(seekSeconds) : undefined,
        }),
      `回放 ${action} 指令已发送`,
    );
  };

  const handleStopPlayback = async (sessionId: string) => {
    try {
      setRunning(true);
      await gb28181Api.stopPlayback(sessionId);
      toast.success("回放已停止");
      if (selectedDeviceId) {
        await loadSnapshots(selectedDeviceId);
      }
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "回放停止失败");
    } finally {
      setRunning(false);
    }
  };

  const selectedStats = useMemo(
    () => [
      {
        label: "目录项",
        value: `${catalog.length}`,
      },
      {
        label: "录像记录",
        value: `${records.length}`,
      },
      {
        label: "订阅数",
        value: `${subscriptions.length}`,
      },
      {
        label: "位置上报",
        value: `${mobilePositions.length}`,
      },
    ],
    [catalog.length, mobilePositions.length, records.length, subscriptions.length],
  );

  return (
    <MobilePage
      title="GB28181 工具"
      description="在手机上完成 DeviceInfo、Catalog、RecordInfo、订阅和 PTZ 的常用操作。"
      action={
        <Button
          disabled={loading || running || !selectedDeviceId}
          onClick={() => selectedDeviceId && loadSnapshots(selectedDeviceId)}
          size="sm"
          variant="outline"
        >
          <RefreshCw className="mr-2 h-4 w-4" />
          刷新
        </Button>
      }
    >
      <Card className="overflow-hidden border-slate-200 shadow-sm">
        <CardContent className="space-y-4 px-4 py-4">
          <div className="space-y-2">
            <Label>设备选择</Label>
            <Select onValueChange={setSelectedDeviceId} value={selectedDeviceId}>
              <SelectTrigger>
                <SelectValue placeholder="请选择国标设备" />
              </SelectTrigger>
              <SelectContent>
                {devices.map((device) => (
                  <SelectItem key={device.id} value={device.deviceId}>
                    {device.name} ({device.deviceId})
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-2">
              <Label htmlFor="mobile-gb-channel-id">通道编号</Label>
              <Input
                id="mobile-gb-channel-id"
                onChange={(event) => setChannelId(event.target.value)}
                placeholder="可选，用于目录/录像/云台"
                value={channelId}
              />
            </div>
            <div className="rounded-2xl bg-slate-50 px-4 py-3">
              <p className="text-xs text-slate-500">设备状态</p>
              <div className="mt-2 flex items-center gap-2">
                <Badge variant={selectedDevice?.online ? "default" : "secondary"}>
                  {selectedDevice?.online ? "在线" : "离线"}
                </Badge>
                <span className="text-xs text-slate-500">
                  {selectedDevice?.name || "未选择设备"}
                </span>
              </div>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-2">
            <Button
              disabled={!selectedDeviceId || running}
              onClick={() =>
                runAction(
                  () => gb28181Api.queryDeviceInfo(selectedDeviceId),
                  "DeviceInfo 查询已发送",
                )
              }
              variant="outline"
            >
              DeviceInfo
            </Button>
            <Button
              disabled={!selectedDeviceId || running}
              onClick={() =>
                runAction(
                  () => gb28181Api.queryCatalog(selectedDeviceId),
                  "Catalog 查询已发送",
                )
              }
              variant="outline"
            >
              Catalog
            </Button>
            <Button
              disabled={!selectedDeviceId || running}
              onClick={() =>
                runAction(
                  () =>
                    gb28181Api.queryRecords(selectedDeviceId, {
                      channelId: channelId || undefined,
                      startTime: recordStartTime || undefined,
                      endTime: recordEndTime || undefined,
                    }),
                  "RecordInfo 查询已发送",
                )
              }
              variant="outline"
            >
              RecordInfo
            </Button>
            <Button
              disabled={!selectedDeviceId || running}
              onClick={() => selectedDeviceId && loadSnapshots(selectedDeviceId)}
            >
              刷新结果
            </Button>
          </div>

          {lastResult ? (
            <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm">
              <div className="flex items-center gap-2">
                <Badge variant={lastResult.success ? "default" : "secondary"}>
                  {lastResult.success ? "成功" : "失败"}
                </Badge>
                <span className="text-slate-600">
                  {lastResult.statusCode} / {lastResult.reason}
                </span>
              </div>
            </div>
          ) : null}
        </CardContent>
      </Card>

      <div className="grid grid-cols-2 gap-3">
        {selectedStats.map((item) => (
          <Card key={item.label} className="border-slate-200 shadow-sm">
            <CardContent className="px-4 py-4">
              <p className="text-xs text-slate-500">{item.label}</p>
              <p className="mt-2 text-2xl font-semibold text-slate-900">
                {item.value}
              </p>
            </CardContent>
          </Card>
        ))}
      </div>

      <Tabs className="gap-4" onValueChange={setActiveTab} value={activeTab}>
        <TabsList className="grid h-auto w-full grid-cols-5 gap-1 rounded-2xl p-1">
          <TabsTrigger className="px-1 py-2 text-xs" value="overview">
            概览
          </TabsTrigger>
          <TabsTrigger className="px-1 py-2 text-xs" value="catalog">
            目录
          </TabsTrigger>
          <TabsTrigger className="px-1 py-2 text-xs" value="records">
            录像
          </TabsTrigger>
          <TabsTrigger className="px-1 py-2 text-xs" value="subscribe">
            订阅
          </TabsTrigger>
          <TabsTrigger className="px-1 py-2 text-xs" value="ptz">
            PTZ
          </TabsTrigger>
        </TabsList>

        <TabsContent className="space-y-4" value="overview">
          <Card className="border-slate-200 shadow-sm">
            <CardHeader className="pb-2">
              <CardTitle className="flex items-center gap-2 text-base">
                <SatelliteDish className="h-5 w-5 text-primary" />
                DeviceInfo
              </CardTitle>
            </CardHeader>
            <CardContent className="grid grid-cols-2 gap-3 text-sm">
              <div className="rounded-2xl bg-slate-50 px-3 py-3">
                <p className="text-xs text-slate-500">设备 ID</p>
                <p className="mt-1 break-all font-medium text-slate-900">
                  {selectedDeviceId || "-"}
                </p>
              </div>
              <div className="rounded-2xl bg-slate-50 px-3 py-3">
                <p className="text-xs text-slate-500">名称</p>
                <p className="mt-1 font-medium text-slate-900">
                  {profile?.name || "-"}
                </p>
              </div>
              <div className="rounded-2xl bg-slate-50 px-3 py-3">
                <p className="text-xs text-slate-500">厂商 / 型号</p>
                <p className="mt-1 font-medium text-slate-900">
                  {profile?.manufacturer || "-"} / {profile?.model || "-"}
                </p>
              </div>
              <div className="rounded-2xl bg-slate-50 px-3 py-3">
                <p className="text-xs text-slate-500">固件 / 状态</p>
                <p className="mt-1 font-medium text-slate-900">
                  {profile?.firmware || "-"} / {profile?.status || "-"}
                </p>
              </div>
            </CardContent>
          </Card>

          <Card className="border-slate-200 shadow-sm">
            <CardHeader className="pb-2">
              <CardTitle className="text-base">最近报警</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              {alarms.length === 0 ? (
                <div className="rounded-2xl border border-dashed border-slate-200 px-4 py-8 text-center text-sm text-slate-500">
                  暂无报警事件
                </div>
              ) : (
                alarms.slice(0, 5).map((alarm) => (
                  <div
                    key={alarm.id}
                    className="rounded-2xl border border-slate-200 px-4 py-4"
                  >
                    <div className="flex items-center gap-2">
                      <Badge variant="secondary">
                        {alarm.alarmMethod || "报警"}
                      </Badge>
                      <span className="text-sm font-medium text-slate-900">
                        {alarm.channelId || alarm.deviceId || "未记录"}
                      </span>
                    </div>
                    <p className="mt-2 text-sm text-slate-600">
                      {alarm.description || alarm.alarmType || "暂无描述"}
                    </p>
                    <p className="mt-2 text-xs text-slate-400">
                      {alarm.alarmTime || formatDateTime(alarm.createdAt)}
                    </p>
                  </div>
                ))
              )}
            </CardContent>
          </Card>

          <Card className="border-slate-200 shadow-sm">
            <CardHeader className="pb-2">
              <CardTitle className="flex items-center gap-2 text-base">
                <MapPin className="h-5 w-5 text-primary" />
                移动位置与回放会话
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              {mobilePositions.slice(0, 3).map((item) => (
                <div
                  key={item.id}
                  className="rounded-2xl border border-slate-200 px-4 py-4"
                >
                  <p className="font-medium text-slate-900">
                    {item.channelId || item.deviceId}
                  </p>
                  <p className="mt-1 text-sm text-slate-500">
                    {item.longitude || "-"}, {item.latitude || "-"}
                  </p>
                  <p className="mt-2 text-xs text-slate-400">{item.time || "-"}</p>
                </div>
              ))}

              {playbackSessions.slice(0, 2).map((session) => (
                <div
                  key={session.id}
                  className="rounded-2xl border border-slate-200 px-4 py-4"
                >
                  <div className="flex items-center justify-between gap-3">
                    <p className="font-medium text-slate-900">{session.channelId}</p>
                    <Badge
                      variant={
                        session.status === "PLAYING"
                          ? "default"
                          : session.status === "CLOSED"
                            ? "secondary"
                            : "outline"
                      }
                    >
                      {session.status}
                    </Badge>
                  </div>
                  <p className="mt-2 text-sm text-slate-500">
                    {session.startTime} 至 {session.endTime}
                  </p>
                </div>
              ))}

              {mobilePositions.length === 0 && playbackSessions.length === 0 ? (
                <div className="rounded-2xl border border-dashed border-slate-200 px-4 py-8 text-center text-sm text-slate-500">
                  暂无位置上报和回放会话
                </div>
              ) : null}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent className="space-y-4" value="catalog">
          <Card className="border-slate-200 shadow-sm">
            <CardHeader className="pb-2">
              <CardTitle className="text-base">Catalog 列表</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              {catalog.length === 0 ? (
                <div className="rounded-2xl border border-dashed border-slate-200 px-4 py-8 text-center text-sm text-slate-500">
                  暂无目录项，先发送 Catalog 查询。
                </div>
              ) : (
                catalog.map((item) => (
                  <div
                    key={item.channelId}
                    className="rounded-2xl border border-slate-200 px-4 py-4"
                  >
                    <div className="flex items-center justify-between gap-3">
                      <div className="min-w-0">
                        <p className="truncate font-medium text-slate-900">
                          {item.name || item.channelId}
                        </p>
                        <p className="mt-1 break-all text-xs text-slate-500">
                          {item.channelId}
                        </p>
                      </div>
                      <Badge variant="outline">{item.status || "-"}</Badge>
                    </div>
                    <p className="mt-2 text-sm text-slate-500">
                      厂商 {item.manufacturer || "-"} / 型号 {item.model || "-"}
                    </p>
                    <p className="mt-1 text-sm text-slate-500">
                      地址 {item.address || "-"}
                    </p>
                  </div>
                ))
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent className="space-y-4" value="records">
          <Card className="border-slate-200 shadow-sm">
            <CardHeader className="pb-2">
              <CardTitle className="flex items-center gap-2 text-base">
                <Video className="h-5 w-5 text-primary" />
                RecordInfo 查询条件
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="mobile-gb-record-start">开始时间</Label>
                <Input
                  id="mobile-gb-record-start"
                  onChange={(event) => setRecordStartTime(event.target.value)}
                  placeholder="2026-04-12T00:00:00"
                  value={recordStartTime}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="mobile-gb-record-end">结束时间</Label>
                <Input
                  id="mobile-gb-record-end"
                  onChange={(event) => setRecordEndTime(event.target.value)}
                  placeholder="2026-04-12T23:59:59"
                  value={recordEndTime}
                />
              </div>
              <Button
                className="w-full"
                disabled={!selectedDeviceId || running}
                onClick={() =>
                  runAction(
                    () =>
                      gb28181Api.queryRecords(selectedDeviceId, {
                        channelId: channelId || undefined,
                        startTime: recordStartTime || undefined,
                        endTime: recordEndTime || undefined,
                      }),
                    "RecordInfo 查询已发送",
                  )
                }
              >
                发送录像查询
              </Button>
              <Button
                className="w-full"
                disabled={!selectedDeviceId || running}
                onClick={handleStartPlayback}
                variant="outline"
              >
                开始设备回放
              </Button>
            </CardContent>
          </Card>

          <Card className="border-slate-200 shadow-sm">
            <CardHeader className="pb-2">
              <CardTitle className="text-base">RecordInfo 结果</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              {records.length === 0 ? (
                <div className="rounded-2xl border border-dashed border-slate-200 px-4 py-8 text-center text-sm text-slate-500">
                  暂无录像记录
                </div>
              ) : (
                records.map((item) => (
                  <div
                    key={item.id}
                    className="rounded-2xl border border-slate-200 px-4 py-4"
                  >
                    <p className="font-medium text-slate-900">
                      {item.name || item.recordId || "未命名录像"}
                    </p>
                    <p className="mt-1 text-sm text-slate-500">
                      通道 {item.channelId || "-"}
                    </p>
                    <p className="mt-2 text-sm text-slate-600">
                      {formatRecordRange(item)}
                    </p>
                  </div>
                ))
              )}
            </CardContent>
          </Card>

          <Card className="border-slate-200 shadow-sm">
            <CardHeader className="pb-2">
              <CardTitle className="text-base">回放会话控制</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-2">
                  <Label htmlFor="mobile-gb-playback-speed">倍速</Label>
                  <Input
                    id="mobile-gb-playback-speed"
                    inputMode="decimal"
                    onChange={(event) => setPlaybackSpeed(event.target.value)}
                    value={playbackSpeed}
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="mobile-gb-seek-seconds">快进秒数</Label>
                  <Input
                    id="mobile-gb-seek-seconds"
                    inputMode="numeric"
                    onChange={(event) => setSeekSeconds(event.target.value)}
                    value={seekSeconds}
                  />
                </div>
              </div>

              {playbackSessions.length === 0 ? (
                <div className="rounded-2xl border border-dashed border-slate-200 px-4 py-8 text-center text-sm text-slate-500">
                  暂无回放会话
                </div>
              ) : (
                playbackSessions.map((session) => (
                  <div
                    key={session.id}
                    className="rounded-2xl border border-slate-200 px-4 py-4"
                  >
                    <div className="flex items-center justify-between gap-3">
                      <div className="min-w-0">
                        <p className="truncate font-medium text-slate-900">
                          {session.channelId}
                        </p>
                        <p className="mt-1 text-xs text-slate-500">
                          {session.startTime} 至 {session.endTime}
                        </p>
                      </div>
                      <Badge
                        variant={
                          session.status === "PLAYING"
                            ? "default"
                            : session.status === "CLOSED"
                              ? "secondary"
                              : "outline"
                        }
                      >
                        {session.status}
                      </Badge>
                    </div>

                    <p className="mt-2 text-sm text-slate-500">
                      speed={session.speed} / stream={session.streamId}
                    </p>

                    {session.status !== "CLOSED" ? (
                      <div className="mt-3 grid grid-cols-2 gap-2">
                        <Button
                          disabled={running}
                          onClick={() => handlePlaybackControl(session.sessionId, "PAUSE")}
                          variant="outline"
                        >
                          <Pause className="mr-2 h-4 w-4" />
                          暂停
                        </Button>
                        <Button
                          disabled={running}
                          onClick={() => handlePlaybackControl(session.sessionId, "RESUME")}
                          variant="outline"
                        >
                          <Play className="mr-2 h-4 w-4" />
                          继续
                        </Button>
                        <Button
                          disabled={running}
                          onClick={() => handlePlaybackControl(session.sessionId, "SPEED")}
                          variant="outline"
                        >
                          <Video className="mr-2 h-4 w-4" />
                          倍速
                        </Button>
                        <Button
                          disabled={running}
                          onClick={() => handlePlaybackControl(session.sessionId, "SEEK")}
                          variant="outline"
                        >
                          <SkipForward className="mr-2 h-4 w-4" />
                          快进
                        </Button>
                        <Button
                          className="col-span-2"
                          disabled={running}
                          onClick={() => handleStopPlayback(session.sessionId)}
                          variant="outline"
                        >
                          <Square className="mr-2 h-4 w-4" />
                          停止回放
                        </Button>
                      </div>
                    ) : null}
                  </div>
                ))
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent className="space-y-4" value="subscribe">
          <Card className="border-slate-200 shadow-sm">
            <CardHeader className="pb-2">
              <CardTitle className="text-base">订阅设置</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Label>事件类型</Label>
                <Select
                  onValueChange={(value: SubscribeEvent) => setEventType(value)}
                  value={eventType}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="Catalog">Catalog</SelectItem>
                    <SelectItem value="Alarm">Alarm</SelectItem>
                    <SelectItem value="MobilePosition">MobilePosition</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-2">
                <Label htmlFor="mobile-gb-expires">订阅时长（秒）</Label>
                <Input
                  id="mobile-gb-expires"
                  inputMode="numeric"
                  onChange={(event) => setExpires(event.target.value)}
                  value={expires}
                />
              </div>
              <Button
                className="w-full"
                disabled={!selectedDeviceId || running}
                onClick={handleSubscribe}
              >
                创建订阅
              </Button>
            </CardContent>
          </Card>

          <Card className="border-slate-200 shadow-sm">
            <CardHeader className="pb-2">
              <CardTitle className="text-base">当前订阅</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              {subscriptions.length === 0 ? (
                <div className="rounded-2xl border border-dashed border-slate-200 px-4 py-8 text-center text-sm text-slate-500">
                  暂无订阅
                </div>
              ) : (
                subscriptions.map((item) => (
                  <div
                    key={item.id}
                    className="rounded-2xl border border-slate-200 px-4 py-4"
                  >
                    <div className="flex items-center justify-between gap-3">
                      <div>
                        <p className="font-medium text-slate-900">{item.eventType}</p>
                        <p className="mt-1 text-xs text-slate-500">
                          id={item.id} / expires={item.expires}
                        </p>
                      </div>
                      <Badge
                        variant={item.status === "ACTIVE" ? "default" : "secondary"}
                      >
                        {item.status}
                      </Badge>
                    </div>
                    {item.status === "ACTIVE" ? (
                      <Button
                        className="mt-3 w-full"
                        disabled={running}
                        onClick={() =>
                          runAction(
                            () => gb28181Api.unsubscribe(item.id),
                            "取消订阅指令已发送",
                          )
                        }
                        variant="outline"
                      >
                        取消订阅
                      </Button>
                    ) : null}
                  </div>
                ))
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent className="space-y-4" value="ptz">
          <Card className="border-slate-200 shadow-sm">
            <CardHeader className="pb-2">
              <CardTitle className="flex items-center gap-2 text-base">
                <Compass className="h-5 w-5 text-primary" />
                云台方向控制
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="mx-auto grid w-[220px] grid-cols-3 gap-2">
                <Button disabled={!selectedDeviceId || running} onClick={() => handlePtz("LEFT_UP")} variant="outline">↖</Button>
                <Button disabled={!selectedDeviceId || running} onClick={() => handlePtz("UP")} variant="outline">↑</Button>
                <Button disabled={!selectedDeviceId || running} onClick={() => handlePtz("RIGHT_UP")} variant="outline">↗</Button>
                <Button disabled={!selectedDeviceId || running} onClick={() => handlePtz("LEFT")} variant="outline">←</Button>
                <Button disabled={!selectedDeviceId || running} onClick={() => handlePtz("STOP")}>停止</Button>
                <Button disabled={!selectedDeviceId || running} onClick={() => handlePtz("RIGHT")} variant="outline">→</Button>
                <Button disabled={!selectedDeviceId || running} onClick={() => handlePtz("LEFT_DOWN")} variant="outline">↙</Button>
                <Button disabled={!selectedDeviceId || running} onClick={() => handlePtz("DOWN")} variant="outline">↓</Button>
                <Button disabled={!selectedDeviceId || running} onClick={() => handlePtz("RIGHT_DOWN")} variant="outline">↘</Button>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <Button
                  disabled={!selectedDeviceId || running}
                  onClick={() => handlePtz("ZOOM_IN")}
                  variant="outline"
                >
                  放大
                </Button>
                <Button
                  disabled={!selectedDeviceId || running}
                  onClick={() => handlePtz("ZOOM_OUT")}
                  variant="outline"
                >
                  缩小
                </Button>
              </div>
            </CardContent>
          </Card>

          <Card className="border-slate-200 shadow-sm">
            <CardHeader className="pb-2">
              <CardTitle className="text-base">速度与预置点</CardTitle>
            </CardHeader>
            <CardContent className="space-y-5">
              <div className="space-y-3">
                <div className="flex items-center justify-between">
                  <Label>云台速度</Label>
                  <span className="text-sm text-slate-500">{ptzSpeed[0]}</span>
                </div>
                <Slider
                  max={255}
                  min={0}
                  onValueChange={setPtzSpeed}
                  step={1}
                  value={ptzSpeed}
                />
              </div>

              <div className="space-y-2">
                <Label htmlFor="mobile-gb-preset-no">预置点编号</Label>
                <Input
                  id="mobile-gb-preset-no"
                  inputMode="numeric"
                  onChange={(event) => setPresetNo(event.target.value)}
                  value={presetNo}
                />
              </div>

              <div className="grid grid-cols-3 gap-2">
                <Button
                  disabled={!selectedDeviceId || running}
                  onClick={() => handlePtz("PRESET_CALL")}
                  variant="outline"
                >
                  调用
                </Button>
                <Button
                  disabled={!selectedDeviceId || running}
                  onClick={() => handlePtz("PRESET_SET")}
                  variant="outline"
                >
                  设置
                </Button>
                <Button
                  disabled={!selectedDeviceId || running}
                  onClick={() => handlePtz("PRESET_DELETE")}
                  variant="outline"
                >
                  删除
                </Button>
              </div>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </MobilePage>
  );
}
