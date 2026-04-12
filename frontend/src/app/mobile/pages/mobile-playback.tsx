import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  CalendarClock,
  Clock3,
  Download,
  Pause,
  Play,
  RefreshCw,
  SkipBack,
  SkipForward,
} from "lucide-react";
import { toast } from "sonner";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { Progress } from "../../components/ui/progress";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "../../components/ui/select";
import {
  playbackApi,
  type PlaybackChannel,
  type PlaybackRecord,
} from "../../lib/api";
import { getToken } from "../../lib/http";
import { requestNativeFileDownload } from "../lib/native-actions";
import MobilePage from "../components/mobile-page";

function getTodayDate() {
  const date = new Date();
  const timezoneOffset = date.getTimezoneOffset() * 60 * 1000;
  return new Date(date.getTime() - timezoneOffset).toISOString().split("T")[0];
}

function buildVideoUrl(filePath: string) {
  const normalizedPath = filePath.replace(/\\/g, "/");
  const apiBase =
    (import.meta as { env?: Record<string, string> }).env?.VITE_API_BASE ?? "";
  return `${apiBase}/api/playback/video?path=${encodeURIComponent(normalizedPath)}`;
}

function timeToMinutes(timeStr?: string | null) {
  if (!timeStr) {
    return 0;
  }
  const match = timeStr.match(/T(\d{2}):(\d{2})/);
  if (!match) {
    return 0;
  }
  return Number(match[1]) * 60 + Number(match[2]);
}

function formatTime(minutes: number) {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
}

function formatRecordTime(value?: string | null) {
  return value?.replace("T", " ") ?? "--";
}

function formatRecordRange(record: PlaybackRecord) {
  return `${formatRecordTime(record.startTime)} 至 ${formatRecordTime(record.endTime)}`;
}

function getRecordFileName(record: PlaybackRecord) {
  const normalized = record.filePath.replace(/\\/g, "/");
  const segments = normalized.split("/");
  return segments[segments.length - 1] || `playback-${record.id}.mp4`;
}

export default function MobilePlayback() {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const previousBlobUrl = useRef("");

  const [channels, setChannels] = useState<PlaybackChannel[]>([]);
  const [selectedChannel, setSelectedChannel] = useState("");
  const [selectedDate, setSelectedDate] = useState(getTodayDate());
  const [records, setRecords] = useState<PlaybackRecord[]>([]);
  const [currentIndex, setCurrentIndex] = useState(-1);
  const [videoSrc, setVideoSrc] = useState("");
  const [loading, setLoading] = useState(true);
  const [loadingRecords, setLoadingRecords] = useState(false);
  const [loadingSegmentId, setLoadingSegmentId] = useState<number | null>(null);
  const [isPlaying, setIsPlaying] = useState(false);

  const resetPlayer = useCallback(() => {
    if (videoRef.current) {
      videoRef.current.pause();
      videoRef.current.removeAttribute("src");
      videoRef.current.load();
    }
    if (previousBlobUrl.current) {
      URL.revokeObjectURL(previousBlobUrl.current);
      previousBlobUrl.current = "";
    }
    setVideoSrc("");
    setCurrentIndex(-1);
    setIsPlaying(false);
  }, []);

  const loadChannels = useCallback(async () => {
    try {
      setLoading(true);
      const data = await playbackApi.channels();
      setChannels(data);
      setSelectedChannel((current) => current || data[0]?.channelId || "");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "回放通道加载失败");
    } finally {
      setLoading(false);
    }
  }, []);

  const loadRecords = useCallback(async () => {
    if (!selectedChannel || !selectedDate) {
      return;
    }

    try {
      setLoadingRecords(true);
      const data = await playbackApi.records(selectedChannel, selectedDate);
      resetPlayer();
      setRecords(data);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "录像片段加载失败");
    } finally {
      setLoadingRecords(false);
    }
  }, [resetPlayer, selectedChannel, selectedDate]);

  useEffect(() => {
    loadChannels();
  }, [loadChannels]);

  useEffect(() => {
    if (selectedChannel) {
      loadRecords();
    }
  }, [loadRecords, selectedChannel, selectedDate]);

  useEffect(() => {
    return () => {
      resetPlayer();
    };
  }, [resetPlayer]);

  const playSegment = useCallback(
    async (index: number) => {
      if (index < 0 || index >= records.length) {
        return;
      }

      const record = records[index];
      const url = buildVideoUrl(record.filePath);

      try {
        setLoadingSegmentId(record.id);
        if (previousBlobUrl.current) {
          URL.revokeObjectURL(previousBlobUrl.current);
          previousBlobUrl.current = "";
        }

        const token = getToken();
        const response = await fetch(url, {
          headers: token ? { Authorization: `Bearer ${token}` } : {},
        });

        if (!response.ok) {
          throw new Error(`录像加载失败 (${response.status})`);
        }

        const blob = await response.blob();
        const blobUrl = URL.createObjectURL(blob);
        previousBlobUrl.current = blobUrl;

        setVideoSrc(blobUrl);
        setCurrentIndex(index);
        setIsPlaying(true);

        window.setTimeout(() => {
          videoRef.current?.play().catch(() => undefined);
        }, 80);
      } catch (error) {
        toast.error(error instanceof Error ? error.message : "录像播放失败");
      } finally {
        setLoadingSegmentId(null);
      }
    },
    [records],
  );

  const playPrev = useCallback(() => {
    if (currentIndex > 0) {
      playSegment(currentIndex - 1);
    }
  }, [currentIndex, playSegment]);

  const playNext = useCallback(() => {
    if (currentIndex >= 0 && currentIndex < records.length - 1) {
      playSegment(currentIndex + 1);
    }
  }, [currentIndex, playSegment, records.length]);

  const handleVideoEnded = useCallback(() => {
    if (currentIndex >= 0 && currentIndex < records.length - 1) {
      playSegment(currentIndex + 1);
      return;
    }
    setIsPlaying(false);
  }, [currentIndex, playSegment, records.length]);

  const togglePlayPause = useCallback(() => {
    if (!videoRef.current) {
      return;
    }

    if (videoRef.current.paused) {
      videoRef.current.play().catch(() => undefined);
      setIsPlaying(true);
    } else {
      videoRef.current.pause();
      setIsPlaying(false);
    }
  }, []);

  const timelineSegments = useMemo(
    () =>
      records.map((record, index) => {
        const startMin = timeToMinutes(record.startTime);
        const endMin = timeToMinutes(record.endTime) || startMin + 1;
        return {
          index,
          record,
          startMin,
          endMin,
        };
      }),
    [records],
  );

  const currentRecord = currentIndex >= 0 ? records[currentIndex] : null;
  const totalSizeMb = useMemo(
    () => records.reduce((sum, item) => sum + item.fileSizeBytes, 0) / 1024 / 1024,
    [records],
  );
  const earliestMinute = timelineSegments[0]?.startMin ?? 0;
  const latestMinute =
    timelineSegments[timelineSegments.length - 1]?.endMin ?? 0;
  const completion =
    records.length > 0 && currentIndex >= 0
      ? ((currentIndex + 1) / records.length) * 100
      : 0;

  const downloadRecord = useCallback(async (record: PlaybackRecord) => {
    try {
      const mode = await requestNativeFileDownload({
        url: buildVideoUrl(record.filePath),
        fileName: getRecordFileName(record),
        mimeType: "video/mp4",
      });
      toast.success(mode === "native" ? "已交给 App 下载" : "下载已开始");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "录像下载失败");
    }
  }, []);

  return (
    <MobilePage
      title="录像回放"
      description="支持按通道和日期查询录像片段，并在移动端连续播放相邻片段。"
      action={
        <Button
          disabled={loading || loadingRecords}
          onClick={loadChannels}
          size="sm"
          variant="outline"
        >
          <RefreshCw className="mr-2 h-4 w-4" />
          刷新
        </Button>
      }
    >
      <Card className="border-slate-200 shadow-sm">
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <CalendarClock className="h-5 w-5 text-primary" />
            回放筛选
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <Label>录像通道</Label>
            <Select onValueChange={setSelectedChannel} value={selectedChannel}>
              <SelectTrigger>
                <SelectValue placeholder="请选择通道" />
              </SelectTrigger>
              <SelectContent>
                {channels.map((channel) => (
                  <SelectItem key={channel.channelId} value={channel.channelId}>
                    {channel.channelId} ({channel.fileCount} 段)
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="grid grid-cols-[1fr_auto] gap-3">
            <div className="space-y-2">
              <Label htmlFor="mobile-playback-date">日期</Label>
              <Input
                id="mobile-playback-date"
                onChange={(event) => setSelectedDate(event.target.value)}
                type="date"
                value={selectedDate}
              />
            </div>
            <div className="flex items-end">
              <Button
                disabled={!selectedChannel || loadingRecords}
                onClick={loadRecords}
              >
                {loadingRecords ? "查询中..." : "查询"}
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>

      <div className="grid grid-cols-3 gap-3">
        <Card className="border-slate-200 shadow-sm">
          <CardContent className="px-4 py-4">
            <p className="text-xs text-slate-500">录像段数</p>
            <p className="mt-2 text-2xl font-semibold text-slate-900">
              {loading ? "--" : records.length}
            </p>
          </CardContent>
        </Card>
        <Card className="border-slate-200 shadow-sm">
          <CardContent className="px-4 py-4">
            <p className="text-xs text-slate-500">覆盖时段</p>
            <p className="mt-2 text-sm font-semibold text-slate-900">
              {records.length > 0
                ? `${formatTime(earliestMinute)} - ${formatTime(latestMinute)}`
                : "--"}
            </p>
          </CardContent>
        </Card>
        <Card className="border-slate-200 shadow-sm">
          <CardContent className="px-4 py-4">
            <p className="text-xs text-slate-500">总大小</p>
            <p className="mt-2 text-sm font-semibold text-slate-900">
              {records.length > 0 ? `${totalSizeMb.toFixed(1)} MB` : "--"}
            </p>
          </CardContent>
        </Card>
      </div>

      <Card className="border-slate-200 shadow-sm">
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <Clock3 className="h-5 w-5 text-primary" />
            当日时间轴
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {records.length === 0 ? (
            <div className="rounded-2xl border border-dashed border-slate-200 px-4 py-8 text-center text-sm text-slate-500">
              {selectedChannel ? "当前日期暂无录像片段" : "请先选择通道"}
            </div>
          ) : (
            <>
              <div className="flex justify-between text-[11px] text-slate-400">
                <span>00:00</span>
                <span>06:00</span>
                <span>12:00</span>
                <span>18:00</span>
                <span>24:00</span>
              </div>
              <div
                className="relative h-11 overflow-hidden rounded-2xl border border-slate-200 bg-slate-100"
                onClick={(event) => {
                  const rect = event.currentTarget.getBoundingClientRect();
                  const clickMinute = Math.floor(
                    ((event.clientX - rect.left) / rect.width) * 1440,
                  );

                  let nextIndex = 0;
                  let nextDistance = Number.POSITIVE_INFINITY;

                  for (const segment of timelineSegments) {
                    if (
                      clickMinute >= segment.startMin &&
                      clickMinute <= segment.endMin
                    ) {
                      nextIndex = segment.index;
                      break;
                    }

                    const distance = Math.min(
                      Math.abs(clickMinute - segment.startMin),
                      Math.abs(clickMinute - segment.endMin),
                    );

                    if (distance < nextDistance) {
                      nextIndex = segment.index;
                      nextDistance = distance;
                    }
                  }

                  playSegment(nextIndex);
                }}
              >
                {Array.from({ length: 24 }, (_, index) => (
                  <div
                    key={index}
                    className="absolute top-0 bottom-0 border-l border-slate-200"
                    style={{ left: `${(index / 24) * 100}%` }}
                  />
                ))}

                {timelineSegments.map((segment) => {
                  const left = (segment.startMin / 1440) * 100;
                  const width = Math.max(
                    ((segment.endMin - segment.startMin) / 1440) * 100,
                    0.6,
                  );
                  const active = segment.index === currentIndex;

                  return (
                    <div
                      key={segment.record.id}
                      className={`absolute top-1 bottom-1 rounded-full transition-all ${
                        active
                          ? "bg-primary ring-2 ring-primary/25"
                          : "bg-emerald-500/75"
                      }`}
                      style={{ left: `${left}%`, width: `${width}%` }}
                      title={formatRecordRange(segment.record)}
                    />
                  );
                })}
              </div>
              <div className="flex flex-wrap gap-2 text-xs text-slate-500">
                <span>最早片段 {formatTime(earliestMinute)}</span>
                <span>最晚片段 {formatTime(latestMinute)}</span>
              </div>
            </>
          )}
        </CardContent>
      </Card>

      <Card className="border-slate-200 shadow-sm">
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center justify-between gap-3 text-base">
            <span>播放器</span>
            {currentRecord ? (
              <Badge variant="secondary">第 {currentIndex + 1} / {records.length} 段</Badge>
            ) : null}
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {!videoSrc ? (
            <div className="flex aspect-video items-center justify-center rounded-3xl bg-slate-950 text-center text-sm text-slate-300">
              点击时间轴或下方列表中的录像片段开始播放
            </div>
          ) : (
            <div className="space-y-3">
              <div className="overflow-hidden rounded-3xl bg-black">
                <video
                  className="aspect-video w-full bg-black object-contain"
                  controls
                  onEnded={handleVideoEnded}
                  onPause={() => setIsPlaying(false)}
                  onPlay={() => setIsPlaying(true)}
                  playsInline
                  ref={videoRef}
                  src={videoSrc}
                />
              </div>

              {currentRecord ? (
                <div className="space-y-2 rounded-2xl bg-slate-50 px-4 py-3">
                  <div className="flex items-center justify-between gap-3">
                    <p className="text-sm font-medium text-slate-900">
                      {formatRecordRange(currentRecord)}
                    </p>
                    <span className="text-xs text-slate-500">
                      {(currentRecord.fileSizeBytes / 1024 / 1024).toFixed(1)} MB
                    </span>
                  </div>
                  <Progress value={completion} />
                  <Button
                    className="w-full rounded-2xl"
                    onClick={() => downloadRecord(currentRecord)}
                    variant="outline"
                  >
                    <Download className="mr-2 h-4 w-4" />
                    下载当前录像
                  </Button>
                </div>
              ) : null}

              <div className="grid grid-cols-3 gap-2">
                <Button
                  disabled={currentIndex <= 0}
                  onClick={playPrev}
                  variant="outline"
                >
                  <SkipBack className="mr-1 h-4 w-4" />
                  上一段
                </Button>
                <Button disabled={!videoSrc} onClick={togglePlayPause}>
                  {isPlaying ? (
                    <>
                      <Pause className="mr-1 h-4 w-4" />
                      暂停
                    </>
                  ) : (
                    <>
                      <Play className="mr-1 h-4 w-4" />
                      播放
                    </>
                  )}
                </Button>
                <Button
                  disabled={currentIndex < 0 || currentIndex >= records.length - 1}
                  onClick={playNext}
                  variant="outline"
                >
                  下一段
                  <SkipForward className="ml-1 h-4 w-4" />
                </Button>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      <Card className="border-slate-200 shadow-sm">
        <CardHeader className="pb-3">
          <CardTitle className="text-base">录像片段列表</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {records.length === 0 ? (
            <div className="rounded-2xl border border-dashed border-slate-200 px-4 py-8 text-center text-sm text-slate-500">
              查询后会在这里展示当日录像片段。
            </div>
          ) : (
            records.map((record, index) => {
              const active = index === currentIndex;
              const busy = loadingSegmentId === record.id;

              return (
                <div
                  key={record.id}
                  className={`w-full rounded-2xl border px-4 py-4 text-left transition-colors ${
                    active
                      ? "border-primary bg-primary/5 shadow-sm"
                      : "border-slate-200 bg-white hover:border-primary/30 hover:bg-slate-50"
                  }`}
                >
                  <button
                    className="w-full text-left"
                    onClick={() => playSegment(index)}
                    type="button"
                  >
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2">
                          <p className="font-medium text-slate-900">
                            第 {index + 1} 段录像
                          </p>
                          {active ? <Badge>播放中</Badge> : null}
                        </div>
                        <p className="mt-2 text-sm text-slate-500">
                          {formatRecordRange(record)}
                        </p>
                        <p className="mt-1 text-xs text-slate-400">
                          文件大小 {(record.fileSizeBytes / 1024 / 1024).toFixed(1)} MB
                        </p>
                      </div>
                      <div className="flex items-center gap-2 text-primary">
                        {busy ? <span className="text-xs text-slate-500">加载中...</span> : null}
                      </div>
                    </div>
                  </button>
                  <div className="mt-3 grid grid-cols-[1fr_auto] gap-2">
                    <div className="min-w-0">
                      <Button
                        disabled={busy}
                        onClick={() => playSegment(index)}
                        variant={active ? "default" : "outline"}
                      >
                        <Play className="mr-2 h-4 w-4" />
                        播放
                      </Button>
                    </div>
                    <Button
                      disabled={busy}
                      onClick={() => downloadRecord(record)}
                      size="icon"
                      variant="outline"
                    >
                      <Download className="h-4 w-4" />
                    </Button>
                  </div>
                </div>
              );
            })
          )}
        </CardContent>
      </Card>
    </MobilePage>
  );
}
