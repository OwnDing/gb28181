import { useCallback, useEffect, useState } from "react";
import { CalendarClock, RefreshCw } from "lucide-react";
import { toast } from "sonner";
import { Button } from "../../components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "../../components/ui/card";
import { playbackApi, type PlaybackChannel } from "../../lib/api";
import MobilePage from "../components/mobile-page";

export default function MobilePlayback() {
  const [loading, setLoading] = useState(true);
  const [channels, setChannels] = useState<PlaybackChannel[]>([]);

  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      const data = await playbackApi.channels();
      setChannels(data);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "回放通道加载失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  return (
    <MobilePage
      title="录像回放"
      description="移动端回放会在下一阶段补齐时间轴与播放器交互，这里先把数据入口和规划页接起来。"
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
            <CalendarClock className="h-5 w-5 text-primary" />
            当前回放准备情况
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-3 text-sm text-slate-600">
          <p>已识别可回放通道数：{loading ? "--" : channels.length}</p>
          <p>
            下一步会在移动端实现日期选择、时间轴定位、片段列表和播放器控制。
          </p>
        </CardContent>
      </Card>

      <div className="space-y-3">
        {channels.slice(0, 8).map((channel) => (
          <Card key={channel.channelId} className="border-slate-200 shadow-sm">
            <CardContent className="px-4 py-4">
              <p className="font-medium text-slate-900">{channel.channelId}</p>
              <p className="mt-1 text-sm text-slate-500">
                当前发现 {channel.fileCount} 个录像文件
              </p>
            </CardContent>
          </Card>
        ))}
      </div>
    </MobilePage>
  );
}
