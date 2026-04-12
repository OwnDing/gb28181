import type { ReactNode } from "react";
import { useEffect } from "react";
import { useLocation } from "react-router";
import { useNativeShellState } from "../lib/native-shell";

type MobilePageProps = {
  title: string;
  description?: string;
  action?: ReactNode;
  children: ReactNode;
};

export default function MobilePage({
  title,
  description,
  action,
  children,
}: MobilePageProps) {
  const location = useLocation();
  const canGoBack =
    location.pathname !== "/m" &&
    location.pathname !== "/m/home";

  useEffect(() => {
    document.title = `${title} - GB28181 App`;
  }, [title]);

  useNativeShellState({
    title,
    canGoBack,
    path: `${location.pathname}${location.search}`,
  });

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-xl font-semibold text-slate-900">{title}</h1>
          {description ? (
            <p className="mt-1 text-sm text-slate-500">{description}</p>
          ) : null}
        </div>
        {action ? <div className="shrink-0">{action}</div> : null}
      </div>

      {children}
    </div>
  );
}
