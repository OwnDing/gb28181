import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useLocation } from "react-router";
import {
  AlertTriangle,
  ChevronRight,
  HardDrive,
  Monitor,
  RefreshCw,
  Video,
} from "lucide-react";
import { toast } from "sonner";
import { Button } from "../../components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "../../components/ui/card";
import { Badge } from "../../components/ui/badge";
import { alarmApi, deviceApi, storageApi, type GbAlarmEvent } from "../../lib/api";
import MobilePage from "../components/mobile-page";
import { appendShellSearch } from "../lib/native-shell";

type DashboardState = {
  totalDevices: number;
  onlineDevices: number;
  usagePercent: number;
  usedGb: number;
  maxStorageGb: number;
  recentAlarms: GbAlarmEvent[];
};

const EMPTY_STATE: DashboardState = {
  totalDevices: 0,
  onlineDevices: 0,
  usagePercent: 0,
  usedGb: 0,
  maxStorageGb: 0,
  recentAlarms: [],
};

function formatDateTime(value?: string | null) {
  if (!value) {
    return "暂无";
  }
  return new Date(value).toLocaleString("zh-CN");
}

export default function MobileHome() {
  const location = useLocation();
  const [loading, setLoading] = useState(true);
  const [state, setState] = useState<DashboardState>(EMPTY_STATE);

  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      const [devicesResult, alarmsResult, usageResult] = await Promise.allSettled([
        deviceApi.list(),
        alarmApi.list({ limit: 5 }),
        storageApi.usage(),
      ]);

      const devices =
        devicesResult.status === "fulfilled" ? devicesResult.value : [];
      const alarms =
        alarmsResult.status === "fulfilled" ? alarmsResult.value : [];
      const usage =
        usageResult.status === "fulfilled" ? usageResult.value : null;

      if (
        devicesResult.status === "rejected" ||
        alarmsResult.status === "rejected" ||
        usageResult.status === "rejected"
      ) {
        toast.error("部分数据加载失败，已展示可用内容");
      }

      setState({
        totalDevices: devices.length,
        onlineDevices: devices.filter((item) => item.online).length,
        usagePercent: usage?.usagePercent ?? 0,
        usedGb: usage?.usedGb ?? 0,
        maxStorageGb: usage?.maxStorageGb ?? 0,
        recentAlarms: alarms,
      });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const statItems = useMemo(
    () => [
      {
        label: "设备总数",
        value: `${state.totalDevices}`,
        hint: `在线 ${state.onlineDevices} 台`,
        icon: Monitor,
      },
      {
        label: "在线率",
        value:
          state.totalDevices > 0
            ? `${Math.round((state.onlineDevices / state.totalDevices) * 100)}%`
            : "0%",
        hint: "按当前注册设备计算",
        icon: Video,
      },
      {
        label: "最近报警",
        value: `${state.recentAlarms.length}`,
        hint: "显示最近 5 条",
        icon: AlertTriangle,
      },
      {
        label: "存储占用",
        value: `${state.usagePercent.toFixed(1)}%`,
        hint: `${state.usedGb.toFixed(1)} / ${state.maxStorageGb.toFixed(0)} GB`,
        icon: HardDrive,
      },
    ],
    [state],
  );

  const quickLinks = [
    {
      title: "设备巡检",
      description: "查看在线状态与设备基础信息",
      to: "/m/devices",
    },
    {
      title: "实时预览",
      description: "打开单路实时画面并切换协议",
      to: "/m/preview",
    },
    {
      title: "报警中心",
      description: "查看最近智能报警与快照",
      to: "/m/alarms",
    },
    {
      title: "更多功能",
      description: "进入回放、存储和 GB28181 工具",
      to: "/m/more",
    },
  ];

  return (
    <MobilePage
      title="移动工作台"
      description="首页聚合设备、报警和存储信息，方便快速巡检。"
      action={
        <Button disabled={loading} onClick={loadData} size="sm" variant="outline">
          <RefreshCw className="mr-2 h-4 w-4" />
          刷新
        </Button>
      }
    >
      <div className="grid grid-cols-2 gap-3">
        {statItems.map((item) => (
          <Card key={item.label} className="border-slate-200 shadow-sm">
            <CardContent className="space-y-3 px-4 py-4">
              <div className="flex items-center justify-between">
                <p className="text-xs text-slate-500">{item.label}</p>
                <item.icon className="h-4 w-4 text-primary" />
              </div>
              <div>
                <p className="text-2xl font-semibold text-slate-900">
                  {loading ? "--" : item.value}
                </p>
                <p className="mt-1 text-xs text-slate-500">{item.hint}</p>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      <Card className="border-slate-200 shadow-sm">
        <CardHeader className="pb-2">
          <CardTitle className="text-base">快捷入口</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {quickLinks.map((item) => (
            <Link
              key={item.to}
              className="flex items-center justify-between rounded-2xl border border-slate-200 px-4 py-4 transition-colors hover:border-primary/40 hover:bg-primary/5"
              to={appendShellSearch(item.to, location.search)}
            >
              <div>
                <p className="font-medium text-slate-900">{item.title}</p>
                <p className="mt-1 text-sm text-slate-500">{item.description}</p>
              </div>
              <ChevronRight className="h-5 w-5 text-slate-400" />
            </Link>
          ))}
        </CardContent>
      </Card>

      <Card className="border-slate-200 shadow-sm">
        <CardHeader className="pb-2">
          <CardTitle className="text-base">最近报警</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {state.recentAlarms.length === 0 ? (
            <div className="rounded-2xl border border-dashed border-slate-200 px-4 py-8 text-center text-sm text-slate-500">
              暂无最近报警记录
            </div>
          ) : (
            state.recentAlarms.map((alarm) => (
              <Link
                key={alarm.id}
                className="flex items-start gap-3 rounded-2xl border border-slate-200 px-4 py-4 transition-colors hover:border-primary/40 hover:bg-primary/5"
                to={appendShellSearch("/m/alarms", location.search)}
              >
                <div className="mt-1 flex h-10 w-10 items-center justify-center rounded-full bg-red-50 text-red-500">
                  <AlertTriangle className="h-5 w-5" />
                </div>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <p className="truncate font-medium text-slate-900">
                      {alarm.deviceId || "未知设备"}
                    </p>
                    <Badge variant="secondary">
                      {alarm.alarmMethod === "AI_DETECTION" ? "AI侦测" : "报警"}
                    </Badge>
                  </div>
                  <p className="mt-1 line-clamp-2 text-sm text-slate-500">
                    {alarm.description || "暂无描述"}
                  </p>
                  <p className="mt-2 text-xs text-slate-400">
                    {alarm.alarmTime || formatDateTime(alarm.createdAt)}
                  </p>
                </div>
              </Link>
            ))
          )}
        </CardContent>
      </Card>
    </MobilePage>
  );
}
