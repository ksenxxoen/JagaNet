/**
 * confirm() as a promise with the website's dialog:
 *
 *   const { confirm, dialog } = useConfirm();
 *   if (!(await confirm({ title: question, confirm: deleteLabel, danger: true }))) return;
 *   …
 *   return <>{…}{dialog}</>;
 */
import { useCallback, useRef, useState } from "react";
import { ConfirmDialog, type ConfirmOptions } from "@/components/ui/Modal";

export function useConfirm() {
  const [options, setOptions] = useState<ConfirmOptions | null>(null);
  const resolver = useRef<((yes: boolean) => void) | null>(null);

  const confirm = useCallback(
    (o: ConfirmOptions) =>
      new Promise<boolean>((resolve) => {
        resolver.current?.(false);
        resolver.current = resolve;
        setOptions(o);
      }),
    [],
  );

  const answer = useCallback((yes: boolean) => {
    resolver.current?.(yes);
    resolver.current = null;
    setOptions(null);
  }, []);

  return { confirm, dialog: <ConfirmDialog open={!!options} options={options} onAnswer={answer} /> };
}
