/**
 * A change on the server (POST/PUT/DELETE) with the website's usual feedback:
 * a success toast when `success` is given, the server's error.message in an error toast
 * (unless `toastError: false`, when the screen shows the error itself), and the listed
 * query keys invalidated afterwards so the screen shows fresh data.
 *
 *   const save = useApiMutation({ mutationFn: (body) => put("/admin/settings/modes", { body }),
 *                                 success: t("Saved"), invalidate: [keys.admin.settings()] });
 */
import { useMutation, useQueryClient, type QueryKey, type UseMutationOptions } from "@tanstack/react-query";
import { notify } from "@/lib/notify";

export interface ApiMutationOptions<TData, TVars> extends Omit<UseMutationOptions<TData, Error, TVars>, "onError"> {
  /** Toast text after success. */
  success?: string | ((data: TData) => string);
  /** Queries to refresh after success. */
  invalidate?: QueryKey[];
  /** false: no error toast (the screen shows the message, as the sign-in form does). */
  toastError?: boolean;
  onError?: (error: Error, vars: TVars) => void;
}

export function useApiMutation<TData = unknown, TVars = void>({
  success,
  invalidate,
  toastError = true,
  onSuccess,
  onError,
  ...options
}: ApiMutationOptions<TData, TVars>) {
  const qc = useQueryClient();
  return useMutation<TData, Error, TVars>({
    ...options,
    onSuccess: async (data, vars, onMutateResult, ctx) => {
      if (invalidate?.length) await Promise.all(invalidate.map((queryKey) => qc.invalidateQueries({ queryKey })));
      if (success) notify.success(typeof success === "function" ? success(data) : success);
      await onSuccess?.(data, vars, onMutateResult, ctx);
    },
    onError: (error, vars) => {
      if (toastError) notify.error(error);
      onError?.(error, vars);
    },
  });
}
