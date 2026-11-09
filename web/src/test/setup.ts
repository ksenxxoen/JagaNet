import "@testing-library/jest-dom/vitest";
import { onLangChange } from "@/i18n";
import { rememberLang } from "@/i18n/site";
import { store } from "@/lib/storage";
import { createSessionStore, installSession } from "@/store/session";

// Screen tests render the website: its session and language live in localStorage, as in entries/site.tsx.
// The mini app's tests install a memory store themselves (features/miniapp/__tests__).
installSession(createSessionStore(store));
onLangChange(rememberLang);
