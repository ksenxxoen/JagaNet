/**
 * A form that opens under a list (a plan, a node): scrolls it into view and puts the cursor in its
 * first field, as the old forms did with scrollIntoView() and focus().
 *
 *   const first = useOpenFocus<HTMLInputElement>();
 *   <Input ref={first} … />
 */
import { useEffect, useRef } from "react";

export function useOpenFocus<T extends HTMLElement = HTMLInputElement>() {
  const ref = useRef<T>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const reduce = typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches;
    el.closest("form")?.scrollIntoView?.({ behavior: reduce ? "auto" : "smooth", block: "start" });
    el.focus({ preventScroll: true });
  }, []);
  return ref;
}
