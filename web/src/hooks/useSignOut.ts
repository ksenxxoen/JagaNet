/**
 * Signing out (the "Sign out" button of the account in app.js): the server forgets the session
 * (an error there does not keep the person signed in), the token and the cached data of the
 * person are dropped, and the landing page opens.
 */
import { useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useCallback, useState } from "react";
import { keys } from "@/api/keys";
import { post } from "@/api/typed";
import { useSession } from "@/store/session";

export function useSignOut() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [pending, setPending] = useState(false);
  const signOut = useCallback(async () => {
    setPending(true);
    try {
      await post("/auth/logout");
    } catch {
      /* the session ends here anyway */
    }
    useSession.getState().signOut();
    qc.removeQueries({ queryKey: keys.me() });
    setPending(false);
    await navigate({ to: "/" });
  }, [qc, navigate]);
  return { signOut, pending };
}
