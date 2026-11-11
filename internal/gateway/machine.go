package gateway

import (
	"os"
	"os/exec"
	"runtime"
	"strings"
)

// MachineName is the name hello gives the machine unless Options say
// otherwise: what its owner calls it, a Mac's Computer Name (System
// Settings › General › About) or Linux's pretty host name (hostnamectl
// --pretty), or else its host name.
func MachineName() string {
	var computer string
	if runtime.GOOS == "darwin" {
		out, _ := exec.Command("scutil", "--get", "ComputerName").Output()
		computer = string(out)
	} else {
		info, _ := os.ReadFile("/etc/machine-info")
		computer = pretty(string(info))
	}
	host, _ := os.Hostname()
	return machineName(computer, host)
}

// pretty is the PRETTY_HOSTNAME machine-info sets, unquoted.
func pretty(info string) string {
	for _, line := range strings.Split(info, "\n") {
		if v, ok := strings.CutPrefix(strings.TrimSpace(line), "PRETTY_HOSTNAME="); ok {
			if len(v) >= 2 && (v[0] == '"' || v[0] == '\'') && v[len(v)-1] == v[0] {
				v = v[1 : len(v)-1]
			}
			return v
		}
	}
	return ""
}

// machineName is the computer's name if it has one, or the host name
// without the .local Bonjour adds.
func machineName(computer, host string) string {
	if name := strings.TrimSpace(computer); name != "" {
		return name
	}
	return strings.TrimSuffix(host, ".local")
}
