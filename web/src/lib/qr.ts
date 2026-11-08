/**
 * QR codes drawn in the browser (the qrcode library), for <canvas> only: the mini app's CSP has no
 * data: images, so there is no toDataURL. The server's /sub.png and qr.png stay for the /k/… pages
 * and the apps; this draws the same text with the same quiet zone (4 modules) and, since a
 * subscription link is short, a stronger error correction than the server's L (M: still a small
 * code, and it survives a scratched or dim screen better).
 */
import QRCode from "qrcode";

export type QrLevel = "L" | "M" | "Q" | "H";

/** Modules of the code: `size` × `size`, row by row, true = dark. */
export interface QrMatrix {
  size: number;
  dark: boolean[];
}

export const QR_MARGIN = 4;

export function qrMatrix(text: string, level: QrLevel = "M"): QrMatrix {
  const { modules } = QRCode.create(text, { errorCorrectionLevel: level });
  return { size: modules.size, dark: Array.from(modules.data, (v) => !!v) };
}

/**
 * The picture as RGBA pixels (black on white, the quiet zone included), `scale` pixels per module.
 * QrCanvas puts exactly these pixels on the canvas, so a test that decodes them checks what people see.
 */
export function qrPixels(m: QrMatrix, scale: number, margin = QR_MARGIN): { width: number; data: Uint8ClampedArray<ArrayBuffer> } {
  const width = (m.size + margin * 2) * scale;
  const data = new Uint8ClampedArray(width * width * 4).fill(255);
  for (let y = 0; y < width; y++) {
    const row = Math.floor(y / scale) - margin;
    if (row < 0 || row >= m.size) continue;
    for (let x = 0; x < width; x++) {
      const col = Math.floor(x / scale) - margin;
      if (col < 0 || col >= m.size || !m.dark[row * m.size + col]) continue;
      const i = (y * width + x) * 4;
      data[i] = data[i + 1] = data[i + 2] = 0;
    }
  }
  return { width, data };
}
