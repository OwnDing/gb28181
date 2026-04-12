import { useEffect, useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { ShieldCheck, Video } from "lucide-react";
import { toast } from "sonner";
import { Button } from "../../components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { authApi } from "../../lib/api";
import { getToken, setToken } from "../../lib/http";

export default function MobileLogin() {
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (getToken()) {
      navigate("/m/home", { replace: true });
    }
  }, [navigate]);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!username || !password) {
      toast.error("请输入用户名和密码");
      return;
    }

    try {
      setSubmitting(true);
      const result = await authApi.login({ username, password });
      setToken(result.token);
      localStorage.setItem("username", result.username);
      localStorage.setItem("role", result.role);
      toast.success("登录成功");
      navigate("/m/home", { replace: true });
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "登录失败");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-950 px-4 py-10">
      <div className="mx-auto flex min-h-[calc(100vh-5rem)] max-w-md flex-col justify-center">
        <div className="mb-8 space-y-4 text-white">
          <div className="inline-flex h-14 w-14 items-center justify-center rounded-2xl bg-white/10 backdrop-blur">
            <Video className="h-7 w-7" />
          </div>
          <div>
            <p className="text-sm uppercase tracking-[0.24em] text-slate-400">
              GB28181 Mobile
            </p>
            <h1 className="mt-2 text-3xl font-semibold">
              视频巡检 App
            </h1>
            <p className="mt-3 text-sm leading-6 text-slate-300">
              使用现有账号登录，快速查看设备状态、实时预览与智能报警。
            </p>
          </div>
        </div>

        <Card className="border-slate-800 bg-white shadow-2xl shadow-slate-950/30">
          <CardHeader className="space-y-2">
            <CardTitle className="flex items-center gap-2 text-lg">
              <ShieldCheck className="h-5 w-5 text-primary" />
              移动端登录
            </CardTitle>
          </CardHeader>
          <CardContent>
            <form className="space-y-4" onSubmit={handleSubmit}>
              <div className="space-y-2">
                <Label htmlFor="mobile-username">用户名</Label>
                <Input
                  id="mobile-username"
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  placeholder="请输入用户名"
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="mobile-password">密码</Label>
                <Input
                  id="mobile-password"
                  type="password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  placeholder="请输入密码"
                />
              </div>
              <Button className="w-full" disabled={submitting} type="submit">
                {submitting ? "登录中..." : "登录进入 App"}
              </Button>
            </form>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
