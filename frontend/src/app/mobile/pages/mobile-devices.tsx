import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router";
import { Monitor, Power, RefreshCw, Video } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardContent } from "../../components/ui/card";
import { deviceApi, type Device } from "../../lib/api";
import MobilePage from "../components/mobile-page";

function formatDateTime(value?: string | null) {
  if (!value) {
    return "暂无";
  }
  return new Date(value).toLocaleString("zh-CN");
}

export default function MobileDevices() {
  const [devices, setDevices] = useState<Device[]>([]);
  const [loading, setLoading] = useState(true);
  const [togglingId, setTogglingId] = useState<number | null>(null);

  const loadDevices = useCallback(async () => {
    try {
      setLoading(true);
      const data = await deviceApi.list();
      setDevices(data);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "设备加载失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadDevices();
  }, [loadDevices]);

  const toggleStatus = async (device: Device) => {
    try {
      setTogglingId(device.id);
      await deviceApi.updateStatus(device.id, !device.online);
      toast.success(device.online ? "设备已停用" : "设备已启用");
      await loadDevices();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "设备状态更新失败");
    } finally {
      setTogglingId(null);
    }
  };

  return (
    <MobilePage
      title="设备管理"
      description="移动端优先展示设备巡检信息，编辑和高级配置后续逐步补齐。"
      action={
        <Button disabled={loading} onClick={loadDevices} size="sm" variant="outline">
          <RefreshCw className="mr-2 h-4 w-4" />
          刷新
        </Button>
      }
    >
      {loading ? (
        <Card className="border-slate-200 shadow-sm">
          <CardContent className="px-4 py-8 text-center text-sm text-slate-500">
            正在加载设备列表...
          </CardContent>
        </Card>
      ) : devices.length === 0 ? (
        <Card className="border-slate-200 shadow-sm">
          <CardContent className="px-4 py-8 text-center text-sm text-slate-500">
            暂无设备，请先在桌面端完成设备注册。
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-3">
          {devices.map((device) => (
            <Card key={device.id} className="border-slate-200 shadow-sm">
              <CardContent className="space-y-4 px-4 py-4">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <div className="flex h-10 w-10 items-center justify-center rounded-2xl bg-primary/10 text-primary">
                        <Monitor className="h-5 w-5" />
                      </div>
                      <div className="min-w-0">
                        <p className="truncate font-medium text-slate-900">
                          {device.name}
                        </p>
                        <p className="truncate text-xs text-slate-500">
                          {device.deviceId}
                        </p>
                      </div>
                    </div>
                  </div>
                  <Badge variant={device.online ? "default" : "secondary"}>
                    {device.online ? "在线" : "离线"}
                  </Badge>
                </div>

                <div className="grid grid-cols-2 gap-3 text-sm">
                  <div className="rounded-2xl bg-slate-50 px-3 py-3">
                    <p className="text-xs text-slate-500">接入地址</p>
                    <p className="mt-1 font-medium text-slate-900">
                      {device.ip}:{device.port}
                    </p>
                  </div>
                  <div className="rounded-2xl bg-slate-50 px-3 py-3">
                    <p className="text-xs text-slate-500">通道 / 编码</p>
                    <p className="mt-1 font-medium text-slate-900">
                      {device.channelCount} / {device.preferredCodec}
                    </p>
                  </div>
                  <div className="rounded-2xl bg-slate-50 px-3 py-3">
                    <p className="text-xs text-slate-500">制造商</p>
                    <p className="mt-1 font-medium text-slate-900">
                      {device.manufacturer || "未填写"}
                    </p>
                  </div>
                  <div className="rounded-2xl bg-slate-50 px-3 py-3">
                    <p className="text-xs text-slate-500">最后心跳</p>
                    <p className="mt-1 font-medium text-slate-900">
                      {formatDateTime(device.lastSeenAt)}
                    </p>
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  <Button asChild className="flex-1">
                    <Link to={`/m/preview?devicePk=${device.id}`}>
                      <Video className="mr-2 h-4 w-4" />
                      去预览
                    </Link>
                  </Button>
                  <Button
                    className="flex-1"
                    disabled={togglingId === device.id}
                    onClick={() => toggleStatus(device)}
                    variant="outline"
                  >
                    <Power className="mr-2 h-4 w-4" />
                    {device.online ? "停用" : "启用"}
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </MobilePage>
  );
}
