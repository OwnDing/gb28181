import { Link, useLocation, useNavigate } from "react-router";
import {
  Bell,
  Database,
  History,
  LogOut,
  Radio,
  ShieldCheck,
} from "lucide-react";
import { toast } from "sonner";
import { Button } from "../../components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "../../components/ui/card";
import { authApi } from "../../lib/api";
import { clearToken } from "../../lib/http";
import MobilePage from "../components/mobile-page";
import { openNativePushCenter } from "../lib/native-actions";
import { appendShellSearch, isRunningInNativeShell } from "../lib/native-shell";

const menuItems = [
  {
    title: "录像回放",
    description: "进入移动端回放规划页，后续补齐时间轴与播放器体验。",
    to: "/m/playback",
    icon: History,
  },
  {
    title: "存储设置",
    description: "查看当前容量策略与存储占用，首版先提供只读视图。",
    to: "/m/settings",
    icon: Database,
  },
  {
    title: "GB28181 工具",
    description: "查看国标能力规划与下一阶段高级操作入口。",
    to: "/m/gb28181",
    icon: Radio,
  },
];

export default function MobileMore() {
  const navigate = useNavigate();
  const location = useLocation();
  const inNativeShell = isRunningInNativeShell(location.search);
  const username = localStorage.getItem("username") || "用户";
  const role = localStorage.getItem("role") || "ADMIN";

  const handleLogout = async () => {
    try {
      await authApi.logout();
    } catch {
      // ignore logout failures
    }
    clearToken();
    localStorage.removeItem("username");
    localStorage.removeItem("role");
    toast.success("已退出登录");
    navigate(appendShellSearch("/m/login", location.search), { replace: true });
  };

  return (
    <MobilePage
      title="更多"
      description="这里收纳低频管理功能，保持首页和底部导航的高频操作足够简洁。"
    >
      <Card className="border-slate-200 shadow-sm">
        <CardHeader className="pb-2">
          <CardTitle className="text-base">当前登录</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex items-center gap-3 rounded-2xl bg-slate-50 px-4 py-4">
            <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-primary/10 text-primary">
              <ShieldCheck className="h-5 w-5" />
            </div>
            <div>
              <p className="font-medium text-slate-900">{username}</p>
              <p className="text-sm text-slate-500">角色：{role}</p>
            </div>
          </div>
        </CardContent>
      </Card>

      {inNativeShell ? (
        <Button
          className="w-full"
          onClick={() => {
            openNativePushCenter();
          }}
          variant="outline"
        >
          <Bell className="mr-2 h-4 w-4" />
          打开原生消息中心
        </Button>
      ) : null}

      <Card className="border-slate-200 shadow-sm">
        <CardHeader className="pb-2">
          <CardTitle className="text-base">功能入口</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {menuItems.map((item) => (
            <Link
              key={item.to}
              className="flex items-center gap-3 rounded-2xl border border-slate-200 px-4 py-4 transition-colors hover:border-primary/40 hover:bg-primary/5"
              to={appendShellSearch(item.to, location.search)}
            >
              <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-slate-100 text-slate-700">
                <item.icon className="h-5 w-5" />
              </div>
              <div className="min-w-0">
                <p className="font-medium text-slate-900">{item.title}</p>
                <p className="mt-1 text-sm text-slate-500">{item.description}</p>
              </div>
            </Link>
          ))}
        </CardContent>
      </Card>

      <Button className="w-full" onClick={handleLogout} variant="outline">
        <LogOut className="mr-2 h-4 w-4" />
        退出登录
      </Button>
    </MobilePage>
  );
}
