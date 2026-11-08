/**
 * Texts with links inside, the way app.js writes them: t("I accept the {a}terms of use{end}") with
 * the tags filled by the screen. The translation keeps the {tag}…{end} markers (unknown
 * placeholders stay as they are), and rich() turns each marked part into the element given for
 * its tag; the rest stays plain text, so nothing is parsed as HTML.
 *
 *   rich(t("Questions about payment? {a}Write to support{end}"), { a: (s) => <a href="#/support">{s}</a> })
 */
import { Fragment, type ReactNode } from "react";

export type RichTags = Record<string, (children: string) => ReactNode>;

export function rich(text: string, tags: RichTags): ReactNode[] {
  const out: ReactNode[] = [];
  const re = /\{(\w+)\}(.*?)\{end\}/g;
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text))) {
    const [all, tag, inner] = m as unknown as [string, string, string];
    const make = tags[tag];
    if (!make) continue;
    if (m.index > last) out.push(text.slice(last, m.index));
    out.push(<Fragment key={out.length}>{make(inner)}</Fragment>);
    last = m.index + all.length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}
