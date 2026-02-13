export class ApiError extends Error {
  status: number;
  code: number;

  constructor(message: string, status: number, code: number) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

type ApiEnvelope<T> = {
  code: number;
  message: string;
  data: T;
};

const API_BASE = (import.meta as { env?: Record<string, string> }).env?.VITE_API_BASE ?? "";

export function getToken(): string | null {
  return localStorage.getItem("accessToken");
}

export function setToken(token: string) {
  localStorage.setItem("accessToken", token);
}

export function clearToken() {
  localStorage.removeItem("accessToken");
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getToken();
  const headers = new Headers(init.headers);
  headers.set("Content-Type", "application/json");
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers,
  });

  let payload: ApiEnvelope<T> | null = null;
  try {
    payload = (await response.json()) as ApiEnvelope<T>;
  } catch {
    payload = null;
  }

  if (!response.ok || !payload || payload.code !== 0) {
    const message = payload?.message || `请求失败 (${response.status})`;
    const code = payload?.code ?? response.status;
    if (response.status === 401) {
      clearToken();
    }
    throw new ApiError(message, response.status, code);
  }

  return payload.data;
}
