import { LANGS, setLang, useI18n } from "@/i18n";
import { cn } from "@/lib/cn";
import { useSession } from "@/store/session";

/** RU / DE / EN, the current one pressed (.langs in style.css). */
export function LangSwitch({ className }: { className?: string }) {
  const { t, lang } = useI18n();
  const setLangState = useSession((s) => s.setLangState);
  return (
    <span
      role="group"
      aria-label={t("Language")}
      className={cn("inline-flex gap-0.5 rounded-ctl border border-line bg-subtle p-[3px]", className)}
    >
      {LANGS.map(([code, name]) => (
        <button
          key={code}
          type="button"
          lang={code}
          title={name}
          aria-pressed={code === lang}
          onClick={() => void setLang(code).then(() => setLangState(code))}
          className={cn(
            "inline-flex h-7 min-w-[34px] items-center justify-center rounded-lg px-1.5 text-xs font-semibold",
            code === lang ? "bg-surface text-ink-heading shadow-sm ring-1 ring-line" : "text-muted hover:text-ink-heading",
          )}
        >
          {code.toUpperCase()}
        </button>
      ))}
    </span>
  );
}
