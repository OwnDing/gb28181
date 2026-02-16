import { useEffect, useState } from "react";
import { format } from "date-fns";
import { zhCN } from "date-fns/locale";
import { Loader2, AlertTriangle, Image as ImageIcon, Video } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import {
    Table,
    TableBody,
    TableCell,
    TableHead,
    TableHeader,
    TableRow,
} from "../components/ui/table";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "../components/ui/dialog";
import { alarmApi, GbAlarmEvent } from "../lib/api";
import { toast } from "sonner";
import { Button } from "../components/ui/button";

export default function AlarmHistory() {
    const [loading, setLoading] = useState(true);
    const [alarms, setAlarms] = useState<GbAlarmEvent[]>([]);
    const [selectedSnapshot, setSelectedSnapshot] = useState<string | null>(null);

    const fetchAlarms = async () => {
        try {
            setLoading(true);
            const data = await alarmApi.list({ limit: 100 });
            setAlarms(data);
        } catch (err) {
            console.error(err);
            toast.error("获取报警记录失败");
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchAlarms();
        // Optional: Auto-refresh every 10 seconds
        const interval = setInterval(fetchAlarms, 10000);
        return () => clearInterval(interval);
    }, []);

    return (
        <div className="space-y-6">
            <div className="flex items-center justify-between">
                <h2 className="text-2xl font-bold tracking-tight">智能报警记录</h2>
                <Button variant="outline" onClick={fetchAlarms} disabled={loading}>
                    {loading ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : null}
                    刷新
                </Button>
            </div>

            <Card>
                <CardHeader>
                    <CardTitle className="flex items-center gap-2">
                        <AlertTriangle className="w-5 h-5 text-red-500" />
                        报警列表 (最近100条)
                    </CardTitle>
                </CardHeader>
                <CardContent>
                    <Table>
                        <TableHeader>
                            <TableRow>
                                <TableHead>时间</TableHead>
                                <TableHead>设备ID</TableHead>
                                <TableHead>通道ID</TableHead>
                                <TableHead>报警类型</TableHead>
                                <TableHead>描述</TableHead>
                                <TableHead>快照</TableHead>
                                {/* <TableHead>录像</TableHead> */}
                            </TableRow>
                        </TableHeader>
                        <TableBody>
                            {alarms.length === 0 ? (
                                <TableRow>
                                    <TableCell colSpan={6} className="text-center py-8 text-muted-foreground">
                                        暂无报警记录
                                    </TableCell>
                                </TableRow>
                            ) : (
                                alarms.map((alarm) => (
                                    <TableRow key={alarm.id}>
                                        <TableCell>
                                            {alarm.alarmTime || format(new Date(alarm.createdAt), "yyyy-MM-dd HH:mm:ss")}
                                        </TableCell>
                                        <TableCell>{alarm.deviceId}</TableCell>
                                        <TableCell>{alarm.channelId}</TableCell>
                                        <TableCell>
                                            <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-red-100 text-red-800">
                                                {alarm.alarmMethod === "AI_DETECTION" ? "AI侦测" : alarm.alarmMethod || "未知"}
                                            </span>
                                        </TableCell>
                                        <TableCell>{alarm.description}</TableCell>
                                        <TableCell>
                                            {alarm.snapshotUrl ? (
                                                <Button
                                                    variant="ghost"
                                                    size="sm"
                                                    className="h-8 w-8 p-0"
                                                    onClick={() => setSelectedSnapshot(alarm.snapshotUrl || null)}
                                                >
                                                    <ImageIcon className="w-4 h-4 text-blue-500" />
                                                </Button>
                                            ) : (
                                                <span className="text-gray-300">-</span>
                                            )}
                                        </TableCell>
                                        {/* <TableCell>
                      {alarm.videoPath ? (
                        <Button variant="ghost" size="sm">
                          <Video className="w-4 h-4 text-green-500" />
                        </Button>
                      ) : (
                        <span className="text-gray-300">-</span>
                      )}
                    </TableCell> */}
                                    </TableRow>
                                ))
                            )}
                        </TableBody>
                    </Table>
                </CardContent>
            </Card>

            <Dialog open={!!selectedSnapshot} onOpenChange={(open) => !open && setSelectedSnapshot(null)}>
                <DialogContent className="max-w-3xl">
                    <DialogHeader>
                        <DialogTitle>报警快照</DialogTitle>
                    </DialogHeader>
                    {selectedSnapshot && (
                        <div className="flex justify-center">
                            <img
                                src={selectedSnapshot}
                                alt="Snapshot"
                                className="max-h-[60vh] object-contain rounded-lg shadow-md"
                                onError={(e) => {
                                    (e.target as HTMLImageElement).src = "";
                                    toast.error("加载图片失败");
                                }}
                            />
                        </div>
                    )}
                </DialogContent>
            </Dialog>
        </div>
    );
}
