import { useCallback, useEffect, useState } from "react";
import { HardDrive, RefreshCw } from "lucide-react";
import { toast } from "sonner";
import { Button } from "../../components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "../../components/ui/card";
import {
  storageApi,
  type StoragePolicy,
  type StorageUsage,
} from "../../lib/api";
import MobilePage from "../components/mobile-page";

export default function MobileSettings() {
  const [loading, setLoading] = useState(true);
  const [policy, setPolicy] = useState<StoragePolicy | null>(null);
  const [usage, setUsage] = useState<StorageUsage | null>(null);

  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      const [policyData, usageData] = await Promise.all([
        storageApi.getPolicy(),
        storageApi.usage(),
      ]);
      setPolicy(policyData);
      setUsage(usageData);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "存储信息加载失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  return (
    <MobilePage
      title="存储设置"
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
            <HardDrive className="h-5 w-5 text-primary" />
            存储概览
          </CardTitle>
        </CardHeader>
        <CardContent className="grid grid-cols-2 gap-3 text-sm">
          <div className="rounded-2xl bg-slate-50 px-3 py-3">
            <p className="text-xs text-slate-500">使用空间</p>
            <p className="mt-1 font-medium text-slate-900">
              {loading ? "--" : `${usage?.usedGb.toFixed(1) || 0} GB`}
            </p>
          </div>
          <div className="rounded-2xl bg-slate-50 px-3 py-3">
            <p className="text-xs text-slate-500">容量上限</p>
            <p className="mt-1 font-medium text-slate-900">
              {loading ? "--" : `${usage?.maxStorageGb || 0} GB`}
            </p>
          </div>
          <div className="rounded-2xl bg-slate-50 px-3 py-3">
            <p className="text-xs text-slate-500">录像文件数</p>
            <p className="mt-1 font-medium text-slate-900">
              {loading ? "--" : `${usage?.fileCount || 0}`}
            </p>
          </div>
          <div className="rounded-2xl bg-slate-50 px-3 py-3">
            <p className="text-xs text-slate-500">使用率</p>
            <p className="mt-1 font-medium text-slate-900">
              {loading ? "--" : `${usage?.usagePercent.toFixed(1) || 0}%`}
            </p>
          </div>
        </CardContent>
      </Card>

      <Card className="border-slate-200 shadow-sm">
        <CardHeader className="pb-2">
          <CardTitle className="text-base">当前策略</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3 text-sm">
          <div className="rounded-2xl bg-slate-50 px-4 py-4">
            <p className="text-xs text-slate-500">保留天数</p>
            <p className="mt-1 font-medium text-slate-900">
              {loading ? "--" : `${policy?.retentionDays || 0} 天`}
            </p>
          </div>
          <div className="rounded-2xl bg-slate-50 px-4 py-4">
            <p className="text-xs text-slate-500">自动覆盖</p>
            <p className="mt-1 font-medium text-slate-900">
              {loading ? "--" : policy?.autoOverwrite ? "已启用" : "已禁用"}
            </p>
          </div>
          <div className="rounded-2xl bg-slate-50 px-4 py-4">
            <p className="text-xs text-slate-500">录像目录</p>
            <p className="mt-1 break-all font-medium text-slate-900">
              {loading ? "--" : policy?.recordPath || "-"}
            </p>
          </div>
        </CardContent>
      </Card>
    </MobilePage>
  );
}
