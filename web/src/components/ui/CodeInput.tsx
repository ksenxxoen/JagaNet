import { forwardRef, type InputHTMLAttributes } from "react";
import { cn } from "@/lib/cn";
import { Input } from "./Field";

/** Keeps the digits only, at most `length` of them ("12 34-56" → "123456"). */
export const codeDigits = (text: string, length = 6) => text.replace(/\D/g, "").slice(0, length);

/**
 * A one-time code of 6 digits: the number keyboard on phones, the code
 * from an SMS or e-mail suggested by the system (autocomplete="one-time-code"), pasted text with
 * spaces or dashes cleaned up. onComplete fires when the last digit is typed.
 */
export const CodeInput = forwardRef<
  HTMLInputElement,
  Omit<InputHTMLAttributes<HTMLInputElement>, "value" | "onChange"> & {
    value: string;
    onChange: (code: string) => void;
    onComplete?: (code: string) => void;
    length?: number;
  }
>(({ value, onChange, onComplete, length = 6, className, ...props }, ref) => (
  <Input
    ref={ref}
    inputMode="numeric"
    autoComplete="one-time-code"
    pattern={`\\d{${length}}`}
    placeholder={"0".repeat(length)}
    spellCheck={false}
    {...props}
    value={value}
    onChange={(e) => {
      const code = codeDigits(e.target.value, length);
      onChange(code);
      if (code.length === length && code !== value) onComplete?.(code);
    }}
    className={cn("h-[52px] text-center font-mono text-2xl font-bold tracking-[0.35em] tnum", className)}
  />
));
CodeInput.displayName = "CodeInput";
