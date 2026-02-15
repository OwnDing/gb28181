
import { useEffect, useRef, useState, forwardRef, useImperativeHandle } from "react";

// Add global declaration for Jessibuca
declare global {
  interface Window {
    Jessibuca: any;
    jessibuca: any;
  }
}

interface JessibucaPlayerProps {
  url?: string;
  className?: string;
  autoPlay?: boolean;
  onPlay?: () => void;
  onPause?: () => void;
  title?: string;
  isH265?: boolean;
}

export interface JessibucaPlayerRef {
  play: (url?: string) => void;
  pause: () => void;
  destroy: () => void;
  screenshot: (filename?: string, format?: string, quality?: number, type?: string) => void;
}

const JessibucaPlayer = forwardRef<JessibucaPlayerRef, JessibucaPlayerProps>(
  ({ url, className, autoPlay = true, onPlay, onPause, title, isH265 = false }, ref) => {
    const containerRef = useRef<HTMLDivElement>(null);
    const jessibucaRef = useRef<any>(null);
    const [isPlaying, setIsPlaying] = useState(false);

    useImperativeHandle(ref, () => ({
      play: (playUrl?: string) => {
        if (jessibucaRef.current) {
          jessibucaRef.current.play(playUrl || url);
        }
      },
      pause: () => {
        if (jessibucaRef.current) {
          jessibucaRef.current.pause();
        }
      },
      destroy: () => {
        if (jessibucaRef.current) {
          jessibucaRef.current.destroy();
        }
      },
      screenshot: (filename, format, quality, type) => {
        if (jessibucaRef.current) {
          jessibucaRef.current.screenshot(filename, format, quality, type);
        }
      },
    }));

    useEffect(() => {
      let instance: any = null;

      const initPlayer = () => {
        const JessibucaConstructor = window.Jessibuca || window.jessibuca;
        if (!containerRef.current || !JessibucaConstructor) return;

        if (jessibucaRef.current) {
          jessibucaRef.current.destroy();
        }

        instance = new JessibucaConstructor({
          container: containerRef.current,
          videoBuffer: 0.2, // 200ms buffer for low latency
          isResize: false,
          useMSE: isH265 ? false : true, // Force WASM for H.265 to avoid Chrome MSE issues
          useWCS: isH265 ? false : true, // Force WASM for H.265
          wcsUseVideoTag: true,
          loadingText: "加载中...",
          debug: false,
          forceNoOffscreen: true,
          showBandwidth: true, // Show bandwidth info
          operateBtns: {
            fullscreen: true,
            screenshot: true,
            play: true,
            audio: true,
          },
          decoder: "/decoder.js", // Explicitly point to decoder.js in public root
        });

        jessibucaRef.current = instance;

        instance.on("play", () => {
          setIsPlaying(true);
          onPlay?.();
        });

        instance.on("pause", () => {
          setIsPlaying(false);
          onPause?.();
        });

        instance.on("error", (error: any) => {
          console.error("Jessibuca error:", error);
        });

        if (url && autoPlay) {
          instance.play(url);
        }
      };

      // Check if Jessibuca is loaded, if not wait a bit or it might be loaded async
      if (window.Jessibuca || window.jessibuca) {
        initPlayer();
      } else {
        const checkInterval = setInterval(() => {
          if (window.Jessibuca || window.jessibuca) {
            clearInterval(checkInterval);
            initPlayer();
          }
        }, 100);

        // Timeout after 5 seconds
        setTimeout(() => clearInterval(checkInterval), 5000);
      }

      return () => {
        if (instance) {
          instance.destroy();
        }
        jessibucaRef.current = null;
      };
    }, [isH265]); // Re-init if codec changes


    useEffect(() => {
      if (jessibucaRef.current && url && isPlaying) {
        // If url changes and we are playing, switch url? 
        // Usually better to let parent control play/pause
        // But for safety:
        if (!jessibucaRef.current.isPlaying()) {
          if (autoPlay) jessibucaRef.current.play(url);
        } else {
          // If url changed, replay?
          // Let's assume parent handles replay if url changes significantly
          // Or we can force replay:
          // jessibucaRef.current.play(url);
        }
      } else if (jessibucaRef.current && url && autoPlay && !isPlaying) {
        // ensure initial play if url comes later
        if (!jessibucaRef.current.hasLoaded() && !jessibucaRef.current.isPlaying()) {
          jessibucaRef.current.play(url)
        }
      }
    }, [url, autoPlay]);

    return (
      <div
        ref={containerRef}
        className={`w-full h-full bg-black relative ${className || ""}`}
        data-name="jessibuca-container"
      />
    );
  }
);

JessibucaPlayer.displayName = "JessibucaPlayer";

export default JessibucaPlayer;
