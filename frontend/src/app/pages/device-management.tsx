import { useCallback, useEffect, useState } from "react";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "../components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "../components/ui/dialog";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "../components/ui/table";
import { Badge } from "../components/ui/badge";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "../components/ui/select";
import { Plus, Pencil, Trash2, Power } from "lucide-react";
import { toast } from "sonner";
import { deviceApi, type Device, type DeviceRequest } from "../lib/api";

type DeviceForm = {
  name: string;
  deviceId: string;
  ip: string;
  port: string;
  transport: "UDP" | "TCP";
  username: string;
  password: string;
  manufacturer: string;
  channelCount: string;
  preferredCodec: "H264" | "H265";
};

const DEFAULT_FORM: DeviceForm = {
  name: "",
  deviceId: "",
  ip: "",
  port: "5060",
  transport: "UDP",
  username: "",
  password: "",
  manufacturer: "",
  channelCount: "1",
  preferredCodec: "H264",
};

export default function DeviceManagement() {
  const [devices, setDevices] = useState<Device[]>([]);
  const [loading, setLoading] = useState(true);
  const [isDialogOpen, setIsDialogOpen] = useState(false);
  const [editingDevice, setEditingDevice] = useState<Device | null>(null);
  const [saving, setSaving] = useState(false);
  const [formData, setFormData] = useState<DeviceForm>(DEFAULT_FORM);

  const loadDevices = useCallback(async () => {
    try {
      setLoading(true);
      const list = await deviceApi.list();
      setDevices(list);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "设备加载失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadDevices();
  }, [loadDevices]);

  const handleOpenDialog = (device?: Device) => {
    if (device) {
      setEditingDevice(device);
      setFormData({
        name: device.name,
        deviceId: device.deviceId,
        ip: device.ip,
        port: device.port.toString(),
        transport: device.transport,
        username: device.username ?? "",
        password: device.password ?? "",
        manufacturer: device.manufacturer,
        channelCount: device.channelCount.toString(),
        preferredCodec: device.preferredCodec,
      });
    } else {
      setEditingDevice(null);
      setFormData(DEFAULT_FORM);
    }
    setIsDialogOpen(true);
  };

  const toPayload = (): DeviceRequest => ({
    name: formData.name.trim(),
    deviceId: formData.deviceId.trim(),
    ip: formData.ip.trim(),
    port: Number(formData.port),
    transport: formData.transport,
    username: formData.username.trim() || undefined,
    password: formData.password.trim() || undefined,
    manufacturer: formData.manufacturer.trim(),
    channelCount: Number(formData.channelCount),
    preferredCodec: formData.preferredCodec,
  });

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      setSaving(true);
      const payload = toPayload();
      if (editingDevice) {
        await deviceApi.update(editingDevice.id, payload);
        toast.success("设备更新成功");
      } else {
        await deviceApi.create(payload);
        toast.success("设备注册成功");
      }
      setIsDialogOpen(false);
      await loadDevices();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "保存失败");
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id: number) => {
    const confirmed = window.confirm("确定删除该设备吗？");
    if (!confirmed) {
      return;
    }
    try {
      await deviceApi.remove(id);
      toast.success("设备删除成功");
      await loadDevices();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "删除失败");
    }
  };

  const toggleDeviceStatus = async (device: Device) => {
    try {
      await deviceApi.updateStatus(device.id, !device.online);
      await loadDevices();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "状态更新失败");
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-3xl font-semibold text-slate-900">设备管理</h2>
          <p className="text-slate-500 mt-1">管理 GB28181 视频设备</p>
        </div>
        <Button onClick={() => handleOpenDialog()}>
          <Plus className="w-4 h-4 mr-2" />
          注册设备
        </Button>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>设备列表</CardTitle>
          <CardDescription>当前注册的视频设备 ({devices.length} 台)</CardDescription>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>设备名称</TableHead>
                <TableHead>GB28181编码</TableHead>
                <TableHead>IP地址</TableHead>
                <TableHead>端口</TableHead>
                <TableHead>通道数</TableHead>
                <TableHead>制造商</TableHead>
                <TableHead>编码</TableHead>
                <TableHead>状态</TableHead>
                <TableHead>最后心跳</TableHead>
                <TableHead className="text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {loading ? (
                <TableRow>
                  <TableCell colSpan={10} className="text-center text-slate-500 py-8">
                    正在加载...
                  </TableCell>
                </TableRow>
              ) : devices.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={10} className="text-center text-slate-500 py-8">
                    暂无设备，请点击"注册设备"添加
                  </TableCell>
                </TableRow>
              ) : (
                devices.map((device) => (
                  <TableRow key={device.id}>
                    <TableCell className="font-medium">{device.name}</TableCell>
                    <TableCell className="font-mono text-sm">{device.deviceId}</TableCell>
                    <TableCell>{device.ip}</TableCell>
                    <TableCell>{device.port}</TableCell>
                    <TableCell>{device.channelCount}</TableCell>
                    <TableCell>{device.manufacturer}</TableCell>
                    <TableCell>{device.preferredCodec}</TableCell>
                    <TableCell>
                      <Badge
                        variant={device.online ? "default" : "secondary"}
                        className="cursor-pointer"
                        onClick={() => toggleDeviceStatus(device)}
                      >
                        <Power className="w-3 h-3 mr-1" />
                        {device.online ? "在线" : "离线"}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-sm text-slate-500">
                      {device.lastSeenAt ? new Date(device.lastSeenAt).toLocaleString("zh-CN") : "-"}
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-2">
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => handleOpenDialog(device)}
                        >
                          <Pencil className="w-4 h-4" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => handleDelete(device.id)}
                        >
                          <Trash2 className="w-4 h-4 text-destructive" />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Dialog open={isDialogOpen} onOpenChange={setIsDialogOpen}>
        <DialogContent className="sm:max-w-[620px]">
          <DialogHeader>
            <DialogTitle>{editingDevice ? "编辑设备" : "注册新设备"}</DialogTitle>
            <DialogDescription>
              {editingDevice ? "修改设备信息" : "填写设备信息以注册到 GB28181 平台"}
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={handleSubmit}>
            <div className="grid gap-4 py-4">
              <div className="grid gap-2">
                <Label htmlFor="name">设备名称</Label>
                <Input
                  id="name"
                  placeholder="例如：监控摄像头-大门"
                  value={formData.name}
                  onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                  required
                />
              </div>
              <div className="grid gap-2">
                <Label htmlFor="deviceId">GB28181 设备编码</Label>
                <Input
                  id="deviceId"
                  placeholder="20位国标编码"
                  value={formData.deviceId}
                  onChange={(e) => setFormData({ ...formData, deviceId: e.target.value })}
                  maxLength={20}
                  required
                />
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div className="grid gap-2">
                  <Label htmlFor="ip">IP地址</Label>
                  <Input
                    id="ip"
                    placeholder="192.168.1.100"
                    value={formData.ip}
                    onChange={(e) => setFormData({ ...formData, ip: e.target.value })}
                    required
                  />
                </div>
                <div className="grid gap-2">
                  <Label htmlFor="port">端口</Label>
                  <Input
                    id="port"
                    type="number"
                    min="1"
                    max="65535"
                    value={formData.port}
                    onChange={(e) => setFormData({ ...formData, port: e.target.value })}
                    required
                  />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div className="grid gap-2">
                  <Label htmlFor="channelCount">通道数</Label>
                  <Input
                    id="channelCount"
                    type="number"
                    min="1"
                    max="64"
                    value={formData.channelCount}
                    onChange={(e) => setFormData({ ...formData, channelCount: e.target.value })}
                    required
                  />
                </div>
                <div className="grid gap-2">
                  <Label htmlFor="manufacturer">制造商</Label>
                  <Input
                    id="manufacturer"
                    placeholder="例如：海康威视"
                    value={formData.manufacturer}
                    onChange={(e) => setFormData({ ...formData, manufacturer: e.target.value })}
                    required
                  />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div className="grid gap-2">
                  <Label>传输方式</Label>
                  <Select
                    value={formData.transport}
                    onValueChange={(value: "UDP" | "TCP") =>
                      setFormData({ ...formData, transport: value })
                    }
                  >
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="UDP">UDP</SelectItem>
                      <SelectItem value="TCP">TCP</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                <div className="grid gap-2">
                  <Label>默认编码</Label>
                  <Select
                    value={formData.preferredCodec}
                    onValueChange={(value: "H264" | "H265") =>
                      setFormData({ ...formData, preferredCodec: value })
                    }
                  >
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="H264">H.264</SelectItem>
                      <SelectItem value="H265">H.265</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div className="grid gap-2">
                  <Label htmlFor="username">设备用户名（可选）</Label>
                  <Input
                    id="username"
                    value={formData.username}
                    onChange={(e) => setFormData({ ...formData, username: e.target.value })}
                  />
                </div>
                <div className="grid gap-2">
                  <Label htmlFor="password">设备密码（可选）</Label>
                  <Input
                    id="password"
                    type="password"
                    value={formData.password}
                    onChange={(e) => setFormData({ ...formData, password: e.target.value })}
                  />
                </div>
              </div>
            </div>
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setIsDialogOpen(false)}
              >
                取消
              </Button>
              <Button type="submit" disabled={saving}>
                {saving ? "保存中..." : editingDevice ? "更新" : "注册"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
