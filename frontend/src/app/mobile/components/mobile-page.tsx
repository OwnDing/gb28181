import type { ReactNode } from "react";

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
