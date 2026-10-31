//go:build linux && boopapp

// Boop for Linux (build-linux.sh builds it with -tags boopapp): the same
// window as on Windows, in WebKitGTK through webview_go, running as the user.
// The tunnel lives in boop-helper (root), reached through helper.Client.
// Without a display or WebKitGTK window the page opens in the browser instead
// (127.0.0.1, a random port, a one-time token).
package main

/*
#cgo pkg-config: gtk+-3.0 webkit2gtk-4.0
#include <stdlib.h>
#include <gtk/gtk.h>
#include <webkit2/webkit2.h>

static int boop_gtk_init(void) {
	g_set_prgname("boop");
	g_set_application_name("Boop");
	return gtk_init_check(NULL, NULL);
}
static void boop_setup(void *w, int iconify) {
	GtkWindow *win = GTK_WINDOW(w);
	gtk_window_set_icon_name(win, "boop");
	gtk_window_set_position(win, GTK_WIN_POS_CENTER);
	if (iconify) gtk_window_iconify(win);
}
static void boop_present(void *w) { gtk_window_present(GTK_WINDOW(w)); }

// Runs a script in the page. webview_go's Eval finds WebKit by dlopen of the
// unversioned "libwebkit2gtk-4.x.so", which exists only with the -dev package:
// on a user's machine it silently does nothing and the window stays empty.
// This calls the linked library directly (the web view is the window's child).
static void boop_eval(void *w, const char *js) {
	GtkWidget *child = gtk_bin_get_child(GTK_BIN(w));
	if (!child || !WEBKIT_IS_WEB_VIEW(child)) return;
#if WEBKIT_CHECK_VERSION(2, 40, 0)
	webkit_web_view_evaluate_javascript(WEBKIT_WEB_VIEW(child), js, -1, NULL, NULL, NULL, NULL, NULL);
#else
	webkit_web_view_run_javascript(WEBKIT_WEB_VIEW(child), js, NULL, NULL, NULL);
#endif
}
*/
import "C"

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime/debug"
	"strings"
	"syscall"
	"time"
	"unsafe"

	"github.com/ksenxxoen/JagaNet/desktop/core"
	"github.com/ksenxxoen/JagaNet/desktop/helper"
	"github.com/ksenxxoen/JagaNet/desktop/vpn"
	webview "github.com/webview/webview_go"
	"golang.org/x/sys/unix"
)

// flavor is the Ubuntu release the package was built for ("ubuntu24.04"),
// set by build-linux.sh; the update manifest is asked for that one.
var flavor = ""

const title = "Boop VPN"

func main() {
	showVersion := flag.Bool("version", false, "показать версию и выйти")
	atLogin := flag.Bool("autostart", false, "запуск при входе в систему: окно свёрнуто")
	browser := flag.Bool("browser", false, "открыть Boop в браузере вместо окна")
	flag.Parse()
	if *showVersion {
		if flavor != "" {
			fmt.Printf("Boop %s (%s)\n", version, flavor)
		} else {
			fmt.Printf("Boop %s\n", version)
		}
		return
	}
	defer func() {
		if r := recover(); r != nil {
			log.Printf("panic: %v\n%s", r, debug.Stack())
		}
	}()

	dir := dataDir()
	_ = os.MkdirAll(dir, 0o700)
	logPath := filepath.Join(dir, "boop.log")
	if lw, err := core.NewRotatingWriter(logPath, 1<<20); err == nil {
		log.SetOutput(io.MultiWriter(os.Stderr, lw))
	} else {
		logPath = ""
	}

	// One Boop per user: a second start brings the first one's window forward.
	inst, first := acquireInstance()
	if !first {
		return
	}
	log.Printf("Boop %s (%s) starting", version, osVersion())

	core.SocketControl = vpn.SocketControlLinux
	core.NetworkKind = vpn.NetworkKindLinux
	core.OSVersion = osVersion

	// For trying a build against a test panel (scripts/fakepanel.mjs), as the dev build allows.
	// BOOP_API replaces the whole list of panel addresses (one, or several separated by commas).
	base, site := apiBases(os.Getenv("BOOP_API"))
	tun := helper.NewClient(os.Getenv("BOOP_HELPER_SOCKET"))
	api := core.NewAPI(base, nil)
	api.ManifestQuery = "platform=linux"
	if flavor != "" {
		api.ManifestQuery += "&distro=" + flavor
	}
	app := &core.App{
		API: api, Store: core.LoadStore(dir), Tunnel: tun,
		Ping: func(ctx context.Context, ip string) (time.Duration, error) {
			if d, err := tun.Ping(ctx, ip); err == nil || !errors.Is(err, helper.ErrUnavailable) {
				return d, err
			}
			return vpn.PingICMP(ctx, ip, 0) // no helper: the plain way, through the tunnel if it is up
		},
		Version: version, Site: site, OpenURL: openURL, LogPath: logPath,
		Autostart: setAutostart, AutostartOn: autostartOn,
		UpdateLink: true, Platform: "linux",
	}

	if !*browser && C.boop_gtk_init() != 0 {
		if runWindow(app, inst, *atLogin) {
			app.Disconnect()
			tun.Close()
			log.Printf("Boop closed")
			return
		}
	}
	runBrowser(app, inst)
	app.Disconnect()
	tun.Close()
	log.Printf("Boop closed")
}

// runWindow shows the WebKitGTK window until it is closed. False when no window could be made.
func runWindow(app *core.App, inst net.Listener, minimized bool) bool {
	// BOOP_DEBUG=1: WebKit's inspector and the page's console on stdout.
	verbose := os.Getenv("BOOP_DEBUG") != ""
	w := webview.New(verbose)
	if w.Window() == nil {
		log.Printf("window: WebKitGTK window could not be created")
		return false
	}
	defer w.Destroy()
	w.SetTitle(title)
	w.SetSize(380, 640, webview.HintNone)
	w.SetSize(core.MinWindowW, core.MinWindowH, webview.HintMin)
	win := w.Window()
	C.boop_setup(win, cbool(minimized))

	push := func(js string) {
		w.Dispatch(func() {
			cs := C.CString(js)
			C.boop_eval(win, cs)
			C.free(unsafe.Pointer(cs))
		})
	}
	app.OnState = func(s core.UIState) {
		b, _ := json.Marshal(s)
		push("window.boop && window.boop.update(" + string(b) + ")")
	}
	app.OnToast = func(msg string) {
		b, _ := json.Marshal(map[string]string{"toast": msg})
		push("window.boop && window.boop.update(" + string(b) + ")")
	}
	app.Init()
	go serveInstance(inst, func() { w.Dispatch(func() { C.boop_present(win) }) })
	watchSignals(w.Terminate)
	checkHelper(app)

	// Calls run off the window's thread; the answer comes back through boop.reply.
	_ = w.Bind("boopSend", func(id int, method, arg string) {
		if verbose {
			log.Printf("debug: call %s", method)
		}
		core.Go("call "+method, func() {
			b, _ := json.Marshal(app.Invoke(method, json.RawMessage(arg)))
			push("window.boop.reply(" + itoa(id) + "," + string(b) + ")")
		})
	})
	w.SetHtml(page())
	w.Run()
	return true
}

// runBrowser serves the page on 127.0.0.1 and opens it in the browser.
func runBrowser(app *core.App, inst net.Listener) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		log.Printf("browser: %v", err)
		return
	}
	web := &webHost{app: app, token: newToken(), host: l.Addr().String()}
	app.OnState = func(s core.UIState) { web.send(s) }
	app.OnToast = func(t string) { web.send(map[string]string{"toast": t}) }
	app.Init()
	url := "http://" + web.host + "/?t=" + web.token
	go serveInstance(inst, func() { log.Printf("Boop is already open in the browser") })
	done := make(chan struct{})
	watchSignals(func() { l.Close() })
	go func() {
		_ = http.Serve(l, web.handler())
		close(done)
	}()
	checkHelper(app)
	fmt.Fprintf(os.Stderr, "Boop открыт в браузере. Если он не открылся сам, откройте ссылку:\n%s\nЧтобы выйти, нажмите Ctrl+C.\n", url)
	openURL(url)
	<-done
}

// checkHelper tells the person once, after the window has started, when the
// tunnel's service cannot be used.
func checkHelper(app *core.App) {
	core.Go("helper", func() {
		c := helper.NewClient(os.Getenv("BOOP_HELPER_SOCKET"))
		defer c.Close()
		_, err := c.Hello()
		if err == nil {
			return
		}
		log.Printf("helper: %v", err)
		msg := "Служба Boop не запущена, VPN не подключится. Выполните в терминале: sudo systemctl enable --now boop-helper"
		if !errors.Is(err, helper.ErrUnavailable) {
			msg = "Нет доступа к службе Boop. Выполните в терминале: sudo usermod -aG boop $USER"
		}
		time.Sleep(2 * time.Second)
		if app.OnToast != nil {
			app.OnToast(msg)
		}
	})
}

func watchSignals(stop func()) {
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT, syscall.SIGHUP)
	go func() {
		<-sig
		stop()
	}()
}

func cbool(b bool) C.int {
	if b {
		return 1
	}
	return 0
}

func itoa(i int) string { b, _ := json.Marshal(i); return string(b) }

// dataDir is $XDG_CONFIG_HOME/boop (~/.config/boop). BOOP_DATA overrides it.
func dataDir() string {
	if d := os.Getenv("BOOP_DATA"); d != "" {
		return d
	}
	if d, err := os.UserConfigDir(); err == nil {
		return filepath.Join(d, "boop")
	}
	return filepath.Join(os.TempDir(), fmt.Sprintf("boop-%d", os.Getuid()))
}

// acquireInstance listens on an abstract unix socket named after the user. If
// another Boop has it, that one is asked to show its window and false is returned.
func acquireInstance() (net.Listener, bool) {
	name := fmt.Sprintf("@boop-ui-%d", os.Getuid())
	l, err := net.Listen("unix", name)
	if err == nil {
		return l, true
	}
	if c, derr := net.DialTimeout("unix", name, time.Second); derr == nil {
		_, _ = c.Write([]byte("show\n"))
		c.Close()
		log.Printf("Boop is already running")
		return nil, false
	}
	log.Printf("instance: %v", err)
	return nil, true // cannot tell: better two windows than none
}

func serveInstance(l net.Listener, show func()) {
	if l == nil {
		return
	}
	for {
		c, err := l.Accept()
		if err != nil {
			return
		}
		if line, _ := bufio.NewReader(io.LimitReader(c, 64)).ReadString('\n'); strings.TrimSpace(line) == "show" {
			show()
		}
		c.Close()
	}
}

func openURL(u string) {
	cmd := exec.Command("xdg-open", u)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := cmd.Start(); err != nil {
		log.Printf("open: %v", err)
		return
	}
	go cmd.Wait()
}

// osVersion is "Ubuntu 24.04.1 LTS (Linux 6.8.0-45-generic)".
func osVersion() string {
	name := "Linux"
	if b, err := os.ReadFile("/etc/os-release"); err == nil {
		for _, line := range strings.Split(string(b), "\n") {
			if v, ok := strings.CutPrefix(line, "PRETTY_NAME="); ok {
				name = strings.Trim(v, `"'`)
			}
		}
	}
	var u unix.Utsname
	if unix.Uname(&u) == nil {
		return fmt.Sprintf("%s (Linux %s)", name, unix.ByteSliceToString(u.Release[:]))
	}
	return name
}

/* ---------- starting with the session: ~/.config/autostart/boop.desktop ---------- */

func autostartFile() string {
	d, err := os.UserConfigDir()
	if err != nil {
		return ""
	}
	return filepath.Join(d, "autostart", "boop.desktop")
}

const autostartEntry = `[Desktop Entry]
Type=Application
Name=Boop
Comment=VPN Boop
Exec=boop --autostart
Icon=boop
Terminal=false
X-GNOME-Autostart-enabled=true
`

func setAutostart(on bool) error {
	p := autostartFile()
	if p == "" {
		return errors.New("autostart: no config folder")
	}
	if !on {
		if err := os.Remove(p); err != nil && !os.IsNotExist(err) {
			return err
		}
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		return err
	}
	return os.WriteFile(p, []byte(autostartEntry), 0o644)
}

func autostartOn() bool {
	p := autostartFile()
	if p == "" {
		return false
	}
	b, err := os.ReadFile(p)
	return err == nil && strings.Contains(string(b), "Exec=boop") && !strings.Contains(string(b), "Hidden=true")
}
