import { useCallback, useEffect, useState } from "react";
import { AlertTriangle, Image as ImageIcon, RefreshCw } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardContent } from "../../components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "../../components/ui/dialog";
import { alarmApi, type GbAlarmEvent } from "../../lib/api";
import MobilePage from "../components/mobile-page";

function formatDateTime(value?: string | null) {
  if (!value) {
    return "暂无";
  }
  return new Date(value).toLocaleString("zh-CN");
}

export default function MobileAlarms() {
  const [loading, setLoading] = useState(true);
  const [alarms, setAlarms] = useState<GbAlarmEvent[]>([]);
  const [selectedSnapshot, setSelectedSnapshot] = useState<string | null>(null);

  const loadAlarms = useCallback(async () => {
    try {
      setLoading(true);
      const data = await alarmApi.list({ limit: 50 });
      setAlarms(data);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "报警记录获取失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadAlarms();
    const timer = window.setInterval(loadAlarms, 10000);
    return () => window.clearInterval(timer);
  }, [loadAlarms]);

  return (
    <MobilePage
      title="报警中心"
      description="默认展示最近 50 条报警，支持查看快照与定时刷新。"
      action={
        <Button disabled={loading} onClick={loadAlarms} size="sm" variant="outline">
          <RefreshCw className="mr-2 h-4 w-4" />
          刷新
        </Button>
      }
    >
      {alarms.length === 0 ? (
        <Card className="border-slate-200 shadow-sm">
          <CardContent className="px-4 py-10 text-center text-sm text-slate-500">
            暂无报警记录
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-3">
          {alarms.map((alarm) => (
            <Card key={alarm.id} className="border-slate-200 shadow-sm">
              <CardContent className="space-y-4 px-4 py-4">
                <div className="flex items-start gap-3">
                  <div className="mt-1 flex h-10 w-10 items-center justify-center rounded-2xl bg-red-50 text-red-500">
                    <AlertTriangle className="h-5 w-5" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <p className="truncate font-medium text-slate-900">
                        {alarm.deviceId || "未知设备"}
                      </p>
                      <Badge variant="secondary">
                        {alarm.alarmMethod === "AI_DETECTION"
                          ? "AI侦测"
                          : alarm.alarmMethod || "报警"}
                      </Badge>
                    </div>
                    <p className="mt-2 text-sm text-slate-500">
                      通道：{alarm.channelId || "未记录"}
                    </p>
                    <p className="mt-1 text-sm text-slate-600">
                      {alarm.description || "暂无描述"}
                    </p>
                    <p className="mt-2 text-xs text-slate-400">
                      {alarm.alarmTime || formatDateTime(alarm.createdAt)}
                    </p>
                  </div>
                </div>

                {alarm.snapshotUrl ? (
                  <div className="overflow-hidden rounded-2xl border border-slate-200 bg-slate-50">
                    <button
                      className="block w-full text-left"
                      onClick={() => setSelectedSnapshot(alarm.snapshotUrl || null)}
                      type="button"
                    >
                      <img
                        alt="报警快照"
                        className="h-44 w-full object-cover"
                        src={alarm.snapshotUrl}
                      />
                    </button>
                    <div className="flex items-center justify-between px-4 py-3">
                      <span className="text-sm text-slate-500">点击查看大图</span>
                      <Button
                        onClick={() => setSelectedSnapshot(alarm.snapshotUrl || null)}
                        size="sm"
                        variant="outline"
                      >
                        <ImageIcon className="mr-2 h-4 w-4" />
                        查看
                      </Button>
                    </div>
                  </div>
                ) : null}
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      <Dialog
        onOpenChange={(open) => {
          if (!open) {
            setSelectedSnapshot(null);
          }
        }}
        open={!!selectedSnapshot}
      >
        <DialogContent className="max-w-[calc(100%-1.5rem)] rounded-2xl p-4 sm:max-w-xl">
          <DialogHeader>
            <DialogTitle>报警快照</DialogTitle>
          </DialogHeader>
          {selectedSnapshot ? (
            <img
              alt="报警快照"
              className="max-h-[70vh] w-full rounded-xl object-contain"
              src={selectedSnapshot}
            />
          ) : null}
        </DialogContent>
      </Dialog>
    </MobilePage>
  );
}
