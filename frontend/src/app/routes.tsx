import { createBrowserRouter, Navigate } from "react-router";
import Login from "./pages/login";
import Layout from "./pages/layout";
import DeviceManagement from "./pages/device-management";
import VideoPreview from "./pages/video-preview";
import VideoPlayback from "./pages/video-playback";
import StorageSettings from "./pages/storage-settings";
import Gb28181Console from "./pages/gb28181-console";
import AlarmHistory from "./pages/alarm-history";
import MobileShell from "./mobile/components/mobile-shell";
import MobileLogin from "./mobile/pages/mobile-login";
import MobileHome from "./mobile/pages/mobile-home";
import MobileDevices from "./mobile/pages/mobile-devices";
import MobilePreview from "./mobile/pages/mobile-preview";
import MobileAlarms from "./mobile/pages/mobile-alarms";
import MobileMore from "./mobile/pages/mobile-more";
import MobilePlayback from "./mobile/pages/mobile-playback";
import MobileSettings from "./mobile/pages/mobile-settings";
import MobileGb28181 from "./mobile/pages/mobile-gb28181";

function MobileIndexRedirect() {
  return <Navigate to="/m/home" replace />;
}

export const router = createBrowserRouter([
  {
    path: "/login",
    Component: Login,
  },
  {
    path: "/",
    Component: Layout,
    children: [
      {
        index: true,
        Component: DeviceManagement,
      },
      {
        path: "devices",
        Component: DeviceManagement,
      },
      {
        path: "video-preview",
        Component: VideoPreview,
      },
      {
        path: "video-playback",
        Component: VideoPlayback,
      },
      {
        path: "storage-settings",
        Component: StorageSettings,
      },
      {
        path: "gb28181",
        Component: Gb28181Console,
      },
      {
        path: "alarm-history",
        Component: AlarmHistory,
      },
    ],
  },
  {
    path: "/m/login",
    Component: MobileLogin,
  },
  {
    path: "/m",
    Component: MobileShell,
    children: [
      {
        index: true,
        Component: MobileIndexRedirect,
      },
      {
        path: "home",
        Component: MobileHome,
      },
      {
        path: "devices",
        Component: MobileDevices,
      },
      {
        path: "preview",
        Component: MobilePreview,
      },
      {
        path: "alarms",
        Component: MobileAlarms,
      },
      {
        path: "more",
        Component: MobileMore,
      },
      {
        path: "playback",
        Component: MobilePlayback,
      },
      {
        path: "settings",
        Component: MobileSettings,
      },
      {
        path: "gb28181",
        Component: MobileGb28181,
      },
    ],
  },
]);
