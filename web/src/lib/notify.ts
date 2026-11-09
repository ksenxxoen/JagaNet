/**
 * Toasts: one place to show a success or an error. Headless react-hot-toast (no goober, no
 * <style> elements: the mini app's CSP allows style files only); components/ui/Toaster draws them.
 */
import toast from "react-hot-toast/headless";

export const notify = {
  success: (message: string) => toast.success(message),
  error: (e: unknown) => toast.error(e instanceof Error ? e.message : String(e)),
  info: (message: string) => toast(message),
};
