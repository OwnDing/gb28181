import { useCallback, useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "../components/ui/select";
import { Badge } from "../components/ui/badge";
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
} from "../lib/api";

type SubscribeEvent = "Catalog" | "Alarm" | "MobilePosition";

const DEFAULT_LIMIT = 100;

export default function Gb28181Console() {
  const [devices, setDevices] = useState<Device[]>([]);
  const [selectedDeviceId, setSelectedDeviceId] = useState<string>("");
  const [loading, setLoading] = useState(true);
  const [running, setRunning] = useState(false);
  const [channelId, setChannelId] = useState("");
  const [startTime, setStartTime] = useState("");
  const [endTime, setEndTime] = useState("");
  const [eventType, setEventType] = useState<SubscribeEvent>("Catalog");
  const [expires, setExpires] = useState("3600");
  const [ptzSpeed, setPtzSpeed] = useState(128);
  const [presetNo, setPresetNo] = useState(1);

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
    setLoading(true);
    try {
      const list = await deviceApi.list();
      setDevices(list);
      if (!selectedDeviceId && list.length > 0) {
        setSelectedDeviceId(list[0].deviceId);
      }
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "设备加载失败");
    } finally {
      setLoading(false);
    }
  }, [selectedDeviceId]);

  const loadSnapshots = useCallback(async (deviceId: string) => {
    if (!deviceId) {
      return;
    }
    try {
      const [profileData, catalogData, recordData, alarmData, mobileData, subData, playbackData] = await Promise.all([
        gb28181Api.profile(deviceId),
        gb28181Api.catalog(deviceId),
        gb28181Api.records(deviceId, { channelId: channelId || undefined, limit: DEFAULT_LIMIT }),
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
      setPlaybackSessions(playbackData.filter((item) => item.deviceId === deviceId));
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "国标数据加载失败");
    }
  }, [channelId]);

  useEffect(() => {
    loadDevices();
  }, [loadDevices]);

  useEffect(() => {
    if (!selectedDeviceId) {
      return;
    }
    loadSnapshots(selectedDeviceId);
  }, [loadSnapshots, selectedDeviceId]);

  const runAction = useCallback(async (action: () => Promise<SipCommandResult>, okMessage: string) => {
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
  }, [loadSnapshots, selectedDeviceId]);

  const handleSubscribe = async () => {
    if (!selectedDeviceId) {
      toast.error("请选择设备");
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

  const handleUnsubscribe = async (id: number) => {
    await runAction(() => gb28181Api.unsubscribe(id), "取消订阅已发送");
  };

  const handlePtz = async (action: string) => {
    if (!selectedDeviceId) { toast.error("请选择设备"); return; }
    await runAction(
      () => gb28181Api.ptzControl(selectedDeviceId, { channelId: channelId || undefined, action, speed: ptzSpeed, presetNo }),
      `云台 ${action} 已发送`,
    );
  };

  const handleStartPlayback = async () => {
    if (!selectedDeviceId) { toast.error("请选择设备"); return; }
    if (!startTime || !endTime) { toast.error("请填写回放起止时间"); return; }
    try {
      setRunning(true);
      const result = await gb28181Api.startPlayback(selectedDeviceId, {
        channelId: channelId || undefined, startTime, endTime,
      });
      toast.success(`回放已开始, stream=${result.session.streamId}`);
      if (selectedDeviceId) await loadSnapshots(selectedDeviceId);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "回放启动失败");
    } finally {
      setRunning(false);
    }
  };

  const handleStopPlayback = async (sessionId: string) => {
    try {
      setRunning(true);
      await gb28181Api.stopPlayback(sessionId);
      toast.success("回放已停止");
      if (selectedDeviceId) await loadSnapshots(selectedDeviceId);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "回放停止失败");
    } finally {
      setRunning(false);
    }
  };

  const handleControlPlayback = async (sessionId: string, action: string) => {
    await runAction(
      () => gb28181Api.controlPlayback(sessionId, { action }),
      `回放 ${action} 已发送`,
    );
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-3xl font-semibold text-slate-900">GB28181 能力中心</h2>
          <p className="text-slate-500 mt-1">设备查询、目录/录像、报警与移动位置订阅</p>
        </div>
        <Button variant="outline" onClick={() => loadDevices()} disabled={loading}>
          刷新设备
        </Button>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>查询控制</CardTitle>
          <CardDescription>先选择设备，再发起国标查询和订阅</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <div className="space-y-2">
              <Label>设备</Label>
              <Select value={selectedDeviceId} onValueChange={setSelectedDeviceId}>
                <SelectTrigger>
                  <SelectValue placeholder="请选择设备" />
                </SelectTrigger>
                <SelectContent>
                  {devices.map((item) => (
                    <SelectItem key={item.id} value={item.deviceId}>
                      {item.name} ({item.deviceId})
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label>录像通道（可选）</Label>
              <Input
                placeholder="34020000001320000001"
                value={channelId}
                onChange={(e) => setChannelId(e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label>订阅事件</Label>
              <Select value={eventType} onValueChange={(v: SubscribeEvent) => setEventType(v)}>
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
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <div className="space-y-2">
              <Label>录像开始时间（可选）</Label>
              <Input
                placeholder="2026-02-13T00:00:00"
                value={startTime}
                onChange={(e) => setStartTime(e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label>录像结束时间（可选）</Label>
              <Input
                placeholder="2026-02-13T23:59:59"
                value={endTime}
                onChange={(e) => setEndTime(e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label>订阅过期秒数</Label>
              <Input value={expires} onChange={(e) => setExpires(e.target.value)} />
            </div>
          </div>

          <div className="flex flex-wrap gap-2">
            <Button
              disabled={!selectedDeviceId || running}
              onClick={() => runAction(() => gb28181Api.queryDeviceInfo(selectedDeviceId), "设备信息查询已发送")}
            >
              查询 DeviceInfo
            </Button>
            <Button
              disabled={!selectedDeviceId || running}
              onClick={() => runAction(() => gb28181Api.queryCatalog(selectedDeviceId), "目录查询已发送")}
            >
              查询 Catalog
            </Button>
            <Button
              disabled={!selectedDeviceId || running}
              onClick={() =>
                runAction(
                  () =>
                    gb28181Api.queryRecords(selectedDeviceId, {
                      channelId: channelId || undefined,
                      startTime: startTime || undefined,
                      endTime: endTime || undefined,
                    }),
                  "录像查询已发送",
                )
              }
            >
              查询 RecordInfo
            </Button>
            <Button disabled={!selectedDeviceId || running} onClick={handleSubscribe}>
              创建订阅
            </Button>
            <Button disabled={!selectedDeviceId || !startTime || !endTime || running} onClick={handleStartPlayback}>
              开始回放
            </Button>
            <Button
              variant="outline"
              disabled={!selectedDeviceId || running}
              onClick={() => loadSnapshots(selectedDeviceId)}
            >
              刷新结果
            </Button>
          </div>

          <div className="text-sm text-slate-500">
            设备状态: {selectedDevice ? (selectedDevice.online ? "在线" : "离线") : "-"}
            {lastResult ? (
              <span className="ml-3">
                最近响应: {lastResult.success ? "成功" : "失败"} / {lastResult.statusCode} / {lastResult.reason}
              </span>
            ) : null}
          </div>
        </CardContent>
      </Card>

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
        <Card>
          <CardHeader>
            <CardTitle>设备信息</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-sm">
            <div>设备ID: {selectedDeviceId || "-"}</div>
            <div>名称: {profile?.name || "-"}</div>
            <div>厂商: {profile?.manufacturer || "-"}</div>
            <div>型号: {profile?.model || "-"}</div>
            <div>固件: {profile?.firmware || "-"}</div>
            <div>状态: {profile?.status || "-"}</div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>订阅列表</CardTitle>
            <CardDescription>点击停用可发送 Expires=0</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            {subscriptions.length === 0 ? (
              <div className="text-sm text-slate-500">暂无订阅</div>
            ) : (
              subscriptions.map((item) => (
                <div key={item.id} className="flex items-center justify-between border rounded-md p-3">
                  <div className="text-sm">
                    <div>{item.eventType}</div>
                    <div className="text-slate-500">id={item.id} expires={item.expires}</div>
                  </div>
                  <div className="flex items-center gap-2">
                    <Badge variant={item.status === "ACTIVE" ? "default" : "secondary"}>{item.status}</Badge>
                    {item.status === "ACTIVE" ? (
                      <Button size="sm" variant="outline" onClick={() => handleUnsubscribe(item.id)} disabled={running}>
                        停用
                      </Button>
                    ) : null}
                  </div>
                </div>
              ))
            )}
          </CardContent>
        </Card>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
        <Card>
          <CardHeader>
            <CardTitle>Catalog（{catalog.length}）</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 max-h-80 overflow-auto">
            {catalog.length === 0 ? (
              <div className="text-sm text-slate-500">暂无目录</div>
            ) : (
              catalog.map((item) => (
                <div key={item.channelId} className="border rounded-md p-2 text-sm">
                  <div>{item.name || item.channelId}</div>
                  <div className="text-slate-500">{item.channelId}</div>
                  <div className="text-slate-500">状态: {item.status || "-"}</div>
                </div>
              ))
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>RecordInfo（{records.length}）</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 max-h-80 overflow-auto">
            {records.length === 0 ? (
              <div className="text-sm text-slate-500">暂无录像记录</div>
            ) : (
              records.map((item) => (
                <div key={item.id} className="border rounded-md p-2 text-sm">
                  <div>{item.name || item.recordId || "-"}</div>
                  <div className="text-slate-500">{item.channelId || "-"}</div>
                  <div className="text-slate-500">{item.startTime || "-"} ~ {item.endTime || "-"}</div>
                </div>
              ))
            )}
          </CardContent>
        </Card>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-3 gap-6">
        <Card>
          <CardHeader>
            <CardTitle>报警（{alarms.length}）</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 max-h-72 overflow-auto">
            {alarms.length === 0 ? (
              <div className="text-sm text-slate-500">暂无报警</div>
            ) : (
              alarms.map((item) => (
                <div key={item.id} className="border rounded-md p-2 text-sm">
                  <div>{item.description || item.alarmType || "-"}</div>
                  <div className="text-slate-500">{item.channelId || item.deviceId || "-"}</div>
                </div>
              ))
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>移动位置（{mobilePositions.length}）</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 max-h-72 overflow-auto">
            {mobilePositions.length === 0 ? (
              <div className="text-sm text-slate-500">暂无位置上报</div>
            ) : (
              mobilePositions.map((item) => (
                <div key={item.id} className="border rounded-md p-2 text-sm">
                  <div>{item.channelId || item.deviceId || "-"}</div>
                  <div className="text-slate-500">{item.longitude || "-"}, {item.latitude || "-"}</div>
                  <div className="text-slate-500">{item.time || "-"}</div>
                </div>
              ))
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>回放会话（{playbackSessions.length}）</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 max-h-72 overflow-auto">
            {playbackSessions.length === 0 ? (
              <div className="text-sm text-slate-500">暂无回放会话</div>
            ) : (
              playbackSessions.map((item) => (
                <div key={item.id} className="border rounded-md p-3 text-sm">
                  <div className="flex justify-between items-center">
                    <div>{item.channelId}</div>
                    <Badge variant={item.status === "PLAYING" ? "default" : item.status === "CLOSED" ? "secondary" : "outline"}>{item.status}</Badge>
                  </div>
                  <div className="text-slate-500">{item.startTime} ~ {item.endTime}</div>
                  <div className="text-slate-500">speed={item.speed} stream={item.streamId}</div>
                  {item.status !== "CLOSED" && (
                    <div className="flex gap-1 mt-2">
                      <Button size="sm" variant="outline" disabled={running} onClick={() => handleControlPlayback(item.sessionId, "PAUSE")}>暂停</Button>
                      <Button size="sm" variant="outline" disabled={running} onClick={() => handleControlPlayback(item.sessionId, "RESUME")}>继续</Button>
                      <Button size="sm" variant="outline" disabled={running} onClick={() => handleControlPlayback(item.sessionId, "SPEED")}>倍速</Button>
                      <Button size="sm" variant="destructive" disabled={running} onClick={() => handleStopPlayback(item.sessionId)}>停止</Button>
                    </div>
                  )}
                </div>
              ))
            )}
          </CardContent>
        </Card>
      </div>

      {/* PTZ Control */}
      <Card>
        <CardHeader>
          <CardTitle>云台控制 (PTZ)</CardTitle>
          <CardDescription>选择设备后可控制云台方向、缩放和预置点</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
            {/* Directional pad */}
            <div className="flex flex-col items-center gap-1">
              <div className="text-sm font-medium mb-2">方向控制</div>
              <div className="grid grid-cols-3 gap-1 w-fit">
                <Button size="sm" variant="outline" disabled={!selectedDeviceId || running} onClick={() => handlePtz("LEFT_UP")}>↖</Button>
                <Button size="sm" variant="outline" disabled={!selectedDeviceId || running} onClick={() => handlePtz("UP")}>↑</Button>
                <Button size="sm" variant="outline" disabled={!selectedDeviceId || running} onClick={() => handlePtz("RIGHT_UP")}>↗</Button>
                <Button size="sm" variant="outline" disabled={!selectedDeviceId || running} onClick={() => handlePtz("LEFT")}>←</Button>
                <Button size="sm" variant="destructive" disabled={!selectedDeviceId || running} onClick={() => handlePtz("STOP")}>■</Button>
                <Button size="sm" variant="outline" disabled={!selectedDeviceId || running} onClick={() => handlePtz("RIGHT")}>→</Button>
                <Button size="sm" variant="outline" disabled={!selectedDeviceId || running} onClick={() => handlePtz("LEFT_DOWN")}>↙</Button>
                <Button size="sm" variant="outline" disabled={!selectedDeviceId || running} onClick={() => handlePtz("DOWN")}>↓</Button>
                <Button size="sm" variant="outline" disabled={!selectedDeviceId || running} onClick={() => handlePtz("RIGHT_DOWN")}>↘</Button>
              </div>
              <div className="flex gap-2 mt-2">
                <Button size="sm" variant="outline" disabled={!selectedDeviceId || running} onClick={() => handlePtz("ZOOM_IN")}>放大</Button>
                <Button size="sm" variant="outline" disabled={!selectedDeviceId || running} onClick={() => handlePtz("ZOOM_OUT")}>缩小</Button>
              </div>
            </div>
            {/* Speed control */}
            <div className="space-y-3">
              <div className="text-sm font-medium">云台速度</div>
              <div className="flex items-center gap-2">
                <Input
                  type="number" min={0} max={255}
                  value={ptzSpeed}
                  onChange={(e) => setPtzSpeed(Number(e.target.value))}
                  className="w-24"
                />
                <span className="text-sm text-slate-500">0~255</span>
              </div>
            </div>
            {/* Preset control */}
            <div className="space-y-3">
              <div className="text-sm font-medium">预置点</div>
              <div className="flex items-center gap-2">
                <Input
                  type="number" min={1} max={255}
                  value={presetNo}
                  onChange={(e) => setPresetNo(Number(e.target.value))}
                  className="w-24"
                />
              </div>
              <div className="flex gap-2">
                <Button size="sm" variant="outline" disabled={!selectedDeviceId || running} onClick={() => handlePtz("PRESET_CALL")}>调用</Button>
                <Button size="sm" variant="outline" disabled={!selectedDeviceId || running} onClick={() => handlePtz("PRESET_SET")}>设置</Button>
                <Button size="sm" variant="outline" disabled={!selectedDeviceId || running} onClick={() => handlePtz("PRESET_DELETE")}>删除</Button>
              </div>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
