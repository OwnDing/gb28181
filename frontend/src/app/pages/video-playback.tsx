import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
    Card,
    CardContent,
    CardHeader,
    CardTitle,
} from "../components/ui/card";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "../components/ui/select";
import { History, Calendar, Play, Pause, SkipForward, SkipBack, RefreshCw } from "lucide-react";
import { toast } from "sonner";
import {
    playbackApi,
    type PlaybackChannel,
    type PlaybackRecord,
} from "../lib/api";
import { getToken } from "../lib/http";

/** Convert a relative file path from the record DB into a playable video URL. */
function buildVideoUrl(filePath: string): string {
    // filePath is an absolute path like C:\...\data\records\record\rtp\ch...\2026-02-17\xxx.mp4
    // We need to extract the relative part after the record root (./data/records)
    // The backend expects a relative path from the record root
    const markers = ["data/records/", "data\\records\\"];
    let relativePath = filePath;
    for (const marker of markers) {
        const idx = filePath.indexOf(marker);
        if (idx >= 0) {
            relativePath = filePath.substring(idx + marker.length);
            break;
        }
    }
    // Normalize to forward slashes
    relativePath = relativePath.replace(/\\/g, "/");

    const API_BASE =
        (import.meta as { env?: Record<string, string> }).env?.VITE_API_BASE ?? "";
    const token = getToken();
    const url = `${API_BASE}/api/playback/video?path=${encodeURIComponent(relativePath)}`;
    // We need to pass auth token, but <video> doesn't support Bearer header.
    // We'll handle this via fetch + blob URL instead.
    return url;
}

/** Parses a LocalDateTime string like "2026-02-17T17:31:13" to minutes since midnight */
function timeToMinutes(timeStr: string | null | undefined): number {
    if (!timeStr) return 0;
    const match = timeStr.match(/T(\d{2}):(\d{2})/);
    if (!match) return 0;
    return parseInt(match[1]) * 60 + parseInt(match[2]);
}

function formatTime(minutes: number): string {
    const h = Math.floor(minutes / 60);
    const m = minutes % 60;
    return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
}

function formatTimeSeconds(totalSeconds: number): string {
    const h = Math.floor(totalSeconds / 3600);
    const m = Math.floor((totalSeconds % 3600) / 60);
    const s = Math.floor(totalSeconds % 60);
    return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

export default function VideoPlayback() {
    const [channels, setChannels] = useState<PlaybackChannel[]>([]);
    const [selectedChannel, setSelectedChannel] = useState<string>("");
    const [selectedDate, setSelectedDate] = useState<string>(
        new Date().toISOString().split("T")[0]
    );
    const [records, setRecords] = useState<PlaybackRecord[]>([]);
    const [currentIndex, setCurrentIndex] = useState<number>(-1);
    const [loading, setLoading] = useState(true);
    const [loadingRecords, setLoadingRecords] = useState(false);
    const [isPlaying, setIsPlaying] = useState(false);
    const [videoSrc, setVideoSrc] = useState<string>("");
    const videoRef = useRef<HTMLVideoElement>(null);
    const previousBlobUrl = useRef<string>("");

    // Load channels
    const loadChannels = useCallback(async () => {
        try {
            setLoading(true);
            const data = await playbackApi.channels();
            setChannels(data);
            if (data.length > 0 && !selectedChannel) {
                setSelectedChannel(data[0].channelId);
            }
        } catch (error) {
            toast.error(
                error instanceof Error ? error.message : "加载通道列表失败"
            );
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        loadChannels();
    }, [loadChannels]);

    // Load records when channel or date changes
    const loadRecords = useCallback(async () => {
        if (!selectedChannel || !selectedDate) return;
        try {
            setLoadingRecords(true);
            const data = await playbackApi.records(selectedChannel, selectedDate);
            setRecords(data);
            setCurrentIndex(-1);
            setVideoSrc("");
            setIsPlaying(false);
        } catch (error) {
            toast.error(
                error instanceof Error ? error.message : "加载录像列表失败"
            );
        } finally {
            setLoadingRecords(false);
        }
    }, [selectedChannel, selectedDate]);

    useEffect(() => {
        if (selectedChannel && selectedDate) {
            loadRecords();
        }
    }, [selectedChannel, selectedDate, loadRecords]);

    // Load and play a video segment by fetching with auth header
    const playSegment = useCallback(
        async (index: number) => {
            if (index < 0 || index >= records.length) return;
            const record = records[index];
            const url = buildVideoUrl(record.filePath);

            try {
                // Clean up previous blob URL
                if (previousBlobUrl.current) {
                    URL.revokeObjectURL(previousBlobUrl.current);
                }

                const token = getToken();
                const response = await fetch(url, {
                    headers: token
                        ? { Authorization: `Bearer ${token}` }
                        : {},
                });

                if (!response.ok) {
                    throw new Error(`视频加载失败 (${response.status})`);
                }

                const blob = await response.blob();
                const blobUrl = URL.createObjectURL(blob);
                previousBlobUrl.current = blobUrl;

                setVideoSrc(blobUrl);
                setCurrentIndex(index);
                setIsPlaying(true);

                // Wait for the video element to be ready
                setTimeout(() => {
                    if (videoRef.current) {
                        videoRef.current.play().catch(() => { });
                    }
                }, 100);
            } catch (error) {
                toast.error(
                    error instanceof Error ? error.message : "播放视频失败"
                );
            }
        },
        [records]
    );

    // Auto-play next segment when current ends
    const handleVideoEnded = useCallback(() => {
        if (currentIndex < records.length - 1) {
            playSegment(currentIndex + 1);
        } else {
            setIsPlaying(false);
        }
    }, [currentIndex, records.length, playSegment]);

    const togglePlayPause = useCallback(() => {
        if (!videoRef.current) return;
        if (videoRef.current.paused) {
            videoRef.current.play().catch(() => { });
            setIsPlaying(true);
        } else {
            videoRef.current.pause();
            setIsPlaying(false);
        }
    }, []);

    const playPrev = useCallback(() => {
        if (currentIndex > 0) {
            playSegment(currentIndex - 1);
        }
    }, [currentIndex, playSegment]);

    const playNext = useCallback(() => {
        if (currentIndex < records.length - 1) {
            playSegment(currentIndex + 1);
        }
    }, [currentIndex, records.length, playSegment]);

    // Clean up blob URLs on unmount
    useEffect(() => {
        return () => {
            if (previousBlobUrl.current) {
                URL.revokeObjectURL(previousBlobUrl.current);
            }
        };
    }, []);

    // Timeline data: compute segments for visualization
    const timelineSegments = useMemo(() => {
        return records.map((rec, idx) => {
            const startMin = timeToMinutes(rec.startTime);
            const endMin = timeToMinutes(rec.endTime);
            return {
                index: idx,
                startMin,
                endMin: endMin || startMin + 1,
                record: rec,
            };
        });
    }, [records]);

    const currentRecord = currentIndex >= 0 ? records[currentIndex] : null;

    if (loading) {
        return (
            <div className="text-slate-600">正在加载通道列表...</div>
        );
    }

    return (
        <div className="space-y-6">
            {/* Header */}
            <div className="flex items-center justify-between">
                <div>
                    <h2 className="text-3xl font-semibold text-slate-900">录像回看</h2>
                    <p className="text-slate-500 mt-1">选择摄像头通道和日期，回看历史录像</p>
                </div>
                <Button variant="outline" onClick={loadChannels}>
                    <RefreshCw className="w-4 h-4 mr-2" />
                    刷新
                </Button>
            </div>

            {/* Controls: channel + date selector */}
            <Card>
                <CardHeader className="pb-3">
                    <CardTitle className="text-base flex items-center gap-2">
                        <History className="w-5 h-5" />
                        回看设置
                    </CardTitle>
                </CardHeader>
                <CardContent>
                    <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                        <div className="space-y-2">
                            <Label>选择通道</Label>
                            <Select
                                value={selectedChannel}
                                onValueChange={setSelectedChannel}
                            >
                                <SelectTrigger>
                                    <SelectValue placeholder="选择通道..." />
                                </SelectTrigger>
                                <SelectContent>
                                    {channels.map((ch) => (
                                        <SelectItem key={ch.channelId} value={ch.channelId}>
                                            {ch.channelId} ({ch.fileCount} 个文件)
                                        </SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        </div>
                        <div className="space-y-2">
                            <Label>选择日期</Label>
                            <Input
                                type="date"
                                value={selectedDate}
                                onChange={(e) => setSelectedDate(e.target.value)}
                            />
                        </div>
                        <div className="flex items-end">
                            <Button onClick={loadRecords} disabled={loadingRecords}>
                                {loadingRecords ? "加载中..." : "查询录像"}
                            </Button>
                        </div>
                    </div>
                </CardContent>
            </Card>

            {/* Timeline */}
            <Card>
                <CardHeader className="pb-3">
                    <CardTitle className="text-base flex items-center gap-2">
                        <Calendar className="w-5 h-5" />
                        时间轴
                        {records.length > 0 && (
                            <span className="text-sm font-normal text-slate-500 ml-2">
                                共 {records.length} 段录像
                            </span>
                        )}
                    </CardTitle>
                </CardHeader>
                <CardContent>
                    {records.length === 0 ? (
                        <div className="text-center text-slate-500 py-8">
                            {selectedChannel && selectedDate
                                ? "该日期暂无录像"
                                : "请先选择通道和日期"}
                        </div>
                    ) : (
                        <div className="space-y-3">
                            {/* Timeline bar */}
                            <div className="relative">
                                {/* Hour labels */}
                                <div className="flex justify-between text-xs text-slate-400 mb-1 px-0">
                                    {Array.from({ length: 25 }, (_, i) => (
                                        <span key={i} className="w-0 text-center" style={{ position: "relative", left: i === 0 ? "0" : i === 24 ? "-8px" : "-4px" }}>
                                            {i < 10 ? `0${i}` : i}
                                        </span>
                                    ))}
                                </div>

                                {/* Timeline track */}
                                <div
                                    className="relative h-10 bg-slate-100 rounded-lg overflow-hidden cursor-pointer border border-slate-200"
                                    onClick={(e) => {
                                        const rect = e.currentTarget.getBoundingClientRect();
                                        const x = e.clientX - rect.left;
                                        const ratio = x / rect.width;
                                        const clickMinute = Math.floor(ratio * 1440);

                                        // Find the segment closest to the clicked time
                                        let bestIdx = -1;
                                        let bestDist = Infinity;
                                        for (const seg of timelineSegments) {
                                            if (clickMinute >= seg.startMin && clickMinute <= seg.endMin) {
                                                bestIdx = seg.index;
                                                break;
                                            }
                                            const dist = Math.min(
                                                Math.abs(clickMinute - seg.startMin),
                                                Math.abs(clickMinute - seg.endMin)
                                            );
                                            if (dist < bestDist) {
                                                bestDist = dist;
                                                bestIdx = seg.index;
                                            }
                                        }
                                        if (bestIdx >= 0) {
                                            playSegment(bestIdx);
                                        }
                                    }}
                                >
                                    {/* Hour grid lines */}
                                    {Array.from({ length: 24 }, (_, i) => (
                                        <div
                                            key={i}
                                            className="absolute top-0 bottom-0 border-l border-slate-200"
                                            style={{ left: `${(i / 24) * 100}%` }}
                                        />
                                    ))}

                                    {/* Recording segments */}
                                    {timelineSegments.map((seg) => {
                                        const leftPct = (seg.startMin / 1440) * 100;
                                        const widthPct = Math.max(
                                            ((seg.endMin - seg.startMin) / 1440) * 100,
                                            0.3
                                        );
                                        const isActive = seg.index === currentIndex;
                                        return (
                                            <div
                                                key={seg.index}
                                                className={`absolute top-1 bottom-1 rounded-sm transition-colors ${isActive
                                                        ? "bg-primary ring-2 ring-primary/30"
                                                        : "bg-emerald-500/70 hover:bg-emerald-500"
                                                    }`}
                                                style={{
                                                    left: `${leftPct}%`,
                                                    width: `${widthPct}%`,
                                                    minWidth: "3px",
                                                }}
                                                title={`${seg.record.startTime?.replace("T", " ")} ~ ${seg.record.endTime?.replace("T", " ")}`}
                                            />
                                        );
                                    })}
                                </div>
                            </div>

                            {/* Quick time labels */}
                            <div className="flex gap-2 flex-wrap">
                                {timelineSegments.length > 0 && (
                                    <>
                                        <span className="text-xs text-slate-500">
                                            最早: {formatTime(timelineSegments[0].startMin)}
                                        </span>
                                        <span className="text-xs text-slate-500">
                                            最晚: {formatTime(timelineSegments[timelineSegments.length - 1].endMin)}
                                        </span>
                                    </>
                                )}
                            </div>
                        </div>
                    )}
                </CardContent>
            </Card>

            {/* Video Player */}
            <Card>
                <CardHeader className="pb-3">
                    <CardTitle className="text-base flex items-center gap-2">
                        <Play className="w-5 h-5" />
                        播放器
                        {currentRecord && (
                            <span className="text-sm font-normal text-slate-500 ml-2">
                                {currentRecord.startTime?.replace("T", " ")} ~{" "}
                                {currentRecord.endTime?.replace("T", " ")}
                                {" "}| 第 {currentIndex + 1}/{records.length} 段
                            </span>
                        )}
                    </CardTitle>
                </CardHeader>
                <CardContent>
                    {!videoSrc ? (
                        <div className="aspect-video bg-slate-900 rounded-lg flex items-center justify-center">
                            <div className="text-center text-slate-400">
                                <Play className="w-16 h-16 mx-auto mb-3 opacity-30" />
                                <p>点击时间轴上的录像段开始播放</p>
                            </div>
                        </div>
                    ) : (
                        <div className="space-y-3">
                            <div className="aspect-video bg-black rounded-lg overflow-hidden">
                                <video
                                    ref={videoRef}
                                    src={videoSrc}
                                    className="w-full h-full"
                                    onEnded={handleVideoEnded}
                                    onPlay={() => setIsPlaying(true)}
                                    onPause={() => setIsPlaying(false)}
                                    controls
                                />
                            </div>

                            {/* Custom controls */}
                            <div className="flex items-center justify-center gap-3">
                                <Button
                                    variant="outline"
                                    size="sm"
                                    onClick={playPrev}
                                    disabled={currentIndex <= 0}
                                >
                                    <SkipBack className="w-4 h-4 mr-1" />
                                    上一段
                                </Button>
                                <Button
                                    variant={isPlaying ? "outline" : "default"}
                                    size="sm"
                                    onClick={togglePlayPause}
                                >
                                    {isPlaying ? (
                                        <>
                                            <Pause className="w-4 h-4 mr-1" />
                                            暂停
                                        </>
                                    ) : (
                                        <>
                                            <Play className="w-4 h-4 mr-1" />
                                            播放
                                        </>
                                    )}
                                </Button>
                                <Button
                                    variant="outline"
                                    size="sm"
                                    onClick={playNext}
                                    disabled={currentIndex >= records.length - 1}
                                >
                                    下一段
                                    <SkipForward className="w-4 h-4 ml-1" />
                                </Button>
                            </div>
                        </div>
                    )}
                </CardContent>
            </Card>

            {/* Records table */}
            {records.length > 0 && (
                <Card>
                    <CardHeader className="pb-3">
                        <CardTitle className="text-base">
                            录像段列表
                        </CardTitle>
                    </CardHeader>
                    <CardContent>
                        <div className="max-h-60 overflow-y-auto">
                            <table className="w-full text-sm">
                                <thead className="sticky top-0 bg-white">
                                    <tr className="text-left text-slate-500 border-b">
                                        <th className="py-2 px-2">#</th>
                                        <th className="py-2 px-2">开始时间</th>
                                        <th className="py-2 px-2">结束时间</th>
                                        <th className="py-2 px-2">大小</th>
                                        <th className="py-2 px-2">操作</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {records.map((rec, idx) => (
                                        <tr
                                            key={rec.id}
                                            className={`border-b cursor-pointer transition-colors ${idx === currentIndex
                                                    ? "bg-primary/10"
                                                    : "hover:bg-slate-50"
                                                }`}
                                            onClick={() => playSegment(idx)}
                                        >
                                            <td className="py-2 px-2 text-slate-400">{idx + 1}</td>
                                            <td className="py-2 px-2 font-mono text-xs">
                                                {rec.startTime?.replace("T", " ") ?? "-"}
                                            </td>
                                            <td className="py-2 px-2 font-mono text-xs">
                                                {rec.endTime?.replace("T", " ") ?? "-"}
                                            </td>
                                            <td className="py-2 px-2">
                                                {(rec.fileSizeBytes / 1024 / 1024).toFixed(1)} MB
                                            </td>
                                            <td className="py-2 px-2">
                                                <Button
                                                    variant="ghost"
                                                    size="sm"
                                                    onClick={(e) => {
                                                        e.stopPropagation();
                                                        playSegment(idx);
                                                    }}
                                                >
                                                    <Play className="w-3 h-3" />
                                                </Button>
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    </CardContent>
                </Card>
            )}
        </div>
    );
}
