import { getToken } from "../../lib/http";
import { isRunningInNativeShell } from "./native-shell";

type NativeDownloadMessage = {
  type: "native-download-file";
  url: string;
  fileName?: string;
  authToken?: string;
  mimeType?: string;
};

type NativeShareMessage = {
  type: "native-share-file";
  url: string;
  fileName?: string;
  authToken?: string;
  mimeType?: string;
  title?: string;
};

type NativePushCenterMessage = {
  type: "native-open-push-center";
};

type NativeBridgeMessage =
  | NativeDownloadMessage
  | NativeShareMessage
  | NativePushCenterMessage;

function resolveAbsoluteUrl(url: string) {
  if (typeof window === "undefined") {
    return url;
  }
  return new URL(url, window.location.origin).toString();
}

function postNativeBridgeMessage(message: NativeBridgeMessage) {
  if (
    typeof window === "undefined" ||
    !window.ReactNativeWebView ||
    typeof window.ReactNativeWebView.postMessage !== "function"
  ) {
    return false;
  }

  window.ReactNativeWebView.postMessage(JSON.stringify(message));
  return true;
}

async function downloadInBrowser(
  url: string,
  fileName?: string,
  authToken?: string,
) {
  const response = await fetch(resolveAbsoluteUrl(url), {
    headers: authToken
      ? {
          Authorization: `Bearer ${authToken}`,
        }
      : {},
  });

  if (!response.ok) {
    throw new Error(`文件下载失败 (${response.status})`);
  }

  const blob = await response.blob();
  const blobUrl = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = blobUrl;
  link.download = fileName || "download";
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(blobUrl), 1000);
}

export async function requestNativeFileDownload(options: {
  url: string;
  fileName?: string;
  mimeType?: string;
}) {
  const authToken = getToken() || undefined;
  const url = resolveAbsoluteUrl(options.url);

  if (
    isRunningInNativeShell() &&
    postNativeBridgeMessage({
      type: "native-download-file",
      url,
      fileName: options.fileName,
      authToken,
      mimeType: options.mimeType,
    })
  ) {
    return "native" as const;
  }

  await downloadInBrowser(url, options.fileName, authToken);
  return "browser" as const;
}

export async function requestNativeFileShare(options: {
  url: string;
  fileName?: string;
  mimeType?: string;
  title?: string;
}) {
  const authToken = getToken() || undefined;
  const url = resolveAbsoluteUrl(options.url);

  if (
    isRunningInNativeShell() &&
    postNativeBridgeMessage({
      type: "native-share-file",
      url,
      fileName: options.fileName,
      authToken,
      mimeType: options.mimeType,
      title: options.title,
    })
  ) {
    return "native" as const;
  }

  await downloadInBrowser(url, options.fileName, authToken);
  return "browser" as const;
}

export function openNativePushCenter() {
  if (!isRunningInNativeShell()) {
    return false;
  }

  return postNativeBridgeMessage({
    type: "native-open-push-center",
  });
}
