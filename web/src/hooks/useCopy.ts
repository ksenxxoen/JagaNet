/**
 * Copying a link (copyText() in app.js, copy() in tg/app.js): the clipboard API, else a hidden
 * textarea and execCommand (older browsers, Telegram on some phones), and as the last way the
 * browser's prompt with the text selected, as the old site did. A toast says it worked.
 */
import { useCallback, useState } from "react";
import { t } from "@/i18n";
import { notify } from "@/lib/notify";

function legacyCopy(text: string): boolean {
  const ta = document.createElement("textarea");
  ta.value = text;
  ta.setAttribute("readonly", "");
  ta.className = "fixed -left-[9999px] top-0 opacity-0";
  document.body.appendChild(ta);
  ta.select();
  let ok: boolean;
  try {
    ok = document.execCommand("copy");
  } catch {
    ok = false;
  }
  ta.remove();
  return ok;
}

/** Copies the text; true when it is on the clipboard. */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (!navigator.clipboard?.writeText) throw new Error("no clipboard");
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    if (legacyCopy(text)) return true;
  }
  try {
    window.prompt("", text);
  } catch {
    /* no prompt either (some webviews): the toast below says how */
  }
  return false;
}

/** `copy(text)` with a toast; `copied` is true for 1.5 s after a copy (the old button said "Copied"). */
export function useCopy() {
  const [copied, setCopied] = useState(false);
  const copy = useCallback(async (text: string) => {
    const ok = await copyText(text);
    if (ok) {
      notify.success(t("Copied"));
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } else notify.info(t("Couldn't copy, select the link and copy it"));
    return ok;
  }, []);
  return { copy, copied };
}
