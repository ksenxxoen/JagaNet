/**
 * A QR code on <canvas> (no data: images, so it works under the mini app's CSP). The canvas has
 * whole pixels per module for the screen's pixel density and is shown at `size` CSS pixels, with
 * a white quiet zone, so it reads like the server's PNG. The text is the label for screen readers.
 */
import { useEffect, useMemo, useRef } from "react";
import { cn } from "@/lib/cn";
import { QR_MARGIN, qrMatrix, qrPixels, type QrLevel } from "@/lib/qr";

export function QrCanvas({
  text,
  label,
  size = 168,
  level = "M",
  className,
}: {
  text: string;
  label: string;
  size?: number;
  level?: QrLevel;
  className?: string;
}) {
  const ref = useRef<HTMLCanvasElement>(null);
  const matrix = useMemo(() => qrMatrix(text, level), [text, level]);

  useEffect(() => {
    const canvas = ref.current;
    const ctx = canvas?.getContext?.("2d");
    if (!canvas || !ctx) return;
    const dpr = Math.max(1, Math.min(3, window.devicePixelRatio || 1));
    const scale = Math.max(1, Math.floor((size * dpr) / (matrix.size + QR_MARGIN * 2)));
    const { width, data } = qrPixels(matrix, scale);
    canvas.width = width;
    canvas.height = width;
    ctx.putImageData(new ImageData(data, width, width), 0, 0);
  }, [matrix, size]);

  return (
    <canvas
      ref={ref}
      role="img"
      aria-label={label}
      width={size}
      height={size}
      style={{ width: size, height: size }}
      className={cn("block rounded-[14px] border border-line bg-white [image-rendering:pixelated]", className)}
    />
  );
}
