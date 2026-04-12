import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Link, useLocation } from "react-router";
import {
  Monitor,
  Pencil,
  Plus,
  Power,
  RefreshCw,
  Trash2,
  Video,
} from "lucide-react";
import { toast } from "sonner";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../../components/ui/alert-dialog";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardContent } from "../../components/ui/card";
import {
  Drawer,
  DrawerContent,
  DrawerDescription,
  DrawerFooter,
  DrawerHeader,
  DrawerTitle,
} from "../../components/ui/drawer";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "../../components/ui/select";
import {
  deviceApi,
  type Device,
  type DeviceRequest,
} from "../../lib/api";
import MobilePage from "../components/mobile-page";
import { appendShellSearch } from "../lib/native-shell";

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

function formatDateTime(value?: string | null) {
  if (!value) {
    return "暂无";
  }
  return new Date(value).toLocaleString("zh-CN");
}

function validatePayload(payload: DeviceRequest) {
  if (!payload.name.trim()) {
    return "设备名称不能为空";
  }
  if (!/^\d{20}$/.test(payload.deviceId)) {
    return "设备编码必须是 20 位数字";
  }
  if (!payload.ip.trim()) {
    return "IP 地址不能为空";
  }
  if (!Number.isInteger(payload.port) || payload.port < 1 || payload.port > 65535) {
    return "端口范围必须在 1 到 65535 之间";
  }
  if (
    !Number.isInteger(payload.channelCount) ||
    payload.channelCount < 1 ||
    payload.channelCount > 64
  ) {
    return "通道数范围必须在 1 到 64 之间";
  }
  if (!payload.manufacturer.trim()) {
    return "制造商不能为空";
  }
  return null;
}

function toPayload(formData: DeviceForm): DeviceRequest {
  return {
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
  };
}

function toFormData(device: Device): DeviceForm {
  return {
    name: device.name,
    deviceId: device.deviceId,
    ip: device.ip,
    port: String(device.port),
    transport: device.transport,
    username: device.username ?? "",
    password: device.password ?? "",
    manufacturer: device.manufacturer,
    channelCount: String(device.channelCount),
    preferredCodec: device.preferredCodec,
  };
}

export default function MobileDevices() {
  const location = useLocation();
  const [devices, setDevices] = useState<Device[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [togglingId, setTogglingId] = useState<number | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingDevice, setEditingDevice] = useState<Device | null>(null);
  const [deletingDevice, setDeletingDevice] = useState<Device | null>(null);
  const [formData, setFormData] = useState<DeviceForm>(DEFAULT_FORM);

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

  const openCreateDrawer = () => {
    setEditingDevice(null);
    setFormData(DEFAULT_FORM);
    setDrawerOpen(true);
  };

  const openEditDrawer = (device: Device) => {
    setEditingDevice(device);
    setFormData(toFormData(device));
    setDrawerOpen(true);
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const payload = toPayload(formData);
    const validationError = validatePayload(payload);

    if (validationError) {
      toast.error(validationError);
      return;
    }

    try {
      setSaving(true);
      if (editingDevice) {
        await deviceApi.update(editingDevice.id, payload);
        toast.success("设备更新成功");
      } else {
        await deviceApi.create(payload);
        toast.success("设备注册成功");
      }
      setDrawerOpen(false);
      await loadDevices();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "设备保存失败");
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!deletingDevice) {
      return;
    }

    try {
      await deviceApi.remove(deletingDevice.id);
      toast.success("设备删除成功");
      setDeletingDevice(null);
      await loadDevices();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "设备删除失败");
    }
  };

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
    <>
      <MobilePage
        title="设备管理"
        action={
          <div className="flex items-center gap-2">
            <Button
              disabled={loading}
              onClick={loadDevices}
              size="sm"
              variant="outline"
            >
              <RefreshCw className="h-4 w-4" />
            </Button>
            <Button onClick={openCreateDrawer} size="sm">
              <Plus className="mr-1 h-4 w-4" />
              新增
            </Button>
          </div>
        }
      >
        {loading ? (
          <Card className="border-slate-200 shadow-sm">
            <CardContent className="px-4 py-10 text-center text-sm text-slate-500">
              正在加载设备列表...
            </CardContent>
          </Card>
        ) : devices.length === 0 ? (
          <Card className="border-slate-200 shadow-sm">
            <CardContent className="space-y-4 px-4 py-10 text-center">
              <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-3xl bg-primary/10 text-primary">
                <Monitor className="h-7 w-7" />
              </div>
              <div>
                <p className="font-medium text-slate-900">当前还没有设备</p>
                <p className="mt-2 text-sm text-slate-500">
                  可以直接在移动端录入新的 GB28181 设备。
                </p>
              </div>
              <Button onClick={openCreateDrawer}>
                <Plus className="mr-2 h-4 w-4" />
                新增设备
              </Button>
            </CardContent>
          </Card>
        ) : (
          <div className="space-y-3">
            {devices.map((device) => (
              <Card key={device.id} className="overflow-hidden border-slate-200 shadow-sm">
                <CardContent className="space-y-4 px-4 py-4">
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="flex items-center gap-3">
                        <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-primary/10 text-primary">
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
                    <div className="flex flex-col items-end gap-2">
                      <Badge variant={device.online ? "default" : "secondary"}>
                        {device.online ? "在线" : "离线"}
                      </Badge>
                      <Badge variant="outline">{device.transport}</Badge>
                    </div>
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
                        {device.manufacturer}
                      </p>
                    </div>
                    <div className="rounded-2xl bg-slate-50 px-3 py-3">
                      <p className="text-xs text-slate-500">最后心跳</p>
                      <p className="mt-1 font-medium text-slate-900">
                        {formatDateTime(device.lastSeenAt)}
                      </p>
                    </div>
                  </div>

                  <div className="grid grid-cols-3 gap-2">
                    <Button asChild className="rounded-2xl">
                      <Link
                        to={appendShellSearch(
                          `/m/preview?devicePk=${device.id}`,
                          location.search,
                        )}
                      >
                        <Video className="mr-1 h-4 w-4" />
                        预览
                      </Link>
                    </Button>
                    <Button
                      className="rounded-2xl"
                      onClick={() => openEditDrawer(device)}
                      variant="outline"
                    >
                      <Pencil className="mr-1 h-4 w-4" />
                      编辑
                    </Button>
                    <Button
                      className="rounded-2xl"
                      disabled={togglingId === device.id}
                      onClick={() => toggleStatus(device)}
                      variant="outline"
                    >
                      <Power className="mr-1 h-4 w-4" />
                      {device.online ? "停用" : "启用"}
                    </Button>
                  </div>

                  <button
                    className="w-full rounded-2xl border border-red-100 px-4 py-3 text-sm font-medium text-red-600 transition-colors hover:bg-red-50"
                    onClick={() => setDeletingDevice(device)}
                    type="button"
                  >
                    <span className="inline-flex items-center gap-2">
                      <Trash2 className="h-4 w-4" />
                      删除设备
                    </span>
                  </button>
                </CardContent>
              </Card>
            ))}
          </div>
        )}
      </MobilePage>

      <Drawer
        onOpenChange={(open) => {
          setDrawerOpen(open);
          if (!open) {
            setEditingDevice(null);
          }
        }}
        open={drawerOpen}
      >
        <DrawerContent>
          <DrawerHeader className="text-left">
            <DrawerTitle>{editingDevice ? "编辑设备" : "新增设备"}</DrawerTitle>
            <DrawerDescription>
              {editingDevice
                ? "更新设备的接入信息、通道数和默认编码。"
                : "录入新的 GB28181 设备，保存后可直接在移动端发起预览。"}
            </DrawerDescription>
          </DrawerHeader>

          <div className="max-h-[68vh] overflow-y-auto px-4 pb-4">
            <form className="space-y-4" id="mobile-device-form" onSubmit={handleSubmit}>
              <div className="space-y-2">
                <Label htmlFor="mobile-device-name">设备名称</Label>
                <Input
                  id="mobile-device-name"
                  onChange={(event) =>
                    setFormData((current) => ({
                      ...current,
                      name: event.target.value,
                    }))
                  }
                  placeholder="例如：园区东门摄像头"
                  value={formData.name}
                />
              </div>

              <div className="space-y-2">
                <Label htmlFor="mobile-device-id">GB28181 设备编码</Label>
                <Input
                  id="mobile-device-id"
                  inputMode="numeric"
                  maxLength={20}
                  onChange={(event) =>
                    setFormData((current) => ({
                      ...current,
                      deviceId: event.target.value,
                    }))
                  }
                  placeholder="20 位国标编码"
                  value={formData.deviceId}
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-2">
                  <Label htmlFor="mobile-device-ip">IP 地址</Label>
                  <Input
                    id="mobile-device-ip"
                    onChange={(event) =>
                      setFormData((current) => ({
                        ...current,
                        ip: event.target.value,
                      }))
                    }
                    placeholder="192.168.6.100"
                    value={formData.ip}
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="mobile-device-port">端口</Label>
                  <Input
                    id="mobile-device-port"
                    inputMode="numeric"
                    onChange={(event) =>
                      setFormData((current) => ({
                        ...current,
                        port: event.target.value,
                      }))
                    }
                    placeholder="5060"
                    value={formData.port}
                  />
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-2">
                  <Label>传输方式</Label>
                  <Select
                    onValueChange={(value: "UDP" | "TCP") =>
                      setFormData((current) => ({
                        ...current,
                        transport: value,
                      }))
                    }
                    value={formData.transport}
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
                <div className="space-y-2">
                  <Label>默认编码</Label>
                  <Select
                    onValueChange={(value: "H264" | "H265") =>
                      setFormData((current) => ({
                        ...current,
                        preferredCodec: value,
                      }))
                    }
                    value={formData.preferredCodec}
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

              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-2">
                  <Label htmlFor="mobile-device-channel-count">通道数</Label>
                  <Input
                    id="mobile-device-channel-count"
                    inputMode="numeric"
                    onChange={(event) =>
                      setFormData((current) => ({
                        ...current,
                        channelCount: event.target.value,
                      }))
                    }
                    placeholder="1"
                    value={formData.channelCount}
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="mobile-device-manufacturer">制造商</Label>
                  <Input
                    id="mobile-device-manufacturer"
                    onChange={(event) =>
                      setFormData((current) => ({
                        ...current,
                        manufacturer: event.target.value,
                      }))
                    }
                    placeholder="海康、大华等"
                    value={formData.manufacturer}
                  />
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-2">
                  <Label htmlFor="mobile-device-username">设备用户名</Label>
                  <Input
                    id="mobile-device-username"
                    onChange={(event) =>
                      setFormData((current) => ({
                        ...current,
                        username: event.target.value,
                      }))
                    }
                    placeholder="可选"
                    value={formData.username}
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="mobile-device-password">设备密码</Label>
                  <Input
                    id="mobile-device-password"
                    onChange={(event) =>
                      setFormData((current) => ({
                        ...current,
                        password: event.target.value,
                      }))
                    }
                    placeholder="可选"
                    type="password"
                    value={formData.password}
                  />
                </div>
              </div>
            </form>
          </div>

          <DrawerFooter className="border-t border-slate-200">
            <Button
              disabled={saving}
              form="mobile-device-form"
              type="submit"
            >
              {saving ? "保存中..." : editingDevice ? "保存修改" : "新增设备"}
            </Button>
            <Button
              onClick={() => setDrawerOpen(false)}
              type="button"
              variant="outline"
            >
              取消
            </Button>
          </DrawerFooter>
        </DrawerContent>
      </Drawer>

      <AlertDialog
        onOpenChange={(open) => {
          if (!open) {
            setDeletingDevice(null);
          }
        }}
        open={!!deletingDevice}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除设备</AlertDialogTitle>
            <AlertDialogDescription>
              {deletingDevice
                ? `设备“${deletingDevice.name}”删除后不可恢复，相关通道和预览入口也会失效。`
                : "删除后不可恢复。"}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              className="bg-red-600 hover:bg-red-700"
              onClick={handleDelete}
            >
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
