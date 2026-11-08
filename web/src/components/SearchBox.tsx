/**
 * A search field whose text goes to ?q= (or another parameter) a moment after typing stops, so a
 * list is not asked for on every key; Enter sends it at once. The address replaces its history
 * entry while typing, and a change of the address (Back) shows up in the field.
 */
import { useEffect, useRef, useState } from "react";
import { Input } from "@/components/ui";
import { usePersistedSearch } from "@/hooks/usePersistedSearch";
import { cn } from "@/lib/cn";

export const SEARCH_DEBOUNCE_MS = 300;

export function SearchBox({
  label,
  placeholder,
  param = "q",
  delay = SEARCH_DEBOUNCE_MS,
  className,
}: {
  label: string;
  placeholder?: string;
  param?: string;
  delay?: number;
  className?: string;
}) {
  const [value, setValue] = usePersistedSearch(param, null, "");
  const [text, setText] = useState(value);
  const [shown, setShown] = useState(value);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  // The address changed from outside (Back, a link): the field follows it.
  if (value !== shown) {
    setShown(value);
    setText(value);
  }

  useEffect(() => () => clearTimeout(timer.current), []);

  const send = (v: string) => {
    clearTimeout(timer.current);
    if (v !== value) setValue(v, { replace: true });
  };

  return (
    <Input
      type="search"
      aria-label={label}
      placeholder={placeholder}
      value={text}
      className={cn("sm:max-w-xs", className)}
      onChange={(e) => {
        const v = e.target.value;
        setText(v);
        clearTimeout(timer.current);
        timer.current = setTimeout(() => send(v), delay);
      }}
      onKeyDown={(e) => {
        if (e.key === "Enter") {
          e.preventDefault();
          send(text);
        }
      }}
    />
  );
}
