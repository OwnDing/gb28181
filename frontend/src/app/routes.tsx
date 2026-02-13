import { createBrowserRouter } from "react-router";
import Login from "./pages/login";
import Layout from "./pages/layout";
import DeviceManagement from "./pages/device-management";
import VideoPreview from "./pages/video-preview";
import StorageSettings from "./pages/storage-settings";

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
    ],
  },
]);
