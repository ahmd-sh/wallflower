// Package proc asks the system about processes, for what tmux doesn't say.
package proc

import (
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
)

// TTY is the terminal a process runs on (/dev/ttys003), or "" if it has none.
func TTY(pid int) string {
	out, _ := exec.Command("ps", "-o", "tty=", "-p", strconv.Itoa(pid)).Output()
	if t := strings.TrimSpace(string(out)); t != "" && !strings.HasPrefix(t, "?") {
		return "/dev/" + t
	}
	return ""
}

// Foreground is the name a terminal's foreground process was run as
// (argv[0]), or "" if it can't tell.
func Foreground(tty string) string {
	out, err := exec.Command("ps", "-o", "pid=,tpgid=,comm=", "-t", strings.TrimPrefix(tty, "/dev/")).Output()
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(out), "\n") {
		f := strings.Fields(line)
		if len(f) >= 3 && f[0] == f[1] {
			return strings.TrimPrefix(filepath.Base(strings.Join(f[2:], " ")), "-")
		}
	}
	return ""
}
