import { createBrowserRouter } from "react-router";
import Login from "./pages/login";
import Layout from "./pages/layout";
import DeviceManagement from "./pages/device-management";
import VideoPreview from "./pages/video-preview";
import StorageSettings from "./pages/storage-settings";
import Gb28181Console from "./pages/gb28181-console";
import AlarmHistory from "./pages/alarm-history";

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
]);
