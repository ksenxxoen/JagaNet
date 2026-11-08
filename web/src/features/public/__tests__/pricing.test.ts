import { describe, expect, it } from "vitest";
import { tariff } from "@/test/app";
import { evenCols } from "../Landing";
import { devicesLine, period, pricedTariffs } from "../pricing";

describe("pricing", () => {
  it("orders by length, computes the price per month and the saving against the monthly plan", () => {
    const { rows, best } = pricedTariffs([
      tariff({ id: "y", durationValue: 12, priceMinor: 270000 }),
      tariff({ id: "w", durationUnit: "days", durationValue: 7, priceMinor: 9900 }),
      tariff({ id: "m", durationValue: 1, priceMinor: 30000 }),
      tariff({ id: "q", durationValue: 3, priceMinor: 80000 }),
    ]);
    expect(rows.map((r) => r.x.id)).toEqual(["w", "m", "q", "y"]);
    expect(rows.map((r) => r.perMonth)).toEqual([null, 30000, 26667, 22500]);
    expect(rows.map((r) => r.save)).toEqual([0, 0, 11, 25]);
    expect(rows.map((r) => r.full)).toEqual([null, null, 90000, 360000]);
    // The biggest saving is the recommended plan.
    expect(best).toBe(3);
  });

  it("the owner's badge picks the recommended plan; another currency has no saving", () => {
    const { rows, best } = pricedTariffs([
      tariff({ id: "m" }),
      tariff({ id: "q", durationValue: 3, priceMinor: 81000, badge: "Popular" }),
      tariff({ id: "e", durationValue: 12, priceMinor: 1000, currency: "EUR" }),
    ]);
    expect(best).toBe(1);
    expect(rows[2]!.save).toBe(0);
    expect(pricedTariffs([tariff()]).best).toBe(0);
    expect(pricedTariffs([]).rows).toEqual([]);
  });

  it("texts for length and devices", () => {
    expect(period({ durationUnit: "days", durationValue: 7 })).toBe("7 days");
    expect(period({ durationUnit: "months", durationValue: 1 })).toBe("1 month");
    expect(devicesLine(1)).toBe("1 device");
    expect(devicesLine(5)).toBe("Up to 5 devices");
  });

  it("rows of tiles are always full", () => {
    expect([1, 2, 3, 4, 5, 6, 8, 9].map(evenCols)).toEqual([1, 2, 3, 4, 4, 3, 4, 3]);
  });
});
