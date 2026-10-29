package core

import (
	"regexp"
	"strings"
)

// Redact cuts secrets out of text that leaves the computer (the problem report,
// issue #11): WireGuard / AmneziaWG keys in base64 and in hex (UAPI), the values
// of private_key / preshared_key / token / secret / password and the like, Bearer
// tokens, key links (/k/…), vpn:// and vless:// links, and long opaque tokens.
// Addresses, host names, versions and problem codes stay: they are what the
// report is for.
func Redact(s string) string {
	for _, r := range redactions {
		s = r.re.ReplaceAllString(s, r.with)
	}
	// A long run of letters, digits, '-' and '_' is a token or a key, unless it is a
	// UUID (device and user ids help the report and are not secrets).
	return opaque.ReplaceAllStringFunc(s, func(m string) string {
		if uuid.MatchString(m) || !strings.ContainsAny(m, "0123456789") {
			return m
		}
		return hidden
	})
}

const (
	hidden       = "[скрыто]"
	hiddenKey    = "[ключ скрыт]"
	hiddenLink   = "[ссылка скрыта]"
	hiddenConfig = "[конфиг скрыт]"
)

type redaction struct {
	re   *regexp.Regexp
	with string
}

var (
	redactions = []redaction{
		// A config pasted whole: from [Interface] to the end of its block.
		{regexp.MustCompile(`(?is)\[Interface\].*?(\n\s*\n|$)`), hiddenConfig + "$1"},
		// Key links of the panel (https://…/k/<token>, also without the host) and AmneziaVPN's vpn:// links.
		{regexp.MustCompile(`(?i)(?:https?://[^\s"'<>]*)?/k/[^\s"'<>]+`), hiddenLink},
		{regexp.MustCompile(`(?i)\bvpn://[^\s"'<>]+`), hiddenLink},
		// vless:// links (issue #29): the UUID in them is the VLESS credential.
		{regexp.MustCompile(`(?i)\bvless://[^\s"'<>]+`), hiddenLink},
		// Authorization: Bearer <token>.
		{regexp.MustCompile(`(?i)\b(bearer)\s+[^\s"',;]+`), "$1 " + hidden},
		// name=value, name: value and "name":"value" for names that hold secrets.
		{regexp.MustCompile(`(?i)("?\b(?:private_?key|preshared_?key|header_?protection_?key|secret|password|passwd|token|sealed|dev_?code)\b"?\s*[:=]\s*)("[^"]*"|[^\s,;&}]+)`), "$1" + hidden},
		// 32-byte keys in base64 (WireGuard, AmneziaWG): 43 characters and '='.
		{regexp.MustCompile(`(^|[^A-Za-z0-9+/])[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=`), "$1" + hiddenKey},
		// The same keys in hex, as UAPI writes them.
		{regexp.MustCompile(`\b[0-9a-fA-F]{64}\b`), hiddenKey},
	}
	opaque = regexp.MustCompile(`\b[A-Za-z0-9_-]{32,}\b`)
	uuid   = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)
)
