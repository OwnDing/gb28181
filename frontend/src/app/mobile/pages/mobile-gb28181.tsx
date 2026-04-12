import { useCallback, useEffect, useState } from "react";
import { Radio, RefreshCw } from "lucide-react";
import { toast } from "sonner";
import { Button } from "../../components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "../../components/ui/card";
import { deviceApi, type Device } from "../../lib/api";
import MobilePage from "../components/mobile-page";

export default function MobileGb28181() {
  const [loading, setLoading] = useState(true);
  const [devices, setDevices] = useState<Device[]>([]);

  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      const data = await deviceApi.list();
      setDevices(data);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "设备信息加载失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  return (
    <MobilePage
      title="GB28181 工具"
      description="首版先把移动端入口和数据概览补齐，下一阶段继续适配查询、订阅、回放和 PTZ 高级能力。"
      action={
        <Button disabled={loading} onClick={loadData} size="sm" variant="outline">
          <RefreshCw className="mr-2 h-4 w-4" />
          刷新
        </Button>
      }
    >
      <Card className="border-slate-200 shadow-sm">
        <CardHeader className="pb-2">
          <CardTitle className="flex items-center gap-2 text-base">
            <Radio className="h-5 w-5 text-primary" />
            当前准备情况
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-3 text-sm text-slate-600">
          <p>当前已发现设备数：{loading ? "--" : devices.length}</p>
          <p>移动端已规划保留 DeviceInfo、Catalog、RecordInfo、订阅和 PTZ 入口。</p>
          <p>复杂结果展示和控制台式布局将在后续阶段做专门适配。</p>
        </CardContent>
      </Card>

      <div className="space-y-3">
        {devices.slice(0, 6).map((device) => (
          <Card key={device.id} className="border-slate-200 shadow-sm">
            <CardContent className="px-4 py-4">
              <p className="font-medium text-slate-900">{device.name}</p>
              <p className="mt-1 text-sm text-slate-500">{device.deviceId}</p>
            </CardContent>
          </Card>
        ))}
      </div>
    </MobilePage>
  );
}
