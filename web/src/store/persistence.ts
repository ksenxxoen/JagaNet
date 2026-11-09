/**
 * Where the session keeps what must outlive a page load. The website passes localStorage
 * (lib/storage.ts, the old site's keys), the mini app keeps everything in memory: its token is
 * gone when the mini app closes, and its bundle never touches web storage (docs/frontend-plan.md, 2.5).
 */
export interface Persistence {
  get(key: string): string | null;
  set(key: string, value: string | null | undefined): void;
}

/** Values in a Map, for the mini app and tests. */
export function memoryPersistence(): Persistence {
  const values = new Map<string, string>();
  return {
    get: (key) => values.get(key) ?? null,
    set: (key, value) => {
      if (value == null) values.delete(key);
      else values.set(key, value);
    },
  };
}
