import { useEffect } from "react";

export type NativeShellState = {
  title: string;
  canGoBack?: boolean;
  path?: string;
};

type NativeShellMessage = {
  type: "shell-state";
  title: string;
  canGoBack: boolean;
  path: string;
};

declare global {
  interface Window {
    ReactNativeWebView?: {
      postMessage: (message: string) => void;
    };
  }
}

function getWindowSearch(search?: string) {
  if (search !== undefined) {
    return search;
  }
  if (typeof window === "undefined") {
    return "";
  }
  return window.location.search;
}

export function isRunningInNativeShell(search?: string) {
  if (typeof window === "undefined") {
    return false;
  }
  if (
    window.ReactNativeWebView &&
    typeof window.ReactNativeWebView.postMessage === "function"
  ) {
    return true;
  }
  const params = new URLSearchParams(getWindowSearch(search));
  return params.get("shell") === "native" || params.get("embed") === "1";
}

function pickShellParams(search?: string) {
  const params = new URLSearchParams(getWindowSearch(search));
  const shellParams = new URLSearchParams();

  const embed = params.get("embed");
  const shell = params.get("shell");

  if (embed) {
    shellParams.set("embed", embed);
  }
  if (shell) {
    shellParams.set("shell", shell);
  }

  return shellParams;
}

export function appendShellSearch(path: string, search?: string) {
  const [pathname, rawQuery = ""] = path.split("?");
  const targetParams = new URLSearchParams(rawQuery);
  const shellParams = pickShellParams(search);

  for (const [key, value] of shellParams.entries()) {
    if (!targetParams.has(key)) {
      targetParams.set(key, value);
    }
  }

  const query = targetParams.toString();
  return query ? `${pathname}?${query}` : pathname;
}

export function syncNativeShellState({
  title,
  canGoBack = false,
  path,
}: NativeShellState) {
  if (!isRunningInNativeShell() || typeof window === "undefined") {
    return;
  }

  const message: NativeShellMessage = {
    type: "shell-state",
    title,
    canGoBack,
    path: path || `${window.location.pathname}${window.location.search}`,
  };

  window.ReactNativeWebView?.postMessage(JSON.stringify(message));
}

export function useNativeShellState(state: NativeShellState) {
  useEffect(() => {
    syncNativeShellState(state);
  }, [state.canGoBack, state.path, state.title]);
}
