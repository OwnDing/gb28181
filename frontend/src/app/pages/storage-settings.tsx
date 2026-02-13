import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "../components/ui/card";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Progress } from "../components/ui/progress";
import { Switch } from "../components/ui/switch";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "../components/ui/table";
import { HardDrive, Calendar, Database, Trash2, RefreshCw } from "lucide-react";
import { toast } from "sonner";
import { storageApi, type RecordFileItem, type StoragePolicy, type StorageUsage } from "../lib/api";

type EditablePolicy = {
  retentionDays: number;
  maxStorageGb: number;
  autoOverwrite: boolean;
  recordEnabled: boolean;
  recordPath: string;
};

export default function StorageSettings() {
  const [policy, setPolicy] = useState<StoragePolicy | null>(null);
  const [draft, setDraft] = useState<EditablePolicy>({
    retentionDays: 7,
    maxStorageGb: 100,
    autoOverwrite: true,
    recordEnabled: true,
    recordPath: "./data/records",
  });
  const [usage, setUsage] = useState<StorageUsage | null>(null);
  const [records, setRecords] = useState<RecordFileItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      const [policyData, usageData, recordsData] = await Promise.all([
        storageApi.getPolicy(),
        storageApi.usage(),
        storageApi.records(),
      ]);
      setPolicy(policyData);
      setDraft({
        retentionDays: policyData.retentionDays,
        maxStorageGb: policyData.maxStorageGb,
        autoOverwrite: policyData.autoOverwrite,
        recordEnabled: policyData.recordEnabled,
        recordPath: policyData.recordPath,
      });
      setUsage(usageData);
      setRecords(recordsData);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "加载存储设置失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const usagePercent = useMemo(() => usage?.usagePercent ?? 0, [usage]);

  const estimatedDays = useMemo(() => {
    if (!usage) {
      return 0;
    }
    if (draft.maxStorageGb <= 0) {
      return 0;
    }
    const avgDailyUsage = usage.usedGb > 0 ? usage.usedGb / Math.max(draft.retentionDays, 1) : 0;
    if (avgDailyUsage <= 0) {
      return draft.retentionDays;
    }
    return Math.floor(draft.maxStorageGb / avgDailyUsage);
  }, [draft.maxStorageGb, draft.retentionDays, usage]);

  const handleSave = async () => {
    try {
      setSaving(true);
      const updated = await storageApi.updatePolicy(draft);
      setPolicy(updated);
      toast.success("存储设置已保存");
      await loadData();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "保存失败");
    } finally {
      setSaving(false);
    }
  };

  const handleCleanup = async () => {
    try {
      const updatedUsage = await storageApi.cleanup();
      setUsage(updatedUsage);
      const refreshed = await storageApi.records();
      setRecords(refreshed);
      toast.success("清理任务已执行");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "执行清理失败");
    }
  };

  const handleDeleteRecord = async (id: number) => {
    try {
      await storageApi.deleteRecord(id);
      toast.success("录像删除成功");
      await loadData();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "删除录像失败");
    }
  };

  if (loading) {
    return (
      <div className="text-slate-600">
        正在加载存储设置...
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-3xl font-semibold text-slate-900">存储设置</h2>
          <p className="text-slate-500 mt-1">配置视频录像存储策略</p>
        </div>
        <Button variant="outline" onClick={loadData}>
          <RefreshCw className="w-4 h-4 mr-2" />
          刷新
        </Button>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-base flex items-center gap-2">
              <HardDrive className="w-5 h-5" />
              存储使用情况
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div>
              <div className="flex justify-between text-sm mb-2">
                <span className="text-slate-500">已使用</span>
                <span className="font-semibold">
                  {(usage?.usedGb ?? 0).toFixed(2)} GB / {draft.maxStorageGb} GB
                </span>
              </div>
              <Progress value={usagePercent} className="h-2" />
              <p className="text-xs text-slate-500 mt-1">
                {usagePercent.toFixed(1)}% 已使用
              </p>
            </div>
            <div className="pt-3 border-t space-y-2">
              <div className="flex justify-between text-sm">
                <span className="text-slate-500">录像文件数</span>
                <span>{usage?.fileCount ?? 0}</span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-slate-500">最早录像</span>
                <span>
                  {usage?.oldestFileTime ? new Date(usage.oldestFileTime).toLocaleString("zh-CN") : "-"}
                </span>
              </div>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-base flex items-center gap-2">
              <Calendar className="w-5 h-5" />
              预计保留时间
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-center py-4">
              <div className="text-4xl font-bold text-primary">{estimatedDays}</div>
              <p className="text-slate-500 mt-2">天（估算）</p>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-base flex items-center gap-2">
              <Database className="w-5 h-5" />
              当前策略
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3 py-2">
            <div>
              <p className="text-sm text-slate-500">保留天数</p>
              <p className="font-medium mt-1">{policy?.retentionDays ?? 0} 天</p>
            </div>
            <div>
              <p className="text-sm text-slate-500">容量上限</p>
              <p className="font-medium mt-1">{policy?.maxStorageGb ?? 0} GB</p>
            </div>
            <div>
              <p className="text-sm text-slate-500">自动覆盖</p>
              <p className="font-medium mt-1">{policy?.autoOverwrite ? "已启用" : "已禁用"}</p>
            </div>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>存储策略配置</CardTitle>
          <CardDescription>设置录像保留规则和容量上限</CardDescription>
        </CardHeader>
        <CardContent className="space-y-6">
          <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
            <div className="space-y-2">
              <Label htmlFor="retentionDays">保留天数</Label>
              <Input
                id="retentionDays"
                type="number"
                min="1"
                max="3650"
                value={draft.retentionDays}
                onChange={(e) =>
                  setDraft({
                    ...draft,
                    retentionDays: Number(e.target.value || 1),
                  })
                }
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="maxStorageGb">最大容量 (GB)</Label>
              <Input
                id="maxStorageGb"
                type="number"
                min="1"
                max="100000"
                value={draft.maxStorageGb}
                onChange={(e) =>
                  setDraft({
                    ...draft,
                    maxStorageGb: Number(e.target.value || 1),
                  })
                }
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="recordPath">录像目录</Label>
              <Input
                id="recordPath"
                value={draft.recordPath}
                onChange={(e) => setDraft({ ...draft, recordPath: e.target.value })}
              />
            </div>
          </div>

          <div className="flex flex-col md:flex-row md:items-center gap-6">
            <div className="flex items-center gap-3">
              <Switch
                checked={draft.autoOverwrite}
                onCheckedChange={(checked) => setDraft({ ...draft, autoOverwrite: checked })}
              />
              <div>
                <div className="font-medium">自动覆盖最早录像</div>
                <div className="text-sm text-slate-500">容量超限时自动删除最早文件</div>
              </div>
            </div>
            <div className="flex items-center gap-3">
              <Switch
                checked={draft.recordEnabled}
                onCheckedChange={(checked) => setDraft({ ...draft, recordEnabled: checked })}
              />
              <div>
                <div className="font-medium">启用录像</div>
                <div className="text-sm text-slate-500">关闭后仅预览不录制</div>
              </div>
            </div>
          </div>

          <div className="flex gap-3 pt-4 border-t">
            <Button onClick={handleSave} disabled={saving}>
              {saving ? "保存中..." : "保存设置"}
            </Button>
            <Button variant="outline" onClick={handleCleanup}>
              立即清理
            </Button>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>录像文件</CardTitle>
          <CardDescription>当前索引的录像文件（最近 30 条）</CardDescription>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>文件路径</TableHead>
                <TableHead>大小</TableHead>
                <TableHead>时间</TableHead>
                <TableHead className="text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {records.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={4} className="text-center text-slate-500 py-8">
                    暂无录像文件
                  </TableCell>
                </TableRow>
              ) : (
                records.slice(0, 30).map((item) => (
                  <TableRow key={item.id}>
                    <TableCell className="font-mono text-xs max-w-[560px] truncate">
                      {item.filePath}
                    </TableCell>
                    <TableCell>{(item.fileSizeBytes / 1024 / 1024).toFixed(2)} MB</TableCell>
                    <TableCell>
                      {item.createdAt ? new Date(item.createdAt).toLocaleString("zh-CN") : "-"}
                    </TableCell>
                    <TableCell className="text-right">
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => handleDeleteRecord(item.id)}
                      >
                        <Trash2 className="w-4 h-4 text-destructive" />
                      </Button>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}
