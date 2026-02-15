import { apiFetch } from "./http";

export type AuthLoginRequest = {
  username: string;
  password: string;
};

export type AuthLoginResponse = {
  token: string;
  username: string;
  role: string;
  expiresAt: string;
};

export type AuthMe = {
  userId: number;
  username: string;
  role: string;
};

export type Device = {
  id: number;
  name: string;
  deviceId: string;
  ip: string;
  port: number;
  transport: "UDP" | "TCP";
  username?: string;
  password?: string;
  manufacturer: string;
  channelCount: number;
  preferredCodec: "H264" | "H265";
  online: boolean;
  lastSeenAt?: string | null;
  createdAt: string;
  updatedAt: string;
};

export type DeviceRequest = {
  name: string;
  deviceId: string;
  ip: string;
  port: number;
  transport: "UDP" | "TCP";
  username?: string;
  password?: string;
  manufacturer: string;
  channelCount: number;
  preferredCodec: "H264" | "H265";
};

export type DeviceChannel = {
  id: number;
  devicePk: number;
  channelNo: number;
  channelId: string;
  name: string;
  codec: "H264" | "H265";
  status: "ONLINE" | "OFFLINE";
  createdAt: string;
  updatedAt: string;
};

export type PreviewStartRequest = {
  devicePk: number;
  channelId?: string;
  protocol?: "WEBRTC" | "HLS" | "HTTP_FLV";
  browserSupportsH265: boolean;
};

export type PreviewStartResponse = {
  sessionId: string;
  devicePk: number;
  deviceId: string;
  channelId: string;
  codec: "H264" | "H265";
  protocol: "WEBRTC" | "HLS" | "HTTP_FLV";
  playUrl: string;
  webrtcPlayerUrl: string;
  hlsUrl: string;
  httpFlvUrl: string;
  rtspUrl: string;
  rtmpUrl: string;
  ssrc?: string | null;
  sipCallId?: string | null;
  viewerCount: number;
  rtpPort?: number | null;
  created: boolean;
  message: string;
};

export type PreviewSessionStatus = {
  sessionId: string;
  devicePk: number;
  deviceId: string;
  channelId: string;
  codec: "H264" | "H265";
  protocol: "WEBRTC" | "HLS" | "HTTP_FLV";
  playUrl: string;
  viewerCount: number;
  startedAt: string;
  updatedAt: string;
};

export type PreviewWebRtcPlayRequest = {
  sessionId: string;
  offerSdp: string;
};

export type PreviewWebRtcPlayResponse = {
  type: string;
  sdp: string;
};

export type SipCommandResult = {
  success: boolean;
  callId?: string | null;
  statusCode: number;
  reason: string;
  timestamp: string;
};

export type GbDeviceProfile = {
  deviceId: string;
  name?: string | null;
  manufacturer?: string | null;
  model?: string | null;
  firmware?: string | null;
  status?: string | null;
  rawXml?: string | null;
  updatedAt: string;
};

export type GbCatalogItem = {
  channelId: string;
  name?: string | null;
  manufacturer?: string | null;
  model?: string | null;
  owner?: string | null;
  civilCode?: string | null;
  address?: string | null;
  parental?: string | null;
  parentId?: string | null;
  safetyWay?: string | null;
  registerWay?: string | null;
  secrecy?: string | null;
  status?: string | null;
};

export type GbRecordItem = {
  id: number;
  deviceId: string;
  channelId?: string | null;
  recordId?: string | null;
  name?: string | null;
  address?: string | null;
  startTime?: string | null;
  endTime?: string | null;
  secrecy?: string | null;
  type?: string | null;
  recorderId?: string | null;
  filePath?: string | null;
  rawXml?: string | null;
  updatedAt: string;
};

export type GbAlarmEvent = {
  id: number;
  deviceId?: string | null;
  channelId?: string | null;
  alarmMethod?: string | null;
  alarmType?: string | null;
  alarmPriority?: string | null;
  alarmTime?: string | null;
  longitude?: string | null;
  latitude?: string | null;
  description?: string | null;
  rawXml?: string | null;
  createdAt: string;
};

export type GbMobilePosition = {
  id: number;
  deviceId: string;
  channelId?: string | null;
  time?: string | null;
  longitude?: string | null;
  latitude?: string | null;
  speed?: string | null;
  direction?: string | null;
  altitude?: string | null;
  rawXml?: string | null;
  createdAt: string;
};

export type GbSubscription = {
  id: number;
  deviceId: string;
  eventType: string;
  callId?: string | null;
  expires: number;
  status: string;
  createdAt: string;
  updatedAt: string;
};

export type GbPlaybackSession = {
  id: number;
  sessionId: string;
  deviceId: string;
  channelId: string;
  streamId: string;
  app: string;
  ssrc?: string | null;
  callId?: string | null;
  rtpPort?: number | null;
  protocol: string;
  speed: number;
  status: string;
  startTime: string;
  endTime: string;
  createdAt: string;
  updatedAt: string;
};

export type GbSubscriptionResult = {
  subscription: GbSubscription;
  sipResult: SipCommandResult;
};

export type StoragePolicy = {
  retentionDays: number;
  maxStorageGb: number;
  autoOverwrite: boolean;
  recordEnabled: boolean;
  recordPath: string;
  updatedAt: string;
};

export type StorageUsage = {
  fileCount: number;
  usedBytes: number;
  usedGb: number;
  maxStorageGb: number;
  usagePercent: number;
  oldestFileTime?: string | null;
  newestFileTime?: string | null;
};

export type RecordFileItem = {
  id: number;
  deviceId?: string | null;
  channelId?: string | null;
  filePath: string;
  fileSizeBytes: number;
  startTime?: string | null;
  endTime?: string | null;
  createdAt: string;
};

export type BackgroundRecordingStatus = {
  devicePk: number;
  deviceId: string;
  deviceName: string;
  deviceOnline: boolean;
  channelId: string;
  channelName: string;
  channelCodec: "H264" | "H265";
  targetRecording: boolean;
  managedByScheduler: boolean;
  sessionActive: boolean;
  streamReady: boolean;
  recordingEnabled: boolean;
  recording: boolean;
  backgroundPinned: boolean;
  viewerCount: number;
  app?: string | null;
  streamId?: string | null;
  lastError?: string | null;
  updatedAt?: string | null;
};

export const authApi = {
  login: (payload: AuthLoginRequest) =>
    apiFetch<AuthLoginResponse>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify(payload),
    }),
  me: () => apiFetch<AuthMe>("/api/auth/me"),
  logout: () =>
    apiFetch<void>("/api/auth/logout", {
      method: "POST",
    }),
};

export const deviceApi = {
  list: () => apiFetch<Device[]>("/api/devices"),
  create: (payload: DeviceRequest) =>
    apiFetch<Device>("/api/devices", {
      method: "POST",
      body: JSON.stringify(payload),
    }),
  update: (id: number, payload: DeviceRequest) =>
    apiFetch<Device>(`/api/devices/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    }),
  remove: (id: number) =>
    apiFetch<void>(`/api/devices/${id}`, {
      method: "DELETE",
    }),
  updateStatus: (id: number, online: boolean) =>
    apiFetch<Device>(`/api/devices/${id}/status`, {
      method: "PATCH",
      body: JSON.stringify({ online }),
    }),
  channels: (id: number) => apiFetch<DeviceChannel[]>(`/api/devices/${id}/channels`),
};

export const previewApi = {
  start: (payload: PreviewStartRequest) =>
    apiFetch<PreviewStartResponse>("/api/preview/start", {
      method: "POST",
      body: JSON.stringify(payload),
    }),
  stop: (sessionId: string) =>
    apiFetch<void>("/api/preview/stop", {
      method: "POST",
      body: JSON.stringify({ sessionId }),
    }),
  status: () => apiFetch<PreviewSessionStatus[]>("/api/preview/status"),
  webrtcPlay: (payload: PreviewWebRtcPlayRequest) =>
    apiFetch<PreviewWebRtcPlayResponse>("/api/preview/webrtc/play", {
      method: "POST",
      body: JSON.stringify(payload),
    }),
};

export const gb28181Api = {
  queryDeviceInfo: (deviceId: string) =>
    apiFetch<SipCommandResult>(`/api/gb28181/devices/${deviceId}/queries/device-info`, {
      method: "POST",
    }),
  queryCatalog: (deviceId: string) =>
    apiFetch<SipCommandResult>(`/api/gb28181/devices/${deviceId}/queries/catalog`, {
      method: "POST",
    }),
  queryRecords: (deviceId: string, payload: {
    channelId?: string;
    startTime?: string;
    endTime?: string;
    secrecy?: string;
    type?: string;
  }) =>
    apiFetch<SipCommandResult>(`/api/gb28181/devices/${deviceId}/queries/records`, {
      method: "POST",
      body: JSON.stringify(payload),
    }),
  profile: (deviceId: string) =>
    apiFetch<GbDeviceProfile | null>(`/api/gb28181/devices/${deviceId}/profile`),
  catalog: (deviceId: string) =>
    apiFetch<GbCatalogItem[]>(`/api/gb28181/devices/${deviceId}/catalog`),
  records: (deviceId: string, params?: { channelId?: string; limit?: number }) => {
    const search = new URLSearchParams();
    if (params?.channelId) {
      search.set("channelId", params.channelId);
    }
    if (params?.limit) {
      search.set("limit", String(params.limit));
    }
    const suffix = search.size > 0 ? `?${search.toString()}` : "";
    return apiFetch<GbRecordItem[]>(`/api/gb28181/devices/${deviceId}/records${suffix}`);
  },
  alarms: (params?: { deviceId?: string; limit?: number }) => {
    const search = new URLSearchParams();
    if (params?.deviceId) {
      search.set("deviceId", params.deviceId);
    }
    if (params?.limit) {
      search.set("limit", String(params.limit));
    }
    const suffix = search.size > 0 ? `?${search.toString()}` : "";
    return apiFetch<GbAlarmEvent[]>(`/api/gb28181/alarms${suffix}`);
  },
  mobilePositions: (params?: { deviceId?: string; limit?: number }) => {
    const search = new URLSearchParams();
    if (params?.deviceId) {
      search.set("deviceId", params.deviceId);
    }
    if (params?.limit) {
      search.set("limit", String(params.limit));
    }
    const suffix = search.size > 0 ? `?${search.toString()}` : "";
    return apiFetch<GbMobilePosition[]>(`/api/gb28181/mobile-positions${suffix}`);
  },
  subscribe: (deviceId: string, payload: { eventType: string; expires?: number }) =>
    apiFetch<GbSubscriptionResult>(`/api/gb28181/devices/${deviceId}/subscriptions`, {
      method: "POST",
      body: JSON.stringify(payload),
    }),
  unsubscribe: (id: number) =>
    apiFetch<SipCommandResult>(`/api/gb28181/subscriptions/${id}`, {
      method: "DELETE",
    }),
  subscriptions: (deviceId?: string) =>
    apiFetch<GbSubscription[]>(`/api/gb28181/subscriptions${deviceId ? `?deviceId=${deviceId}` : ""}`),
  playbackSessions: () => apiFetch<GbPlaybackSession[]>("/api/gb28181/playback-sessions"),
};

export const storageApi = {
  getPolicy: () => apiFetch<StoragePolicy>("/api/storage/policy"),
  updatePolicy: (payload: {
    retentionDays: number;
    maxStorageGb: number;
    autoOverwrite: boolean;
    recordEnabled: boolean;
    recordPath: string;
  }) =>
    apiFetch<StoragePolicy>("/api/storage/policy", {
      method: "PUT",
      body: JSON.stringify(payload),
    }),
  usage: () => apiFetch<StorageUsage>("/api/storage/usage"),
  cleanup: () =>
    apiFetch<StorageUsage>("/api/storage/cleanup", {
      method: "POST",
    }),
  backgroundStatus: () =>
    apiFetch<BackgroundRecordingStatus[]>("/api/storage/background-recording/status"),
  records: () => apiFetch<RecordFileItem[]>("/api/records"),
  deleteRecord: (id: number) =>
    apiFetch<void>(`/api/records/${id}`, {
      method: "DELETE",
    }),
};
