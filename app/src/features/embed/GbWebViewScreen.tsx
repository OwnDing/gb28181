import { StatusBar } from "expo-status-bar";
import * as Device from "expo-device";
import { useEffect, useMemo, useRef, useState } from "react";
import {
  ActivityIndicator,
  BackHandler,
  Linking,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  View,
} from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { WebView } from "react-native-webview";

const DEFAULT_DEV_PORT = 5173;
const DEFAULT_LAN_EMBED_HOST = "http://192.168.6.230:5173";

function buildEmbedUrl(baseUrl: string) {
  const trimmed = baseUrl.trim().replace(/\/$/, "");
  const routeUrl = trimmed.endsWith("/m/home") ? trimmed : `${trimmed}/m/home`;
  const joiner = routeUrl.includes("?") ? "&" : "?";
  const cacheBust = __DEV__ ? `&ts=${Date.now()}` : "";
  return `${routeUrl}${joiner}embed=1&shell=native${cacheBust}`;
}

function getDefaultDevHost() {
  if (Device.isDevice) {
    return DEFAULT_LAN_EMBED_HOST;
  }

  return Platform.select({
    android: DEFAULT_LAN_EMBED_HOST,
    default: DEFAULT_LAN_EMBED_HOST,
  });
}

function getEmbedUrl() {
  const envUrl = process.env.EXPO_PUBLIC_GB28181_EMBED_URL?.trim();
  if (envUrl) {
    return buildEmbedUrl(envUrl);
  }

  if (!__DEV__) {
    return null;
  }

  const host = getDefaultDevHost();
  return host ? buildEmbedUrl(host) : null;
}

export function GbWebViewScreen() {
  const webViewRef = useRef<WebView>(null);
  const embedUrl = useMemo(() => getEmbedUrl(), []);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [canGoBack, setCanGoBack] = useState(false);

  useEffect(() => {
    const subscription = BackHandler.addEventListener(
      "hardwareBackPress",
      () => {
        if (canGoBack) {
          webViewRef.current?.goBack();
          return true;
        }
        return false;
      },
    );

    return () => subscription.remove();
  }, [canGoBack]);

  if (!embedUrl) {
    return (
      <SafeAreaView edges={["top", "left", "right"]} style={styles.safeArea}>
        <StatusBar style="dark" />
        <View style={styles.stateWrap}>
          <Text style={styles.stateTitle}>未配置移动端 H5 地址</Text>
          <Text style={styles.stateText}>
            当前默认会尝试访问 `192.168.6.230:5173`；如需切换地址，请设置环境变量
            `EXPO_PUBLIC_GB28181_EMBED_URL`，并重启 Expo。
          </Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView edges={["top", "left", "right"]} style={styles.safeArea}>
      <StatusBar style="dark" />

      <View style={styles.header}>
        <View>
          <Text style={styles.headerEyebrow}>GB28181 APP</Text>
          <Text style={styles.headerTitle}>移动端 WebView 容器</Text>
        </View>
        <Pressable
          onPress={() => {
            Linking.openURL(embedUrl).catch(() => undefined);
          }}
          style={styles.headerButton}
        >
          <Text style={styles.headerButtonText}>浏览器打开</Text>
        </Pressable>
      </View>

      <View style={styles.container}>
        <WebView
          allowsInlineMediaPlayback
          cacheEnabled={false}
          domStorageEnabled
          javaScriptEnabled
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
          onNavigationStateChange={(event) => {
            setCanGoBack(event.canGoBack);
          }}
          originWhitelist={["*"]}
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
            <Text style={styles.stateHint}>当前地址：{embedUrl}</Text>
            <View style={styles.errorActions}>
              <Pressable
                onPress={() => {
                  setError(null);
                  setLoading(true);
                  webViewRef.current?.reload();
                }}
                style={styles.primaryAction}
              >
                <Text style={styles.primaryActionText}>重新加载</Text>
              </Pressable>
              <Pressable
                onPress={() => {
                  Linking.openURL(embedUrl).catch(() => undefined);
                }}
                style={styles.secondaryAction}
              >
                <Text style={styles.secondaryActionText}>浏览器打开</Text>
              </Pressable>
            </View>
          </View>
        ) : null}
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: "#F8FAFC",
  },
  header: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: "#CBD5E1",
    backgroundColor: "#FFFFFF",
  },
  headerEyebrow: {
    fontSize: 11,
    color: "#64748B",
    textTransform: "uppercase",
    letterSpacing: 1.6,
  },
  headerTitle: {
    marginTop: 4,
    fontSize: 15,
    fontWeight: "600",
    color: "#0F172A",
  },
  headerButton: {
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 999,
    backgroundColor: "#E2E8F0",
  },
  headerButtonText: {
    fontSize: 12,
    fontWeight: "600",
    color: "#0F172A",
  },
  container: {
    flex: 1,
    backgroundColor: "#E2E8F0",
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
  errorOverlay: {
    ...StyleSheet.absoluteFillObject,
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: 24,
    gap: 12,
    backgroundColor: "rgba(248, 250, 252, 0.96)",
  },
  stateWrap: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: 24,
    gap: 12,
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
    alignItems: "center",
    gap: 12,
    marginTop: 8,
  },
  primaryAction: {
    borderRadius: 999,
    backgroundColor: "#1D4ED8",
    paddingHorizontal: 18,
    paddingVertical: 10,
  },
  primaryActionText: {
    fontSize: 13,
    fontWeight: "600",
    color: "#FFFFFF",
  },
  secondaryAction: {
    borderRadius: 999,
    backgroundColor: "#E2E8F0",
    paddingHorizontal: 18,
    paddingVertical: 10,
  },
  secondaryActionText: {
    fontSize: 13,
    fontWeight: "600",
    color: "#0F172A",
  },
});
