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
  records: () => apiFetch<RecordFileItem[]>("/api/records"),
  deleteRecord: (id: number) =>
    apiFetch<void>(`/api/records/${id}`, {
      method: "DELETE",
    }),
};
