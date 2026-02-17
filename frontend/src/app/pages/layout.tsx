import { useEffect, useState } from "react";
import { Outlet, useNavigate, NavLink } from "react-router";
import { Video, Monitor, Settings, LogOut, Radio, AlertTriangle, History } from "lucide-react";
import { Button } from "../components/ui/button";
import { authApi } from "../lib/api";
import { clearToken, getToken } from "../lib/http";
import { toast } from "sonner";

export default function Layout() {
  const navigate = useNavigate();
  const [username, setUsername] = useState(localStorage.getItem("username") || "用户");
  const [checking, setChecking] = useState(true);

  useEffect(() => {
    let mounted = true;
    const bootstrap = async () => {
      const token = getToken();
      if (!token) {
        navigate("/login");
        return;
      }
      try {
        const me = await authApi.me();
        if (!mounted) {
          return;
        }
        setUsername(me.username);
        localStorage.setItem("username", me.username);
      } catch {
        clearToken();
        localStorage.removeItem("username");
        navigate("/login");
      } finally {
        if (mounted) {
          setChecking(false);
        }
      }
    };
    bootstrap();
    return () => {
      mounted = false;
    };
  }, [navigate]);

  const handleLogout = async () => {
    try {
      await authApi.logout();
    } catch {
      // ignore
    }
    clearToken();
    localStorage.removeItem("username");
    localStorage.removeItem("role");
    toast.success("已退出登录");
    navigate("/login");
  };

  const navItems = [
    { path: "/devices", label: "设备管理", icon: Monitor },
    { path: "/video-preview", label: "在线预览", icon: Video },
    { path: "/video-playback", label: "录像回看", icon: History },
    { path: "/storage-settings", label: "存储设置", icon: Settings },
    { path: "/gb28181", label: "GB28181能力", icon: Radio },
    { path: "/alarm-history", label: "智能报警", icon: AlertTriangle },
  ];

  if (checking) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-slate-50 text-slate-600">
        正在验证登录状态...
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-slate-50">
      {/* 顶部导航栏 */}
      <header className="bg-white border-b border-slate-200 sticky top-0 z-50">
        <div className="flex items-center justify-between px-6 py-4">
          <div className="flex items-center gap-3">
            <div className="p-2 bg-primary rounded-lg">
              <Video className="w-6 h-6 text-primary-foreground" />
            </div>
            <div>
              <h1 className="font-semibold text-slate-900">GB28181 视频协议平台</h1>
              <p className="text-sm text-slate-500">视频监控管理系统</p>
            </div>
          </div>
          <div className="flex items-center gap-4">
            <span className="text-sm text-slate-600">欢迎，{username}</span>
            <Button variant="outline" size="sm" onClick={handleLogout}>
              <LogOut className="w-4 h-4 mr-2" />
              退出登录
            </Button>
          </div>
        </div>
      </header>

      {/* 主内容区 */}
      <div className="flex">
        {/* 侧边导航栏 */}
        <aside className="w-64 bg-white border-r border-slate-200 min-h-[calc(100vh-73px)]">
          <nav className="p-4 space-y-2">
            {navItems.map((item) => (
              <NavLink
                key={item.path}
                to={item.path}
                className={({ isActive }) =>
                  `flex items-center gap-3 px-4 py-3 rounded-lg transition-colors ${isActive
                    ? "bg-primary text-primary-foreground"
                    : "text-slate-700 hover:bg-slate-100"
                  }`
                }
              >
                <item.icon className="w-5 h-5" />
                <span>{item.label}</span>
              </NavLink>
            ))}
          </nav>
        </aside>

        {/* 页面内容 */}
        <main className="flex-1 p-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
