import AsyncStorage from "@react-native-async-storage/async-storage";
import Constants from "expo-constants";
import * as Device from "expo-device";
import { File, Paths } from "expo-file-system";
import * as Notifications from "expo-notifications";
import * as Sharing from "expo-sharing";
import { StatusBar } from "expo-status-bar";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ActivityIndicator,
  BackHandler,
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { WebView } from "react-native-webview";

const DEFAULT_LAN_EMBED_HOST = "http://192.168.6.230:5173";
const ADDRESS_STORAGE_KEY = "gb28181.embed-base-url";
const DEFAULT_PAGE_TITLE = "视频巡检与管理";

type ShellStateMessage = {
  type: "shell-state";
  title?: string;
  canGoBack?: boolean;
  path?: string;
};

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

type AuthStateMessage = {
  type: "auth-state";
  token: string | null;
  username?: string | null;
  role?: string | null;
};

type WebViewMessage =
  | ShellStateMessage
  | NativeDownloadMessage
  | NativeShareMessage
  | NativePushCenterMessage
  | AuthStateMessage;

type NativeEventItem = {
  id: string;
  title: string;
  detail: string;
  createdAt: string;
};

type ApiResult<T> = {
  code: number;
  message: string;
  data: T;
};

type RegisteredPushToken = {
  id: number;
  userId: number;
  username: string;
  token: string;
  tokenType: string;
  platform: string;
  deviceName?: string | null;
  appVersion?: string | null;
  permissionStatus?: string | null;
  projectId?: string | null;
  createdAt: string;
  updatedAt: string;
  lastSeenAt?: string | null;
};

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: false,
    shouldSetBadge: false,
  }),
});

function normalizeBaseUrl(rawValue: string) {
  const trimmed = rawValue.trim();

  if (!trimmed) {
    return "";
  }

  const normalized = /^https?:\/\//i.test(trimmed)
    ? trimmed
    : `http://${trimmed}`;

  return normalized.replace(/\/+$/, "");
}

function getDefaultBaseUrl() {
  const envUrl = normalizeBaseUrl(
    process.env.EXPO_PUBLIC_GB28181_EMBED_URL || DEFAULT_LAN_EMBED_HOST,
  );
  return envUrl || DEFAULT_LAN_EMBED_HOST;
}

function buildEmbedUrl(baseUrl: string, reloadToken: number) {
  const normalized = normalizeBaseUrl(baseUrl);

  if (!normalized) {
    return null;
  }

  let routeUrl = normalized;
  if (routeUrl.endsWith("/m")) {
    routeUrl = `${routeUrl}/home`;
  } else if (!routeUrl.endsWith("/m/home")) {
    routeUrl = `${routeUrl}/m/home`;
  }

  const joiner = routeUrl.includes("?") ? "&" : "?";
  const cacheBust = __DEV__ ? `&ts=${reloadToken}` : "";
  return `${routeUrl}${joiner}embed=1&shell=native${cacheBust}`;
}

function safeHostLabel(baseUrl: string) {
  return normalizeBaseUrl(baseUrl).replace(/^https?:\/\//i, "");
}

function parseWebViewMessage(rawMessage: string): WebViewMessage | null {
  try {
    return JSON.parse(rawMessage) as WebViewMessage;
  } catch {
    return null;
  }
}

function sanitizeFileName(fileName?: string) {
  const safeName = (fileName || `gb28181-${Date.now()}`)
    .replace(/[<>:"/\\|?*\u0000-\u001F]/g, "-")
    .replace(/\s+/g, "-");
  return safeName || `gb28181-${Date.now()}`;
}

function previewToken(token?: string | null) {
  if (!token) {
    return "未获取";
  }
  if (token.length <= 24) {
    return token;
  }
  return `${token.slice(0, 12)}...${token.slice(-8)}`;
}

async function parseApiResponse<T>(response: Response): Promise<T> {
  const rawText = await response.text();
  let payload: ApiResult<T> | null = null;

  if (rawText) {
    try {
      payload = JSON.parse(rawText) as ApiResult<T>;
    } catch {
      payload = null;
    }
  }

  if (!response.ok || !payload || payload.code !== 0) {
    throw new Error(payload?.message || rawText || `请求失败 (${response.status})`);
  }

  return payload.data;
}

function resolvePermissionStatus(permission: unknown) {
  const record = permission as {
    status?: string;
    granted?: boolean;
    canAskAgain?: boolean;
  };

  if (typeof record.status === "string") {
    return record.status;
  }
  if (record.granted === true) {
    return Notifications.PermissionStatus.GRANTED;
  }
  if (record.canAskAgain === false) {
    return Notifications.PermissionStatus.DENIED;
  }
  return Notifications.PermissionStatus.UNDETERMINED;
}

export function GbWebViewScreen() {
  const webViewRef = useRef<WebView>(null);
  const flashTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const lastSyncedPushKeyRef = useRef<string | null>(null);

  const [configReady, setConfigReady] = useState(false);
  const [baseUrl, setBaseUrl] = useState(getDefaultBaseUrl());
  const [draftUrl, setDraftUrl] = useState(getDefaultBaseUrl());
  const [settingsVisible, setSettingsVisible] = useState(false);
  const [pushCenterVisible, setPushCenterVisible] = useState(false);
  const [settingsError, setSettingsError] = useState<string | null>(null);
  const [shellTitle, setShellTitle] = useState(DEFAULT_PAGE_TITLE);
  const [shellPath, setShellPath] = useState("/m/home");
  const [shellCanGoBack, setShellCanGoBack] = useState(false);
  const [browserCanGoBack, setBrowserCanGoBack] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(Date.now());
  const [flashMessage, setFlashMessage] = useState<string | null>(null);
  const [authToken, setAuthToken] = useState<string | null>(null);
  const [authUsername, setAuthUsername] = useState<string | null>(null);
  const [authRole, setAuthRole] = useState<string | null>(null);
  const [pushPermissionStatus, setPushPermissionStatus] = useState("unknown");
  const [expoPushToken, setExpoPushToken] = useState<string | null>(null);
  const [devicePushToken, setDevicePushToken] = useState<string | null>(null);
  const [pushSyncState, setPushSyncState] = useState("等待登录后注册 Push");
  const [pushSyncError, setPushSyncError] = useState<string | null>(null);
  const [registeringPush, setRegisteringPush] = useState(false);
  const [loadingPushTokens, setLoadingPushTokens] = useState(false);
  const [registeredPushTokens, setRegisteredPushTokens] = useState<
    RegisteredPushToken[]
  >([]);
  const [nativeEvents, setNativeEvents] = useState<NativeEventItem[]>([
    {
      id: "native-shell-ready",
      title: "原生消息中心已启用",
      detail: "已支持 Push 注册、本地测试通知、文件下载与分享回执。",
      createdAt: new Date().toISOString(),
    },
  ]);

  useEffect(() => {
    let mounted = true;

    const loadStoredAddress = async () => {
      try {
        const stored = await AsyncStorage.getItem(ADDRESS_STORAGE_KEY);
        const nextBaseUrl = normalizeBaseUrl(stored || getDefaultBaseUrl());
        if (!mounted) {
          return;
        }
        setBaseUrl(nextBaseUrl || getDefaultBaseUrl());
        setDraftUrl(nextBaseUrl || getDefaultBaseUrl());
      } finally {
        if (mounted) {
          setConfigReady(true);
        }
      }
    };

    loadStoredAddress();

    return () => {
      mounted = false;
    };
  }, []);

  useEffect(() => {
    return () => {
      if (flashTimeoutRef.current) {
        clearTimeout(flashTimeoutRef.current);
      }
    };
  }, []);

  const embedUrl = useMemo(
    () => (configReady ? buildEmbedUrl(baseUrl, reloadToken) : null),
    [baseUrl, configReady, reloadToken],
  );
  const hostLabel = useMemo(() => safeHostLabel(baseUrl), [baseUrl]);
  const canGoBack = shellCanGoBack || browserCanGoBack;
  const projectId = useMemo(() => {
    const envProjectId = process.env.EXPO_PUBLIC_EXPO_PROJECT_ID?.trim();
    const configProjectId = Constants.easConfig?.projectId;
    const extraProjectId = (
      Constants.expoConfig?.extra as { eas?: { projectId?: string } } | undefined
    )?.eas?.projectId;

    return envProjectId || configProjectId || extraProjectId || null;
  }, []);

  const openSettings = useCallback(() => {
    setDraftUrl(baseUrl);
    setSettingsError(null);
    setSettingsVisible(true);
  }, [baseUrl]);

  const pushNativeEvent = useCallback((title: string, detail: string) => {
    setNativeEvents((current) => [
      {
        id: `${Date.now()}-${Math.random().toString(16).slice(2, 8)}`,
        title,
        detail,
        createdAt: new Date().toISOString(),
      },
      ...current,
    ].slice(0, 12));
  }, []);

  const showFlash = useCallback((message: string) => {
    setFlashMessage(message);
    if (flashTimeoutRef.current) {
      clearTimeout(flashTimeoutRef.current);
    }
    flashTimeoutRef.current = setTimeout(() => {
      setFlashMessage(null);
    }, 2400);
  }, []);

  const reloadPage = useCallback(() => {
    setError(null);
    setLoading(true);
    setReloadToken(Date.now());
    webViewRef.current?.reload();
  }, []);

  const goBack = useCallback(() => {
    if (shellCanGoBack) {
      webViewRef.current?.injectJavaScript("window.history.back(); true;");
      return true;
    }

    if (browserCanGoBack) {
      webViewRef.current?.goBack();
      return true;
    }

    return false;
  }, [browserCanGoBack, shellCanGoBack]);

  useEffect(() => {
    const subscription = BackHandler.addEventListener(
      "hardwareBackPress",
      () => goBack(),
    );

    return () => subscription.remove();
  }, [goBack]);

  useEffect(() => {
    const receivedSubscription = Notifications.addNotificationReceivedListener(
      (notification) => {
        const title = notification.request.content.title || "收到系统通知";
        const detail =
          notification.request.content.body ||
          `当前页面 ${shellPath} 收到一条通知`;
        pushNativeEvent(title, detail);
        showFlash(title);
      },
    );

    const responseSubscription =
      Notifications.addNotificationResponseReceivedListener((response) => {
        const content = response.notification.request.content;
        pushNativeEvent(
          "已打开通知",
          content.title || content.body || "用户从系统通知进入了 App。",
        );
        setPushCenterVisible(true);
      });

    return () => {
      receivedSubscription.remove();
      responseSubscription.remove();
    };
  }, [pushNativeEvent, shellPath, showFlash]);

  const saveAddress = useCallback(async () => {
    const normalized = normalizeBaseUrl(draftUrl);

    if (!normalized) {
      setSettingsError("请输入服务器地址");
      return;
    }

    try {
      new URL(normalized);
    } catch {
      setSettingsError("地址格式无效，请检查协议、IP 和端口");
      return;
    }

    await AsyncStorage.setItem(ADDRESS_STORAGE_KEY, normalized);
    setBaseUrl(normalized);
    setDraftUrl(normalized);
    setSettingsError(null);
    setSettingsVisible(false);
    setShellTitle(DEFAULT_PAGE_TITLE);
    setShellPath("/m/home");
    pushNativeEvent("服务器地址已更新", `当前地址 ${safeHostLabel(normalized)}`);
    showFlash("服务器地址已保存");
    reloadPage();
  }, [draftUrl, pushNativeEvent, reloadPage, showFlash]);

  const downloadRemoteFile = useCallback(
    async (options: {
      url: string;
      fileName?: string;
      authToken?: string;
      mimeType?: string;
    }) => {
      const targetFile = new File(Paths.cache, sanitizeFileName(options.fileName));
      const headers = options.authToken
        ? {
            Authorization: `Bearer ${options.authToken}`,
          }
        : undefined;

      return File.downloadFileAsync(options.url, targetFile, {
        headers,
        idempotent: true,
      });
    },
    [],
  );

  const handleNativeDownload = useCallback(
    async (message: NativeDownloadMessage) => {
      try {
        const file = await downloadRemoteFile(message);
        pushNativeEvent(
          "文件已准备好",
          message.fileName || "下载文件已缓存，可在系统面板中保存或发送。",
        );
        showFlash("文件已准备好");

        if (await Sharing.isAvailableAsync()) {
          await Sharing.shareAsync(file.uri, {
            mimeType: message.mimeType,
          });
        }
      } catch (downloadError) {
        const text =
          downloadError instanceof Error
            ? downloadError.message
            : "原生下载失败";
        pushNativeEvent("原生下载失败", text);
        showFlash("原生下载失败");
      }
    },
    [downloadRemoteFile, pushNativeEvent, showFlash],
  );

  const handleNativeShare = useCallback(
    async (message: NativeShareMessage) => {
      try {
        const file = await downloadRemoteFile(message);

        if (await Sharing.isAvailableAsync()) {
          await Sharing.shareAsync(file.uri, {
            mimeType: message.mimeType,
          });
          pushNativeEvent(
            "已打开系统分享面板",
            message.title || message.fileName || "文件已交给系统分享。",
          );
          showFlash("已打开系统分享面板");
          return;
        }

        pushNativeEvent("当前设备不支持分享", "请在系统文件中查看该文件。");
        showFlash("当前设备不支持分享");
      } catch (shareError) {
        const text =
          shareError instanceof Error ? shareError.message : "原生分享失败";
        pushNativeEvent("原生分享失败", text);
        showFlash("原生分享失败");
      }
    },
    [downloadRemoteFile, pushNativeEvent, showFlash],
  );

  const loadRegisteredTokens = useCallback(async () => {
    if (!authToken) {
      setRegisteredPushTokens([]);
      return;
    }

    try {
      setLoadingPushTokens(true);
      const response = await fetch(
        `${normalizeBaseUrl(baseUrl)}/api/app/push-tokens`,
        {
          headers: {
            Accept: "application/json",
            Authorization: `Bearer ${authToken}`,
          },
        },
      );
      const data = await parseApiResponse<RegisteredPushToken[]>(response);
      setRegisteredPushTokens(data);

      if (data.length === 0 && !expoPushToken && !devicePushToken) {
        setPushSyncState("已登录，尚未注册 Push token");
      }
    } catch (loadError) {
      const text =
        loadError instanceof Error ? loadError.message : "读取 Push token 失败";
      setPushSyncError(text);
    } finally {
      setLoadingPushTokens(false);
    }
  }, [authToken, baseUrl, devicePushToken, expoPushToken]);

  const syncPushTokenToServer = useCallback(
    async (token: string, tokenType: "EXPO" | "DEVICE") => {
      if (!authToken) {
        setPushSyncState("Push token 已获取，等待 H5 登录态同步");
        return;
      }

      try {
        setPushSyncError(null);
        setPushSyncState("正在同步 Push token 到服务端...");
        const response = await fetch(
          `${normalizeBaseUrl(baseUrl)}/api/app/push-tokens`,
          {
            method: "POST",
            headers: {
              Accept: "application/json",
              "Content-Type": "application/json",
              Authorization: `Bearer ${authToken}`,
            },
            body: JSON.stringify({
              token,
              tokenType,
              platform: Platform.OS,
              deviceName: Device.deviceName || Device.modelName || "未知设备",
              appVersion: Constants.expoConfig?.version || "1.0.0",
              permissionStatus: pushPermissionStatus,
              projectId,
            }),
          },
        );
        const savedToken = await parseApiResponse<RegisteredPushToken>(response);
        setPushSyncState(
          `已同步到服务端：${savedToken.tokenType} / ${savedToken.platform}`,
        );
        pushNativeEvent(
          "Push token 已同步",
          `${savedToken.deviceName || "当前设备"} 已登记到服务端。`,
        );
        showFlash("Push token 已同步");
        void loadRegisteredTokens();
      } catch (syncError) {
        lastSyncedPushKeyRef.current = null;
        const text =
          syncError instanceof Error ? syncError.message : "Push token 同步失败";
        setPushSyncError(text);
        setPushSyncState("Push token 同步失败");
        pushNativeEvent("Push token 同步失败", text);
        showFlash("Push token 同步失败");
      }
    },
    [
      authToken,
      baseUrl,
      loadRegisteredTokens,
      projectId,
      pushNativeEvent,
      pushPermissionStatus,
      showFlash,
    ],
  );

  const registerForPushNotifications = useCallback(async () => {
    try {
      setRegisteringPush(true);
      setPushSyncError(null);

      if (Platform.OS === "android") {
        await Notifications.setNotificationChannelAsync("default", {
          name: "default",
          importance: Notifications.AndroidImportance.DEFAULT,
        });
      }

      const currentPermission = await Notifications.getPermissionsAsync();
      let finalStatus = resolvePermissionStatus(currentPermission);
      if (finalStatus !== Notifications.PermissionStatus.GRANTED) {
        const requestedPermission =
          await Notifications.requestPermissionsAsync();
        finalStatus = resolvePermissionStatus(requestedPermission);
      }

      setPushPermissionStatus(finalStatus);
      if (finalStatus !== Notifications.PermissionStatus.GRANTED) {
        setPushSyncState("通知权限未授予");
        setPushSyncError("系统未授予通知权限，无法继续注册远程 Push。");
        pushNativeEvent(
          "通知权限未授予",
          "请在系统设置中开启通知后再次尝试。",
        );
        showFlash("通知权限未授予");
        return;
      }

      let nextDevicePushToken: string | null = null;
      let nextExpoPushToken: string | null = null;

      if (Device.isDevice) {
        try {
          const rawDeviceToken = await Notifications.getDevicePushTokenAsync();
          nextDevicePushToken =
            typeof rawDeviceToken.data === "string"
              ? rawDeviceToken.data
              : JSON.stringify(rawDeviceToken.data);
          setDevicePushToken(nextDevicePushToken);
        } catch (deviceTokenError) {
          const text =
            deviceTokenError instanceof Error
              ? deviceTokenError.message
              : "设备 Push token 获取失败";
          setPushSyncError(text);
        }

        if (projectId) {
          try {
            const expoToken = await Notifications.getExpoPushTokenAsync({
              projectId,
            });
            nextExpoPushToken = expoToken.data;
            setExpoPushToken(expoToken.data);
          } catch (expoTokenError) {
            const text =
              expoTokenError instanceof Error
                ? expoTokenError.message
                : "Expo Push token 获取失败";
            setPushSyncError(text);
          }
        } else {
          pushNativeEvent(
            "未配置 Expo projectId",
            "本地通知可继续测试，若要使用 Expo Push，请在 app 配置中补充 projectId。",
          );
        }
      } else {
        setPushSyncState("当前是模拟器，可测试本地通知；远程 Push 需真机");
        pushNativeEvent(
          "当前是模拟器",
          "已可测试本地通知，远程 Push 注册需要真机环境。",
        );
      }

      const tokenReady = nextExpoPushToken || nextDevicePushToken;
      if (tokenReady) {
        setPushSyncState("Push token 已获取，准备同步到服务端");
        setPushSyncError(null);
        showFlash("Push token 已获取");
      } else if (Device.isDevice) {
        setPushSyncState("已授权通知，但暂未获取到可用 Push token");
      }
    } finally {
      setRegisteringPush(false);
    }
  }, [projectId, pushNativeEvent, showFlash]);

  const sendTestNotification = useCallback(async () => {
    if (pushPermissionStatus !== Notifications.PermissionStatus.GRANTED) {
      setPushSyncError("请先申请通知权限，然后再发送测试通知。");
      showFlash("请先申请通知权限");
      return;
    }

    try {
      await Notifications.scheduleNotificationAsync({
        content: {
          title: "GB28181 App",
          body: `当前页面：${shellTitle}`,
          data: {
            shellPath,
          },
        },
        trigger: null,
      });
      pushNativeEvent("测试通知已发送", `当前页面：${shellPath}`);
      showFlash("测试通知已发送");
    } catch (notificationError) {
      const text =
        notificationError instanceof Error
          ? notificationError.message
          : "测试通知发送失败";
      setPushSyncError(text);
      pushNativeEvent("测试通知发送失败", text);
      showFlash("测试通知发送失败");
    }
  }, [pushNativeEvent, pushPermissionStatus, shellPath, shellTitle, showFlash]);

  useEffect(() => {
    if (!authToken) {
      setRegisteredPushTokens([]);
      lastSyncedPushKeyRef.current = null;
      if (expoPushToken || devicePushToken) {
        setPushSyncState("Push token 已获取，等待 H5 登录态同步");
      } else {
        setPushSyncState("等待登录后注册 Push");
      }
      return;
    }

    const token = expoPushToken || devicePushToken;
    if (!token) {
      void loadRegisteredTokens();
      return;
    }

    const tokenType = expoPushToken ? "EXPO" : "DEVICE";
    const syncKey = `${authToken}:${tokenType}:${token}`;
    if (lastSyncedPushKeyRef.current === syncKey) {
      return;
    }

    lastSyncedPushKeyRef.current = syncKey;
    void syncPushTokenToServer(token, tokenType);
  }, [
    authToken,
    devicePushToken,
    expoPushToken,
    loadRegisteredTokens,
    syncPushTokenToServer,
  ]);

  useEffect(() => {
    if (!pushCenterVisible || !authToken) {
      return;
    }
    void loadRegisteredTokens();
  }, [authToken, loadRegisteredTokens, pushCenterVisible]);

  const handleWebViewMessage = useCallback(
    async (rawMessage: string) => {
      const payload = parseWebViewMessage(rawMessage);
      if (!payload) {
        return;
      }

      if (payload.type === "shell-state") {
        setShellTitle(payload.title || DEFAULT_PAGE_TITLE);
        setShellCanGoBack(Boolean(payload.canGoBack));
        setShellPath(payload.path || "/m/home");
        return;
      }

      if (payload.type === "auth-state") {
        setAuthToken(payload.token ?? null);
        setAuthUsername(payload.username ?? null);
        setAuthRole(payload.role ?? null);
        setPushSyncError(null);

        if (!payload.token) {
          lastSyncedPushKeyRef.current = null;
          setRegisteredPushTokens([]);
        }
        return;
      }

      if (payload.type === "native-open-push-center") {
        setPushCenterVisible(true);
        return;
      }

      if (payload.type === "native-download-file") {
        await handleNativeDownload(payload);
        return;
      }

      if (payload.type === "native-share-file") {
        await handleNativeShare(payload);
      }
    },
    [handleNativeDownload, handleNativeShare],
  );

  return (
    <>
      <SafeAreaView edges={["top", "left", "right"]} style={styles.safeArea}>
        <StatusBar style="dark" />

        <View style={styles.header}>
          <Pressable
            disabled={!canGoBack}
            onPress={goBack}
            style={[
              styles.headerAction,
              !canGoBack && styles.headerActionDisabled,
            ]}
          >
            <Text
              style={[
                styles.headerActionText,
                !canGoBack && styles.headerActionTextDisabled,
              ]}
            >
              返回
            </Text>
          </Pressable>

          <View style={styles.headerCenter}>
            <Text numberOfLines={1} style={styles.headerTitle}>
              {shellTitle}
            </Text>
            <Text numberOfLines={1} style={styles.headerSubtitle}>
              {hostLabel || DEFAULT_LAN_EMBED_HOST.replace(/^https?:\/\//i, "")}
            </Text>
          </View>

          <View style={styles.headerActionsRight}>
            <Pressable
              onPress={() => setPushCenterVisible(true)}
              style={styles.headerAction}
            >
              <Text style={styles.headerActionText}>消息</Text>
            </Pressable>
            <Pressable onPress={openSettings} style={styles.headerAction}>
              <Text style={styles.headerActionText}>配置</Text>
            </Pressable>
          </View>
        </View>

        <View style={styles.container}>
          {!configReady ? (
            <View style={styles.stateWrap}>
              <ActivityIndicator color="#1D4ED8" size="small" />
              <Text style={styles.stateText}>正在读取 App 配置...</Text>
            </View>
          ) : !embedUrl ? (
            <View style={styles.stateWrap}>
              <Text style={styles.stateTitle}>未配置服务器地址</Text>
              <Text style={styles.stateText}>
                请先在配置页填写前端可访问地址，例如
                `http://192.168.6.230:5173`
              </Text>
              <Pressable onPress={openSettings} style={styles.primaryAction}>
                <Text style={styles.primaryActionText}>打开配置页</Text>
              </Pressable>
            </View>
          ) : (
            <>
              <WebView
                allowsInlineMediaPlayback
                bounces={false}
                cacheEnabled={false}
                domStorageEnabled
                hideKeyboardAccessoryView
                javaScriptEnabled
                key={embedUrl}
                mediaPlaybackRequiresUserAction={false}
                onError={(event) => {
                  setLoading(false);
                  setError(event.nativeEvent.description || "WebView 加载失败");
                }}
                onHttpError={(event) => {
                  setLoading(false);
                  setError(`WebView HTTP ${event.nativeEvent.statusCode}`);
                }}
                onLoadEnd={() => {
                  setLoading(false);
                }}
                onLoadStart={() => {
                  setLoading(true);
                  setError(null);
                }}
                onMessage={(event) => {
                  void handleWebViewMessage(event.nativeEvent.data);
                }}
                onNavigationStateChange={(event) => {
                  setBrowserCanGoBack(event.canGoBack);
                }}
                originWhitelist={["*"]}
                pullToRefreshEnabled
                ref={webViewRef}
                setSupportMultipleWindows={false}
                source={{ uri: embedUrl }}
                style={styles.webview}
              />

              {loading ? (
                <View pointerEvents="none" style={styles.loadingOverlay}>
                  <ActivityIndicator color="#1D4ED8" size="small" />
                  <Text style={styles.loadingText}>正在连接移动端页面...</Text>
                </View>
              ) : null}

              {error ? (
                <View style={styles.errorOverlay}>
                  <Text style={styles.stateTitle}>页面暂时不可用</Text>
                  <Text style={styles.stateText}>{error}</Text>
                  <Text style={styles.stateHint}>
                    当前地址：{embedUrl}
                  </Text>
                  <View style={styles.errorActions}>
                    <Pressable onPress={reloadPage} style={styles.primaryAction}>
                      <Text style={styles.primaryActionText}>重新加载</Text>
                    </Pressable>
                    <Pressable onPress={openSettings} style={styles.secondaryAction}>
                      <Text style={styles.secondaryActionText}>修改地址</Text>
                    </Pressable>
                  </View>
                </View>
              ) : null}

              {flashMessage ? (
                <View pointerEvents="none" style={styles.flashBanner}>
                  <Text style={styles.flashBannerText}>{flashMessage}</Text>
                </View>
              ) : null}
            </>
          )}
        </View>
      </SafeAreaView>

      <Modal
        animationType="slide"
        onRequestClose={() => setSettingsVisible(false)}
        presentationStyle="fullScreen"
        visible={settingsVisible}
      >
        <SafeAreaView
          edges={["top", "bottom", "left", "right"]}
          style={styles.modalSafeArea}
        >
          <KeyboardAvoidingView
            behavior={Platform.OS === "ios" ? "padding" : undefined}
            style={styles.modalSafeArea}
          >
            <View style={styles.modalHeader}>
              <Text style={styles.modalTitle}>服务器地址配置</Text>
              <Pressable
                onPress={() => setSettingsVisible(false)}
                style={styles.modalClose}
              >
                <Text style={styles.modalCloseText}>关闭</Text>
              </Pressable>
            </View>

            <ScrollView
              contentContainerStyle={styles.modalContent}
              keyboardShouldPersistTaps="handled"
            >
              <View style={styles.tipCard}>
                <Text style={styles.tipTitle}>当前接入方式</Text>
                <Text style={styles.tipText}>
                  App 会用 WebView 打开移动端 H5，所以这里需要填写能直接访问前端页面的地址，而不是后端 API 地址。
                </Text>
                <Text style={styles.tipHint}>当前页面：{shellPath}</Text>
              </View>

              <View style={styles.fieldGroup}>
                <Text style={styles.fieldLabel}>移动端前端地址</Text>
                <TextInput
                  autoCapitalize="none"
                  autoCorrect={false}
                  keyboardType="url"
                  onChangeText={(text) => {
                    setDraftUrl(text);
                    if (settingsError) {
                      setSettingsError(null);
                    }
                  }}
                  placeholder="http://192.168.6.230:5173"
                  placeholderTextColor="#94A3B8"
                  style={styles.input}
                  value={draftUrl}
                />
                <Text style={styles.fieldHint}>
                  支持填写完整地址，也可以直接写 `192.168.6.230:5173`。
                </Text>
                {settingsError ? (
                  <Text style={styles.errorText}>{settingsError}</Text>
                ) : null}
              </View>

              <View style={styles.quickActions}>
                <Pressable
                  onPress={() => {
                    setDraftUrl(getDefaultBaseUrl());
                    setSettingsError(null);
                  }}
                  style={styles.secondaryAction}
                >
                  <Text style={styles.secondaryActionText}>恢复默认</Text>
                </Pressable>
                <Pressable
                  onPress={() => {
                    setDraftUrl(baseUrl);
                    setSettingsError(null);
                  }}
                  style={styles.secondaryAction}
                >
                  <Text style={styles.secondaryActionText}>使用当前地址</Text>
                </Pressable>
              </View>
            </ScrollView>

            <View style={styles.modalFooter}>
              <Pressable onPress={saveAddress} style={styles.primaryAction}>
                <Text style={styles.primaryActionText}>保存并重载</Text>
              </Pressable>
            </View>
          </KeyboardAvoidingView>
        </SafeAreaView>
      </Modal>

      <Modal
        animationType="slide"
        onRequestClose={() => setPushCenterVisible(false)}
        presentationStyle="pageSheet"
        visible={pushCenterVisible}
      >
        <SafeAreaView
          edges={["top", "bottom", "left", "right"]}
          style={styles.modalSafeArea}
        >
          <View style={styles.modalHeader}>
            <Text style={styles.modalTitle}>原生消息中心</Text>
            <Pressable
              onPress={() => setPushCenterVisible(false)}
              style={styles.modalClose}
            >
              <Text style={styles.modalCloseText}>关闭</Text>
            </Pressable>
          </View>

          <ScrollView contentContainerStyle={styles.modalContent}>
            <View style={styles.tipCard}>
              <Text style={styles.tipTitle}>原生消息与 Push 状态</Text>
              <Text style={styles.tipText}>
                这里会直接显示当前登录态、通知权限、Push token、服务端同步状态，以及最近的原生通知事件。
              </Text>
              <Text style={styles.tipHint}>
                当前页面：{shellPath} / 项目 ID：{projectId || "未配置"}
              </Text>
            </View>

            <View style={styles.statusGrid}>
              <View style={styles.statusCard}>
                <Text style={styles.statusLabel}>当前登录</Text>
                <Text style={styles.statusValue}>
                  {authUsername ? `${authUsername}${authRole ? ` / ${authRole}` : ""}` : "未登录"}
                </Text>
              </View>
              <View style={styles.statusCard}>
                <Text style={styles.statusLabel}>通知权限</Text>
                <Text style={styles.statusValue}>{pushPermissionStatus}</Text>
              </View>
            </View>

            <View style={styles.tokenCard}>
              <Text style={styles.sectionTitle}>Push Token</Text>
              <View style={styles.tokenRow}>
                <Text style={styles.tokenLabel}>Expo Token</Text>
                <Text style={styles.tokenValue}>{previewToken(expoPushToken)}</Text>
              </View>
              <View style={styles.tokenRow}>
                <Text style={styles.tokenLabel}>Device Token</Text>
                <Text style={styles.tokenValue}>{previewToken(devicePushToken)}</Text>
              </View>
              <View style={styles.tokenRow}>
                <Text style={styles.tokenLabel}>服务端同步</Text>
                <Text style={styles.tokenValue}>{pushSyncState}</Text>
              </View>
              {pushSyncError ? (
                <Text style={styles.tokenError}>{pushSyncError}</Text>
              ) : null}
            </View>

            <View style={styles.inlineActions}>
              <Pressable
                onPress={() => {
                  void registerForPushNotifications();
                }}
                style={styles.primaryAction}
              >
                <Text style={styles.primaryActionText}>
                  {registeringPush ? "注册中..." : "申请权限并注册 Push"}
                </Text>
              </Pressable>
              <Pressable
                onPress={() => {
                  void sendTestNotification();
                }}
                style={styles.secondaryAction}
              >
                <Text style={styles.secondaryActionText}>发送测试通知</Text>
              </Pressable>
              <Pressable onPress={reloadPage} style={styles.secondaryAction}>
                <Text style={styles.secondaryActionText}>刷新当前页面</Text>
              </Pressable>
              <Pressable onPress={openSettings} style={styles.secondaryAction}>
                <Text style={styles.secondaryActionText}>打开服务器配置</Text>
              </Pressable>
            </View>

            <View style={styles.feedSection}>
              <Text style={styles.sectionTitle}>
                服务端登记记录
                {loadingPushTokens ? " · 读取中" : ""}
              </Text>
              {registeredPushTokens.length === 0 ? (
                <View style={styles.feedEmptyCard}>
                  <Text style={styles.feedDetail}>
                    还没有读取到服务端登记记录。先登录 H5，再点击“申请权限并注册 Push”。
                  </Text>
                </View>
              ) : (
                registeredPushTokens.map((item) => (
                  <View key={item.id} style={styles.feedCard}>
                    <Text style={styles.feedTitle}>
                      {item.tokenType} / {item.platform}
                    </Text>
                    <Text style={styles.feedDetail}>
                      {item.deviceName || "未知设备"} · {previewToken(item.token)}
                    </Text>
                    <Text style={styles.feedTime}>
                      最近更新 {new Date(item.updatedAt).toLocaleString("zh-CN")}
                    </Text>
                  </View>
                ))
              )}
            </View>

            <View style={styles.feedSection}>
              <Text style={styles.sectionTitle}>最近原生事件</Text>
              {nativeEvents.map((item) => (
                <View key={item.id} style={styles.feedCard}>
                  <Text style={styles.feedTitle}>{item.title}</Text>
                  <Text style={styles.feedDetail}>{item.detail}</Text>
                  <Text style={styles.feedTime}>
                    {new Date(item.createdAt).toLocaleString("zh-CN")}
                  </Text>
                </View>
              ))}
            </View>
          </ScrollView>
        </SafeAreaView>
      </Modal>
    </>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: "#F8FAFC",
  },
  container: {
    flex: 1,
    backgroundColor: "#F8FAFC",
  },
  header: {
    flexDirection: "row",
    alignItems: "center",
    gap: 12,
    borderBottomWidth: 1,
    borderBottomColor: "#E2E8F0",
    backgroundColor: "rgba(255, 255, 255, 0.98)",
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  headerAction: {
    minWidth: 56,
    alignItems: "center",
    justifyContent: "center",
    borderRadius: 999,
    backgroundColor: "#E8EEF8",
    paddingHorizontal: 12,
    paddingVertical: 10,
  },
  headerActionDisabled: {
    backgroundColor: "#F1F5F9",
  },
  headerActionText: {
    fontSize: 13,
    fontWeight: "600",
    color: "#1E293B",
  },
  headerActionTextDisabled: {
    color: "#94A3B8",
  },
  headerCenter: {
    flex: 1,
    minWidth: 0,
  },
  headerActionsRight: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
  },
  headerTitle: {
    fontSize: 17,
    fontWeight: "700",
    color: "#0F172A",
  },
  headerSubtitle: {
    marginTop: 2,
    fontSize: 12,
    color: "#64748B",
  },
  webview: {
    flex: 1,
    backgroundColor: "#FFFFFF",
  },
  loadingOverlay: {
    ...StyleSheet.absoluteFillObject,
    alignItems: "center",
    justifyContent: "center",
    gap: 8,
    backgroundColor: "rgba(248, 250, 252, 0.72)",
  },
  loadingText: {
    fontSize: 13,
    color: "#475569",
  },
  flashBanner: {
    position: "absolute",
    right: 16,
    bottom: 18,
    left: 16,
    borderRadius: 18,
    backgroundColor: "rgba(15, 23, 42, 0.94)",
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  flashBannerText: {
    fontSize: 13,
    fontWeight: "600",
    color: "#F8FAFC",
    textAlign: "center",
  },
  errorOverlay: {
    ...StyleSheet.absoluteFillObject,
    alignItems: "center",
    justifyContent: "center",
    gap: 12,
    backgroundColor: "rgba(248, 250, 252, 0.96)",
    paddingHorizontal: 24,
  },
  stateWrap: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    gap: 12,
    paddingHorizontal: 24,
  },
  stateTitle: {
    fontSize: 18,
    fontWeight: "700",
    color: "#0F172A",
    textAlign: "center",
  },
  stateText: {
    fontSize: 14,
    lineHeight: 22,
    color: "#475569",
    textAlign: "center",
  },
  stateHint: {
    fontSize: 12,
    lineHeight: 18,
    color: "#64748B",
    textAlign: "center",
  },
  errorActions: {
    flexDirection: "row",
    flexWrap: "wrap",
    justifyContent: "center",
    gap: 12,
    marginTop: 4,
  },
  primaryAction: {
    borderRadius: 999,
    backgroundColor: "#1D4ED8",
    paddingHorizontal: 18,
    paddingVertical: 12,
  },
  primaryActionText: {
    fontSize: 13,
    fontWeight: "700",
    color: "#FFFFFF",
  },
  secondaryAction: {
    borderRadius: 999,
    borderWidth: 1,
    borderColor: "#CBD5E1",
    backgroundColor: "#FFFFFF",
    paddingHorizontal: 18,
    paddingVertical: 12,
  },
  secondaryActionText: {
    fontSize: 13,
    fontWeight: "600",
    color: "#334155",
  },
  modalSafeArea: {
    flex: 1,
    backgroundColor: "#F8FAFC",
  },
  modalHeader: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    borderBottomWidth: 1,
    borderBottomColor: "#E2E8F0",
    backgroundColor: "#FFFFFF",
    paddingHorizontal: 20,
    paddingVertical: 16,
  },
  modalTitle: {
    fontSize: 20,
    fontWeight: "700",
    color: "#0F172A",
  },
  modalClose: {
    borderRadius: 999,
    backgroundColor: "#E2E8F0",
    paddingHorizontal: 14,
    paddingVertical: 8,
  },
  modalCloseText: {
    fontSize: 13,
    fontWeight: "600",
    color: "#334155",
  },
  modalContent: {
    gap: 20,
    paddingHorizontal: 20,
    paddingTop: 20,
    paddingBottom: 32,
  },
  tipCard: {
    borderRadius: 24,
    backgroundColor: "#0F172A",
    paddingHorizontal: 18,
    paddingVertical: 18,
  },
  tipTitle: {
    fontSize: 15,
    fontWeight: "700",
    color: "#F8FAFC",
  },
  tipText: {
    marginTop: 8,
    fontSize: 14,
    lineHeight: 22,
    color: "#CBD5E1",
  },
  tipHint: {
    marginTop: 10,
    fontSize: 12,
    color: "#94A3B8",
  },
  fieldGroup: {
    gap: 8,
  },
  fieldLabel: {
    fontSize: 15,
    fontWeight: "700",
    color: "#0F172A",
  },
  input: {
    borderRadius: 18,
    borderWidth: 1,
    borderColor: "#CBD5E1",
    backgroundColor: "#FFFFFF",
    paddingHorizontal: 16,
    paddingVertical: 14,
    fontSize: 15,
    color: "#0F172A",
  },
  fieldHint: {
    fontSize: 12,
    lineHeight: 18,
    color: "#64748B",
  },
  errorText: {
    fontSize: 12,
    color: "#DC2626",
  },
  quickActions: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 12,
  },
  inlineActions: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 12,
  },
  feedSection: {
    gap: 12,
  },
  statusGrid: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 12,
  },
  statusCard: {
    minWidth: 140,
    flex: 1,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: "#E2E8F0",
    backgroundColor: "#FFFFFF",
    paddingHorizontal: 16,
    paddingVertical: 14,
    gap: 6,
  },
  statusLabel: {
    fontSize: 12,
    color: "#64748B",
  },
  statusValue: {
    fontSize: 15,
    fontWeight: "700",
    color: "#0F172A",
  },
  tokenCard: {
    gap: 12,
    borderRadius: 24,
    borderWidth: 1,
    borderColor: "#E2E8F0",
    backgroundColor: "#FFFFFF",
    paddingHorizontal: 18,
    paddingVertical: 18,
  },
  tokenRow: {
    gap: 6,
  },
  tokenLabel: {
    fontSize: 12,
    color: "#64748B",
  },
  tokenValue: {
    fontSize: 13,
    lineHeight: 20,
    color: "#0F172A",
  },
  tokenError: {
    fontSize: 12,
    lineHeight: 18,
    color: "#DC2626",
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: "700",
    color: "#0F172A",
  },
  feedEmptyCard: {
    borderRadius: 20,
    borderWidth: 1,
    borderStyle: "dashed",
    borderColor: "#CBD5E1",
    backgroundColor: "#FFFFFF",
    paddingHorizontal: 16,
    paddingVertical: 18,
  },
  feedCard: {
    borderRadius: 20,
    borderWidth: 1,
    borderColor: "#E2E8F0",
    backgroundColor: "#FFFFFF",
    paddingHorizontal: 16,
    paddingVertical: 14,
    gap: 6,
  },
  feedTitle: {
    fontSize: 14,
    fontWeight: "700",
    color: "#0F172A",
  },
  feedDetail: {
    fontSize: 13,
    lineHeight: 20,
    color: "#475569",
  },
  feedTime: {
    fontSize: 11,
    color: "#94A3B8",
  },
  modalFooter: {
    borderTopWidth: 1,
    borderTopColor: "#E2E8F0",
    backgroundColor: "#FFFFFF",
    paddingHorizontal: 20,
    paddingVertical: 16,
  },
});
