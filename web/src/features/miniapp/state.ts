/**
 * What the mini app keeps while it is open (in memory only): the screen, the chosen
 * tariff and the order being paid. The order outlives a visit to another screen: back on the
 * payment screen, the waiting screen shows again.
 */
import { create } from "zustand";
import type { Screen } from "./screens";

export interface PendingOrder {
  id: string;
  /** The payment page; opened again from the waiting screen. */
  url?: string;
  name: string;
  amount: string;
  /** When it was made (ms): asking stops 20 minutes later. */
  started: number;
}

interface MiniState {
  screen: Screen;
  tariff: string | null;
  order: PendingOrder | null;
  go: (screen: Screen) => void;
  setTariff: (id: string | null) => void;
  setOrder: (order: PendingOrder | null) => void;
}

export const useMini = create<MiniState>((set) => ({
  screen: "home",
  tariff: null,
  order: null,
  go: (screen) => {
    set({ screen });
    try {
      window.scrollTo(0, 0);
    } catch {
      /* jsdom */
    }
  },
  setTariff: (tariff) => set({ tariff }),
  setOrder: (order) => set({ order }),
}));
