package winsvc

import (
	"errors"
	"path/filepath"
	"reflect"
	"strings"
	"syscall"
	"testing"
)

type fakeSys struct {
	exe, dir, data string
	state          State
	task           bool
	calls          []string
	failOn         string
	files          map[string]string
}

func newFake() *fakeSys {
	return &fakeSys{exe: `C:\Users\u\Downloads\Boop.exe`, dir: `C:\Program Files\Boop`, data: `C:\ProgramData\Boop`, files: map[string]string{}}
}

func (f *fakeSys) rec(s string) error {
	f.calls = append(f.calls, s)
	if f.failOn != "" && strings.HasPrefix(s, f.failOn) {
		return errors.New("boom")
	}
	return nil
}
func (f *fakeSys) Exe() string         { return f.exe }
func (f *fakeSys) InstallDir() string  { return f.dir }
func (f *fakeSys) DataDir() string     { return f.data }
func (f *fakeSys) Logf(string, ...any) {}
func (f *fakeSys) ServiceState() (State, error) {
	return f.state, nil
}
func (f *fakeSys) CreateService(b string) error { f.state = Stopped; return f.rec("create " + b) }
func (f *fakeSys) StartService() error {
	if err := f.rec("start"); err != nil {
		return err
	}
	f.state = Running
	return nil
}
func (f *fakeSys) StopService() error       { f.state = Stopped; return f.rec("stop") }
func (f *fakeSys) DeleteService() error     { f.state = NotInstalled; return f.rec("delete") }
func (f *fakeSys) SecureDir(d string) error { return f.rec("secure " + d) }
func (f *fakeSys) CopyFile(s, d string) error {
	f.files[d] = s
	return f.rec("copy " + s + " " + d)
}
func (f *fakeSys) WriteFile(p string, _ []byte) error { return f.rec("write " + p) }
func (f *fakeSys) RemoveFile(p string) error          { return f.rec("rm " + p) }
func (f *fakeSys) RemoveDir(p string) error           { return f.rec("rmdir " + p) }
func (f *fakeSys) AutostartTaskExists() bool          { return f.task }
func (f *fakeSys) DeleteAutostartTask() error         { f.task = false; return f.rec("deltask") }

func TestInstallFresh(t *testing.T) {
	f := newFake()
	f.task = true
	if err := Install(f, Extras{"wintun.dll": []byte("x")}); err != nil {
		t.Fatal(err)
	}
	want := []string{
		`secure C:\Program Files\Boop`,
		`copy C:\Users\u\Downloads\Boop.exe ` + filepath.Join(f.dir, "Boop.exe"),
		`write ` + filepath.Join(f.dir, "wintun.dll"),
		`secure C:\ProgramData\Boop`,
		`create ` + filepath.Join(f.dir, "Boop.exe"),
		`start`,
		`deltask`,
	}
	if !reflect.DeepEqual(f.calls, want) {
		t.Fatalf("calls:\n%q\nwant\n%q", f.calls, want)
	}
	if f.state != Running || f.task {
		t.Fatalf("state %v task %v", f.state, f.task)
	}
}

// The service points at the protected copy, never at where the user ran it from.
func TestInstallPointsServiceAtProtectedCopy(t *testing.T) {
	f := newFake()
	_ = Install(f, nil)
	for _, c := range f.calls {
		if strings.HasPrefix(c, "create ") && !strings.Contains(c, f.dir) {
			t.Fatalf("service binary outside the install dir: %s", c)
		}
	}
}

func TestInstallUpgradeStopsRunningService(t *testing.T) {
	f := newFake()
	f.state = Running
	if err := Install(f, nil); err != nil {
		t.Fatal(err)
	}
	if f.calls[0] != "stop" {
		t.Fatalf("not stopped first: %q", f.calls)
	}
}

// Run from the install dir itself (the in-place update): nothing to copy.
func TestInstallFromInstallDir(t *testing.T) {
	f := newFake()
	f.exe = filepath.Join(f.dir, "Boop.exe")
	_ = Install(f, nil)
	for _, c := range f.calls {
		if strings.HasPrefix(c, "copy") {
			t.Fatalf("copied onto itself: %q", f.calls)
		}
	}
}

func TestInstallErrorNamesTheStep(t *testing.T) {
	for _, step := range []string{"secure", "copy", "create", "start"} {
		f := newFake()
		f.failOn = step
		err := Install(f, nil)
		if err == nil {
			t.Fatalf("%s: no error", step)
		}
		if f.state == Running {
			t.Errorf("%s: running after a failure", step)
		}
	}
}

// A task that cannot be deleted does not fail the install.
func TestInstallTaskNotFatal(t *testing.T) {
	f := newFake()
	f.task = true
	f.failOn = "deltask"
	if err := Install(f, nil); err != nil {
		t.Fatal(err)
	}
}

func TestUninstall(t *testing.T) {
	f := newFake()
	f.state = Running
	f.task = true
	if err := Uninstall(f); err != nil {
		t.Fatal(err)
	}
	if f.state != NotInstalled || f.task {
		t.Fatalf("state %v task %v", f.state, f.task)
	}
	if f.calls[0] != "stop" || f.calls[1] != "delete" {
		t.Fatalf("order: %q", f.calls)
	}
	joined := strings.Join(f.calls, "\n")
	for _, w := range []string{"rmdir " + f.dir, "rmdir " + f.data} {
		if !strings.Contains(joined, w) {
			t.Errorf("missing %q in\n%s", w, joined)
		}
	}
	// Nothing installed: still clean, no error.
	g := newFake()
	if err := Uninstall(g); err != nil {
		t.Fatal(err)
	}
	for _, c := range g.calls {
		if c == "stop" || c == "delete" {
			t.Fatalf("touched a service that is not there: %q", g.calls)
		}
	}
}

func TestNeed(t *testing.T) {
	cases := []struct {
		svc      string
		answered bool
		mine     string
		want     bool
	}{
		{"", false, "1.4.0", true},
		{"1.4.0", true, "1.4.0", false},
		{"1.3.1", true, "1.4.0", true},  // new UI, old service
		{"1.5.0", true, "1.4.0", false}, // old UI, newer service: compatible, no downgrade
		{"1.4.0", true, "dev", false},
		{"", false, "dev", true},
	}
	for _, c := range cases {
		if got, _ := Need(c.svc, c.answered, c.mine); got != c.want {
			t.Errorf("%+v: got %v", c, got)
		}
	}
}

type fakeAuto struct {
	task, key bool
	cmd       string
	failSet   bool
	failDel   bool
}

func (a *fakeAuto) RunKeyOn() bool { return a.key }
func (a *fakeAuto) SetRunKey(c string) error {
	if a.failSet {
		return errors.New("no")
	}
	a.key, a.cmd = true, c
	return nil
}
func (a *fakeAuto) ClearRunKey() error { a.key = false; return nil }
func (a *fakeAuto) TaskExists() bool   { return a.task }
func (a *fakeAuto) DeleteTask() error {
	if a.failDel {
		return errors.New("access denied")
	}
	a.task = false
	return nil
}

func TestMigrateAutostart(t *testing.T) {
	exe := `C:\Program Files\Boop\Boop.exe`
	a := &fakeAuto{task: true}
	if err := MigrateAutostart(a, exe, true); err != nil {
		t.Fatal(err)
	}
	if !a.key || a.task || a.cmd != `"`+exe+`" --tray` {
		t.Fatalf("%+v", a)
	}
	// The task could not be deleted: autostart is on through the key anyway.
	b := &fakeAuto{task: true, failDel: true}
	if err := MigrateAutostart(b, exe, true); err != nil || !b.key {
		t.Fatalf("%+v %v", b, err)
	}
	// The key could not be written: the task stays, nothing is lost.
	c := &fakeAuto{task: true, failSet: true}
	if err := MigrateAutostart(c, exe, true); err == nil || !c.task {
		t.Fatalf("%+v %v", c, err)
	}
	// Autostart was off: stays off.
	d := &fakeAuto{}
	if err := MigrateAutostart(d, exe, true); err != nil || d.key {
		t.Fatalf("%+v %v", d, err)
	}
	// Key on, pointing elsewhere: re-pointed.
	e := &fakeAuto{key: true, cmd: "old"}
	_ = MigrateAutostart(e, exe, true)
	if e.cmd != RunCommand(exe) {
		t.Fatalf("%+v", e)
	}
}

// The service is not working yet (broken install, declined prompt): the old
// autostart task stays, it is the one that still starts the program.
func TestMigrateAutostartKeepsTaskWithoutService(t *testing.T) {
	exe := `C:\Program Files\Boop\Boop.exe`
	a := &fakeAuto{task: true}
	if err := MigrateAutostart(a, exe, false); err != nil || !a.task || a.key {
		t.Fatalf("%+v %v", a, err)
	}
}

// Every step that can fail names itself, with the Windows code when there is one.
func TestInstallStepErrors(t *testing.T) {
	for _, step := range []string{"secure", "copy", "write", "create", "start"} {
		f := newFake()
		f.failOn = step
		err := Install(f, Extras{"wintun.dll": []byte("x")})
		var se *StepError
		if !errors.As(err, &se) || se.Step == "" {
			t.Fatalf("%s: %v", step, err)
		}
	}
	err := stepErr("secure-program-dir", syscall.Errno(1307))
	step, code, _ := Describe(err)
	if step != "secure-program-dir" || code != 1307 {
		t.Fatalf("%q %d", step, code)
	}
	if m := Message(err); !strings.Contains(m, "1307") || !strings.Contains(m, "secure-program-dir") {
		t.Fatal(m)
	}
	if _, code, _ := Describe(errors.New("plain")); code != 0 {
		t.Fatal(code)
	}
}

// 1.4.0 left a half install: files with wrong rights, no service. Running the
// installer again finishes it.
func TestInstallRepairsBrokenInstall(t *testing.T) {
	f := newFake()
	f.state = NotInstalled
	f.exe = filepath.Join(f.dir, "Boop.exe")
	if err := Install(f, nil); err != nil {
		t.Fatal(err)
	}
	if f.state != Running {
		t.Fatalf("state %v", f.state)
	}
}

// The setup program sets and clears the user's Run key through the same
// command the client itself writes.
func TestSetAutostart(t *testing.T) {
	exe := `C:\Program Files\Boop\Boop.exe`
	a := &fakeAuto{}
	if err := SetAutostart(a, exe, true); err != nil || !a.key || a.cmd != `"`+exe+`" --tray` {
		t.Fatalf("%+v %v", a, err)
	}
	if err := SetAutostart(a, exe, false); err != nil || a.key {
		t.Fatalf("%+v %v", a, err)
	}
	b := &fakeAuto{failSet: true}
	if err := SetAutostart(b, exe, true); err == nil {
		t.Fatal("a failed write must be reported")
	}
}

// The setup program's uninstaller removes the files itself: RemoveService takes
// the service and the old task and touches no file or directory.
func TestRemoveServiceLeavesFiles(t *testing.T) {
	f := newFake()
	f.state = Running
	f.task = true
	if err := RemoveService(f); err != nil {
		t.Fatal(err)
	}
	if f.state != NotInstalled || f.task {
		t.Fatalf("state %v task %v", f.state, f.task)
	}
	for _, c := range f.calls {
		if strings.HasPrefix(c, "rm ") || strings.HasPrefix(c, "rmdir ") {
			t.Fatalf("RemoveService touched a file: %q", f.calls)
		}
	}
	// A service that will not delete is reported.
	g := newFake()
	g.state = Stopped
	g.failOn = "delete"
	if err := RemoveService(g); err == nil {
		t.Fatal("a failed delete must be reported")
	}
}
