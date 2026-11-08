/** Toasts (react-hot-toast): one place to show a success or an error. */
import toast from "react-hot-toast";

export const notify = {
  success: (message: string) => toast.success(message),
  error: (e: unknown) => toast.error(e instanceof Error ? e.message : String(e)),
  info: (message: string) => toast(message),
};
