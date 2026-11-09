/**
 * The users tab (ctlUsers() in control.js): a search by e-mail, Telegram, key UUID, key link or IP
 * in ?q= (the newest users without it), and a user's card on ?id=.
 */
import { useQuery } from "@tanstack/react-query";
import { useSearch } from "@tanstack/react-router";
import { Toolbar } from "@/components/layout";
import { SearchBox } from "@/components/SearchBox";
import { ErrorState, Spinner } from "@/components/ui";
import { useI18n } from "@/i18n";
import { UserTable } from "../common";
import { usersQuery } from "../queries";
import { UserCard } from "./UserCard";

export default function Users() {
  const { id } = useSearch({ strict: false }) as { id?: string };
  return id ? <UserCard id={id} /> : <UserList />;
}

function UserList() {
  const { t } = useI18n();
  const { q = "" } = useSearch({ strict: false }) as { q?: string };
  const r = useQuery({ ...usersQuery(q), placeholderData: (prev) => prev });
  const placeholder = r.data?.telegramUsernames
    ? t("E-mail, Telegram id or @name, key UUID, key link, IP")
    : t("E-mail, Telegram id, key UUID, key link, IP");
  return (
    <>
      <Toolbar>
        <SearchBox label={t("Find")} placeholder={placeholder} className="sm:max-w-md" />
      </Toolbar>
      {q ? null : <p className="mb-3 mt-0 text-muted">{t("Newest users")}</p>}
      {r.isError ? (
        <ErrorState error={r.error} onRetry={() => void r.refetch()} />
      ) : !r.data ? (
        <Spinner />
      ) : (
        <UserTable users={r.data.users} empty={t("Nobody found")} inUrl label={t("Users")} />
      )}
    </>
  );
}
