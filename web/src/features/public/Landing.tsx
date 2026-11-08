/**
 * The landing page #/ (home() in app.js): hero, the apps it works in, the three pillars and the
 * extras, how it works, pricing from /v1/billing/plans, apps, questions, the closing call.
 * The anchors (#features, #how, #pricing, #faq, #download) are handled by useLandingAnchors().
 */
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useEffect, useRef, type CSSProperties, type ReactNode } from "react";
import { plansQuery, siteQuery } from "@/api/queries";
import { buttonVariants, ErrorState, Notice, Spinner } from "@/components/ui";
import { useI18n } from "@/i18n";
import { cn } from "@/lib/cn";
import { notify } from "@/lib/notify";
import { rich } from "@/lib/rich";
import { scrollToAnchor } from "@/lib/anchors";
import { startCheckout } from "@/lib/checkout";
import { appCards } from "@/lib/apps";
import type { Schema } from "@/api/typed";
import { Icon, type IconName } from "./icons";
import { devicesLine, period, pricedTariffs, type PricedTariff } from "@/lib/pricing";
import "./landing.css";

type SiteInfo = Schema<"SiteInfo">;
type FreePlan = Schema<"FreePlan">;

/** Classes of a landing button: the UI kit's button with the old site's look (gradient, large, light on dark). */
const btn = (look: "grad" | "secondary" | "ghost-light", large = false) =>
  cn(
    buttonVariants({ variant: look === "secondary" ? "secondary" : "primary", size: large ? "lg" : "md" }),
    "btn-lp",
    look,
    large && "large",
  );

/**
 * Columns for a row of n tiles so that no row is left half empty (evenCols() in app.js):
 * up to 4 in one row, else the largest of 4, 3, 2 that divides n (a short last row is centered).
 */
export const evenCols = (n: number) => (n <= 4 ? Math.max(1, n) : ([4, 3, 2].find((c) => n % c === 0) ?? 4));

const Tiles = ({ items, className }: { items: ReactNode[]; className?: string }) => (
  <div className={cn("cards", className)} style={{ "--n": evenCols(items.length) } as CSSProperties} data-even={items.length % 2 === 0}>
    {items}
  </div>
);

const Checks = ({ lines }: { lines: (string | false | null | undefined)[] }) => (
  <ul className="checks">
    {lines.filter(Boolean).map((l, i) => (
      <li key={i}>
        <Icon name="check" />
        <span>{l}</span>
      </li>
    ))}
  </ul>
);

const SectionHead = ({ eyebrow, title, sub, extra }: { eyebrow?: string; title: string; sub?: string; extra?: ReactNode }) => (
  <div className="sec-head reveal">
    {eyebrow ? <span className="eyebrow">{eyebrow}</span> : null}
    <h2>{title}</h2>
    {sub ? <p>{sub}</p> : null}
    {extra}
  </div>
);

/** Fades sections in as they scroll into view (reveal() in app.js); everything shows at once with reduced motion. */
function useReveal(root: React.RefObject<HTMLElement | null>, ready: boolean) {
  useEffect(() => {
    const el = root.current;
    if (!ready || !el) return;
    const items = el.querySelectorAll(".reveal");
    const reduced = typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (!("IntersectionObserver" in window) || reduced) {
      for (const e of items) e.classList.add("in");
      return;
    }
    document.documentElement.classList.add("anim");
    const io = new IntersectionObserver(
      (list) => {
        for (const e of list)
          if (e.isIntersecting) {
            e.target.classList.add("in");
            io.unobserve(e.target);
          }
      },
      { rootMargin: "0px 0px -4% 0px" },
    );
    for (const e of items) io.observe(e);
    return () => {
      io.disconnect();
      document.documentElement.classList.remove("anim");
    };
  }, [root, ready]);
}

function PlanCard({
  name,
  badge,
  best,
  price,
  per,
  note,
  lines,
  button,
}: {
  name: string;
  badge?: string;
  best?: boolean;
  price: string;
  per?: string;
  note?: ReactNode;
  lines: (string | false | null | undefined)[];
  button: ReactNode;
}) {
  return (
    <article className={cn("plan", best && "best")}>
      <div className="plan-top">
        <h3>{name}</h3>
        {badge ? <span className={cn("badge", best ? "hot" : "best")}>{badge}</span> : null}
      </div>
      <div className="price">
        <b>{price}</b>
        {per ? <span>{per}</span> : null}
      </div>
      <p className="price-note">{note || " "}</p>
      <Checks lines={lines} />
      {button}
    </article>
  );
}

function BuyButton({ tariffId, best, payOk }: { tariffId: string; best: boolean; payOk: boolean }) {
  const { t } = useI18n();
  const navigate = useNavigate();
  return (
    <button
      type="button"
      className={btn(best ? "grad" : "secondary")}
      disabled={!payOk}
      onClick={() =>
        void startCheckout(tariffId)
          .then((r) => (r === "signin" ? navigate({ to: "/signin" }) : undefined))
          .catch((e: unknown) => notify.error(e))
      }
    >
      {t("Get this plan")}
    </button>
  );
}

function TariffPlan({ r, best, payOk }: { r: PricedTariff; best: boolean; payOk: boolean }) {
  const { t, money } = useI18n();
  const x = r.x;
  const bill = t("{price} for {period}", { price: money(x.priceMinor, x.currency), period: period(x) });
  const note =
    r.months > 1 ? (
      <>
        {r.full ? <s>{money(r.full, x.currency)}</s> : null}
        {r.full ? " " : null}
        {bill}
        {r.save ? (
          <>
            {" "}
            <span className="save">{t("Save {p}%", { p: r.save })}</span>
          </>
        ) : null}
      </>
    ) : r.months === 1 ? (
      bill
    ) : null;
  const dataText =
    x.monthlyDataLimitBytes == null ? t("Unlimited data") : t("{n} GB a month", { n: Math.round(x.monthlyDataLimitBytes / 1e9) });
  return (
    <PlanCard
      name={x.name}
      best={best}
      price={money(r.perMonth ?? x.priceMinor, x.currency)}
      per={r.perMonth != null ? t("per month") : t("for {period}", { period: period(x) })}
      note={note}
      badge={x.badge || (best ? t("Best value") : r.save ? t("Save {p}%", { p: r.save }) : "")}
      lines={[
        devicesLine(x.deviceLimit),
        dataText,
        t("Every server location"),
        t("Low ping for online games"),
        t("Works in other VPN apps too"),
      ]}
      button={<BuyButton tariffId={x.id} best={best} payOk={payOk} />}
    />
  );
}

function FreePlanCard({ free, cur }: { free: FreePlan; cur: string }) {
  const { t, tp, money } = useI18n();
  return (
    <PlanCard
      name={t("Free")}
      price={money(0, cur)}
      per={free.days ? t("for {period}", { period: tp(free.days, "{n} day|{n} days") }) : t("forever")}
      note={t("No payment needed")}
      lines={[
        free.days ? tp(free.days, "{n} day|{n} days") : t("No time limit"),
        devicesLine(free.deviceLimit),
        free.monthlyDataLimitBytes ? t("{n} GB a month", { n: Math.round(free.monthlyDataLimitBytes / 1e9) }) : t("Unlimited data"),
        free.speedMbps ? t("Up to {n} Mbit/s", { n: free.speedMbps }) : t("In the Boop app"),
      ]}
      button={
        <a className={btn("secondary")} href="#download">
          {t("Start for free")}
        </a>
      }
    />
  );
}

/** The product picture in the hero: the Boop app, connected. Decorative, so hidden from screen readers. */
function HeroVisual() {
  const { t } = useI18n();
  const bars = [38, 52, 44, 63, 58, 72, 66, 80, 74, 86, 78, 92];
  return (
    <div className="hero-visual" aria-hidden="true">
      <div className="orbit o1" />
      <div className="orbit o2" />
      <div className="phone">
        <div className="phone-in">
          <div className="ph-top">
            <span className="ph-logo">
              <img src="/favicon.svg" alt="" />
              Boop
            </span>
            <span className="ph-pill">{t("Protected")}</span>
          </div>
          <div className="ph-ring">
            <span className="pulse" />
            <span className="pulse p2" />
            <div className="ph-btn">
              <Icon name="shield" />
            </div>
          </div>
          <div className="ph-state">
            <b>{t("Protected")}</b>
            <span>{t("All traffic is encrypted")}</span>
          </div>
          <div className="ph-row">
            <Icon name="globe" />
            <div>
              <b>{t("Auto, best server")}</b>
              <span>{t("Lowest ping is chosen for you")}</span>
            </div>
          </div>
          <div className="ph-graph">
            {bars.map((v, i) => (
              <i key={i} style={{ height: `${v}%`, animationDelay: `${i * 90}ms` }} />
            ))}
          </div>
        </div>
      </div>
      <div className="float f1">
        <Icon name="bolt" />
        <span>{t("High speed")}</span>
      </div>
      <div className="float f2">
        <Icon name="ping" />
        <span>{t("Low ping")}</span>
      </div>
      <div className="float f3">
        <Icon name="lock" />
        <span>{t("Data security")}</span>
      </div>
    </div>
  );
}

function Downloads({ site }: { site: Partial<SiteInfo> }) {
  return (
    <Tiles
      className="apps"
      items={appCards(site).map((c) => (
        <div key={c.title} className="app-card rounded-panel border border-line bg-surface shadow-sm">
          <div className="tile-ic">
            <Icon name={c.icon} />
          </div>
          <h3>{c.title}</h3>
          <p>{c.text}</p>
          {c.href ? (
            <a className={btn("secondary")} href={c.href} {...(c.external ? { rel: "noreferrer" } : {})}>
              {c.label}
            </a>
          ) : (
            <button type="button" className={btn("secondary")} disabled>
              {c.label}
            </button>
          )}
        </div>
      ))}
    />
  );
}

export function Landing() {
  const { t, tp, lang } = useI18n();
  const plans = useQuery(plansQuery);
  const siteQ = useQuery(siteQuery);
  const root = useRef<HTMLDivElement>(null);
  const ready = !!plans.data && !siteQ.isPending;
  useReveal(root, ready);

  // Opened with an anchor (#pricing from another screen or a link): go to that section once it is drawn.
  useEffect(() => {
    if (ready) scrollToAnchor(window.location.hash, false);
  }, [ready]);

  if (plans.isError) return <ErrorState error={plans.error} onRetry={() => void plans.refetch()} />;
  if (!ready) return <Spinner />;

  const site: Partial<SiteInfo> = siteQ.data ?? {};
  const { free, tariffs } = plans.data;
  const { rows, best } = pricedTariffs(tariffs);
  const cur = tariffs[0]?.currency || (lang === "ru" ? "RUB" : "EUR");
  const unlimitedPro = tariffs.length > 0 && tariffs.every((x) => x.monthlyDataLimitBytes == null);
  const maxDevices = Math.max(0, ...tariffs.map((x) => x.deviceLimit));
  const payOk = site.paymentsEnabled !== false;
  const invite = (site.inviteRewardDays ?? 0) > 0;
  const supportLink = (s: string) => <a href="#/support">{s}</a>;

  const extras = (
    [
      [
        "apps",
        t("Works in the apps you know"),
        t("Happ, v2RayTun, Hiddify, AmneziaVPN and the Boop apps. Add the key with a QR code or a link."),
      ],
      ["key", t("One key for every location"), t("The subscription link brings in every country and backup connection by itself.")],
      site.telegramBotUrl
        ? [
            "plane",
            t("Everything in the Telegram bot"),
            t("Buy, get your keys, reminders before the end of your plan and support, right in the chat."),
          ]
        : null,
      invite
        ? [
            "gift",
            t("Invite your friends"),
            t("Get {days} of Pro for every friend you invite.", { days: tp(site.inviteRewardDays ?? 0, "{n} day|{n} days") }),
          ]
        : null,
      ["chat", t("Support that answers"), t("Write to us through the form on the website or in the bot, and we will help you connect.")],
    ] as ([IconName, string, string] | null)[]
  ).filter((x): x is [IconName, string, string] => !!x);

  const steps: [string, string][] = [
    [t("Choose a plan"), t("Start with the free plan or get Pro for a month or longer.")],
    [t("Get your key"), t("Your key with a QR code and a link appears in your account right away.")],
    [t("Turn on the VPN"), t("Add the key to the app in one tap and connect.")],
  ];

  const faq: [string, string][] = [
    [
      t("How is Boop different from a regular VPN?"),
      t(
        "Boop is built for speed. It uses modern protocols, has servers in several countries and picks the server with the lowest ping for you, so it suits online games, video calls and streaming.",
      ),
    ],
    [
      t("Is there a free plan?"),
      t("Yes. The free plan works in the Boop app with {data} and {devices}, no payment needed.", {
        data: free.monthlyDataLimitBytes
          ? t("{n} GB a month", { n: Math.round(free.monthlyDataLimitBytes / 1e9) })
          : t("Unlimited data").toLowerCase(),
        devices: tp(free.deviceLimit, "{n} device|{n} devices"),
      }),
    ],
    ...(maxDevices
      ? ([
          [
            t("How many devices can I connect?"),
            t("Paid plans include up to {devices}. The subscription key for Happ, v2RayTun and Hiddify is not counted in this limit.", {
              devices: tp(maxDevices, "{n} device|{n} devices"),
            }),
          ],
        ] as [string, string][])
      : []),
    [
      t("Do you keep logs?"),
      t(
        "We don't keep the history of the sites you visit. To run the service we only need your e-mail or Telegram account and the amount of traffic, to count your plan's limits.",
      ),
    ],
    [
      t("How do I connect?"),
      t(
        "Install the Boop app or Happ, v2RayTun, Hiddify or AmneziaVPN. Then scan the QR code from your account or paste the key link, and turn the VPN on.",
      ),
    ],
    [t("When does access turn on after payment?"), t("Right after the payment. Pro turns on and your key appears in your account.")],
    [
      t("What if the VPN stops connecting?"),
      t(
        "Your key has several locations and backup connections, so the app can switch to another one. If it still doesn't work, write to support.",
      ),
    ],
  ];

  return (
    <div className="lp" ref={root} data-page="home">
      <section className="hero band">
        <div className="glow g1" />
        <div className="glow g2" />
        <div className="gridlines" />
        <div className="shell hero-in">
          <div className="hero-copy">
            <span className="eyebrow pill">
              <i />
              {t("Great for online games and streaming")}
            </span>
            <h1>
              {t("High speed. Low ping.")} <span className="grad">{t("Your data stays safe.")}</span>
            </h1>
            <p className="lead">
              {t(
                "Boop encrypts all your traffic, keeps the ping low for online games and keeps no browsing history. Setting up takes a couple of minutes on a phone or a computer.",
              )}
            </p>
            <div className="cta">
              <a className={btn("grad", true)} href="#pricing">
                {t("Choose your plan")}
                <Icon name="arrow" />
              </a>
              <a className={btn("ghost-light", true)} href="#download">
                <Icon name="download" />
                {t("Download the app")}
              </a>
            </div>
            <ul className="trust">
              <li>
                <Icon name="check" />
                {t("Free plan, no payment needed")}
              </li>
              <li>
                <Icon name="check" />
                {t("We don't keep browsing history")}
              </li>
              <li>
                <Icon name="check" />
                {t("For phones and computers")}
              </li>
            </ul>
          </div>
          <HeroVisual />
        </div>
      </section>

      <section className="compat">
        <div className="shell">
          <span>{t("Works in the apps you know")}</span>
          <ul>
            {["Boop", "Happ", "v2RayTun", "Hiddify", "AmneziaVPN", "v2rayNG"].map((n) => (
              <li key={n}>{n}</li>
            ))}
          </ul>
        </div>
      </section>

      <section className="sec" id="features">
        <div className="shell">
          <SectionHead
            eyebrow={t("Why Boop")}
            title={t("Speed, ping and protection without compromise")}
            sub={t("We built Boop around the three things that matter most in a VPN.")}
          />
          <div className="pillars">
            <Pillar
              ic="bolt"
              title={t("High speed")}
              lead={t("Boop runs on modern fast protocols that use the channel carefully. Video, downloads and calls run smoothly.")}
              lines={[
                t("Modern protocols with minimal overhead"),
                unlimitedPro && t("Unlimited traffic on Pro"),
                t("Good for HD video and large downloads"),
              ]}
            />
            <Pillar
              ic="ping"
              title={t("Low ping")}
              lead={t("The closer the server, the faster the response. Pick a country by its ping or let Boop choose.")}
              lines={[
                t("Servers in several countries"),
                t("The ping to every location is shown in the app"),
                t("The subscription picks the best server by itself"),
              ]}
            />
            <Pillar
              ic="shield"
              title={t("Data security")}
              lead={t("All traffic goes through an encrypted tunnel, so your data stays private on any network, even on public Wi-Fi.")}
              lines={[
                t("Modern encryption of all traffic"),
                t("Protection on public Wi-Fi"),
                t("We don't keep browsing history"),
                t("Sign in with a one-time code, no password"),
              ]}
            />
          </div>
          <div className={`bento n${extras.length}`}>
            {extras.map(([ic, title, text]) => (
              <article key={ic} className="tile reveal">
                <div className="tile-ic">
                  <Icon name={ic} />
                </div>
                <h3>{title}</h3>
                <p>{text}</p>
              </article>
            ))}
          </div>
        </div>
      </section>

      <section className="sec tint" id="how">
        <div className="shell">
          <SectionHead
            eyebrow={t("How it works")}
            title={t("Connected in three steps")}
            sub={t("No settings to figure out. It takes a couple of minutes.")}
          />
          <ol className="steps3">
            {steps.map(([title, text], i) => (
              <li key={title} className="reveal">
                <span className="num">{i + 1}</span>
                <h3>{title}</h3>
                <p>{text}</p>
              </li>
            ))}
          </ol>
        </div>
      </section>

      <section className="sec" id="pricing">
        <div className="shell">
          <SectionHead
            eyebrow={t("Pricing")}
            title={t("Simple plans, no hidden fees")}
            sub={t("Start for free and move to Pro when you need more.")}
            extra={site.testPayments ? <span className="badge test">{t("Test payments, no real money")}</span> : null}
          />
          {payOk ? null : (
            <Notice tone="bad" className="mx-auto max-w-xl">
              {t("Payments are temporarily unavailable")}
            </Notice>
          )}
          <div className={`plans n${rows.length + 1}`}>
            <FreePlanCard free={free} cur={cur} />
            {rows.map((r, i) => (
              <TariffPlan key={r.x.id} r={r} best={i === best} payOk={payOk} />
            ))}
          </div>
          <ul className="assure">
            <li>
              <Icon name="bolt" />
              {t("Access turns on right after payment")}
            </li>
            <li>
              <Icon name="key" />
              {t("The key and its QR code are in your account at once")}
            </li>
            <li>
              <Icon name="chat" />
              <span>{rich(t("Questions about payment? {a}Write to support{end}"), { a: supportLink })}</span>
            </li>
          </ul>
        </div>
      </section>

      <section className="sec tint" id="download">
        <div className="shell">
          <SectionHead
            eyebrow={t("Apps")}
            title={t("Get the app")}
            sub={t("Boop works on phones and computers. Your key also works in Happ, v2RayTun, Hiddify and v2rayNG.")}
          />
          <Downloads site={site} />
        </div>
      </section>

      <section className="sec" id="faq">
        <div className="shell narrow-shell">
          <SectionHead eyebrow={t("Questions")} title={t("Frequently asked questions")} />
          <div className="faq">
            {faq.map(([q, a], i) => (
              <details key={q} className="reveal" open={i === 0}>
                <summary>
                  {q}
                  <span className="chev" aria-hidden="true" />
                </summary>
                <p>{a}</p>
              </details>
            ))}
          </div>
          <p className="faq-more">{rich(t("Didn't find an answer? {a}Write to support{end}"), { a: supportLink })}</p>
        </div>
      </section>

      <section className="final band">
        <div className="glow g1" />
        <div className="glow g2" />
        <div className="shell final-in reveal">
          <h2>{t("Ready to connect?")}</h2>
          <p>{t("Start for free, and move to Pro when you need more.")}</p>
          <div className="cta">
            <a className={btn("grad", true)} href="#pricing">
              {t("Choose your plan")}
              <Icon name="arrow" />
            </a>
            <a className={btn("ghost-light", true)} href="#download">
              {t("Start for free")}
            </a>
          </div>
        </div>
      </section>
    </div>
  );
}

function Pillar({ ic, title, lead, lines }: { ic: IconName; title: string; lead: string; lines: (string | false)[] }) {
  return (
    <article className="pillar reveal">
      <div className="pillar-ic">
        <Icon name={ic} />
      </div>
      <h3>{title}</h3>
      <p>{lead}</p>
      <Checks lines={lines} />
    </article>
  );
}
