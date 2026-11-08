import { Check, Copy } from "lucide-react";
import { useCopy } from "@/hooks/useCopy";
import { useI18n } from "@/i18n";
import { Button, type ButtonProps } from "./Button";

/** A button that copies `text` (useCopy: toast, fallbacks); for 1.5 s after a copy it says "Copied". */
export function CopyButton({ text, label, ...props }: Omit<ButtonProps, "onClick" | "children"> & { text: string; label: string }) {
  const { t } = useI18n();
  const { copy, copied } = useCopy();
  return (
    <Button {...props} onClick={() => void copy(text)}>
      {copied ? <Check aria-hidden="true" /> : <Copy aria-hidden="true" />}
      {copied ? t("Copied") : label}
    </Button>
  );
}
