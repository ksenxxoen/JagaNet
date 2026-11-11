/**
 * A form that saves to the server: while it saves the form is locked,
 * after success an optional toast and fresh queries, on failure the message under the form.
 * Checks before sending throw an Error from `build`, and that message is shown the same way.
 *
 *   const form = useSaveForm({ mutationFn: (body: Body) => put("/admin/settings/alerts", { body }), success: t("Saved") });
 *   <Form onSubmit={() => form.save(() => ({ emails }))} error={form.error} pending={form.pending}>…</Form>
 */
import { useCallback, useState } from "react";
import { useApiMutation, type ApiMutationOptions } from "./useApiMutation";

export function useSaveForm<TData = unknown, TVars = void>(options: ApiMutationOptions<TData, TVars>) {
  const [error, setError] = useState<string | null>(null);
  const mutation = useApiMutation<TData, TVars>({ toastError: false, ...options });
  const { mutateAsync } = mutation;

  /**
   * Builds the request (an Error thrown here is shown under the form), asks `ask` if given (a
   * confirm dialog: false sends nothing) and sends it. Resolves with the answer, or undefined after
   * an error or a "no".
   */
  const save = useCallback(
    async (build: () => TVars, ask?: (vars: TVars) => Promise<boolean> | boolean): Promise<TData | undefined> => {
      setError(null);
      let vars: TVars;
      try {
        vars = build();
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
        return undefined;
      }
      if (ask && !(await ask(vars))) return undefined;
      try {
        return await mutateAsync(vars);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
        return undefined;
      }
    },
    [mutateAsync],
  );

  return { save, error, setError, pending: mutation.isPending, data: mutation.data, reset: mutation.reset };
}
