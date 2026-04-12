import { useEffect, useMemo, useState } from "react";
import { Link, Outlet, useLocation, useNavigate } from "react-router";
import { AlertTriangle, Home, Monitor, UserRound, Video } from "lucide-react";
import { authApi } from "../../lib/api";
import { clearToken, getToken } from "../../lib/http";

type NavItem = {
  label: string;
  to: string;
  icon: typeof Home;
  matcher: (pathname: string) => boolean;
};

const navItems: NavItem[] = [
  {
    label: "首页",
    to: "/m/home",
    icon: Home,
    matcher: (pathname) => pathname === "/m/home" || pathname === "/m",
  },
  {
    label: "设备",
    to: "/m/devices",
    icon: Monitor,
    matcher: (pathname) => pathname.startsWith("/m/devices"),
  },
  {
    label: "预览",
    to: "/m/preview",
    icon: Video,
    matcher: (pathname) => pathname.startsWith("/m/preview"),
  },
  {
    label: "报警",
    to: "/m/alarms",
    icon: AlertTriangle,
    matcher: (pathname) => pathname.startsWith("/m/alarms"),
  },
  {
    label: "更多",
    to: "/m/more",
    icon: UserRound,
    matcher: (pathname) =>
      pathname.startsWith("/m/more") ||
      pathname.startsWith("/m/playback") ||
      pathname.startsWith("/m/settings") ||
      pathname.startsWith("/m/gb28181"),
  },
];

export default function MobileShell() {
  const navigate = useNavigate();
  const location = useLocation();
  const [checking, setChecking] = useState(true);
  const [username, setUsername] = useState(
    localStorage.getItem("username") || "用户",
  );

  useEffect(() => {
    let mounted = true;

    const bootstrap = async () => {
      const token = getToken();
      if (!token) {
        navigate("/m/login", { replace: true });
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
        localStorage.removeItem("role");
        navigate("/m/login", { replace: true });
        return;
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

  const userInitial = useMemo(
    () => username.trim().slice(0, 1).toUpperCase() || "U",
    [username],
  );

  if (checking) {
    return (
      <div className="min-h-screen bg-slate-100">
        <div className="mx-auto flex min-h-screen max-w-screen-md items-center justify-center bg-white px-6 text-center text-sm text-slate-500">
          正在验证移动端登录状态...
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-slate-100">
      <div className="mx-auto flex min-h-screen max-w-screen-md flex-col bg-white shadow-sm">
        <header
          className="sticky top-0 z-40 border-b border-slate-200 bg-white/95 backdrop-blur"
          style={{ paddingTop: "max(env(safe-area-inset-top), 0px)" }}
        >
          <div className="flex items-center justify-between px-4 py-3">
            <div className="min-w-0">
              <p className="text-xs uppercase tracking-[0.2em] text-slate-400">
                GB28181 APP
              </p>
              <p className="truncate text-sm font-medium text-slate-900">
                视频巡检与管理
              </p>
            </div>
            <div className="flex items-center gap-3">
              <span className="hidden text-xs text-slate-500 sm:inline">
                {username}
              </span>
              <div className="flex h-9 w-9 items-center justify-center rounded-full bg-primary/10 text-sm font-semibold text-primary">
                {userInitial}
              </div>
            </div>
          </div>
        </header>

        <main
          className="flex-1 px-4 py-4"
          style={{ paddingBottom: "calc(88px + env(safe-area-inset-bottom))" }}
        >
          <Outlet />
        </main>
      </div>

      <nav
        className="fixed inset-x-0 bottom-0 z-50 border-t border-slate-200 bg-white/95 backdrop-blur"
        style={{ paddingBottom: "max(env(safe-area-inset-bottom), 0px)" }}
      >
        <div className="mx-auto grid max-w-screen-md grid-cols-5">
          {navItems.map((item) => {
            const active = item.matcher(location.pathname);
            return (
              <Link
                key={item.to}
                to={item.to}
                className={`flex flex-col items-center justify-center gap-1 px-2 py-3 text-xs transition-colors ${
                  active
                    ? "text-primary"
                    : "text-slate-500 hover:text-slate-900"
                }`}
              >
                <item.icon className="h-5 w-5" />
                <span>{item.label}</span>
              </Link>
            );
          })}
        </div>
      </nav>
    </div>
  );
}
