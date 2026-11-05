package gateway

import (
	"os"
	"os/exec"
	"strings"
)

// MachineName is the name hello gives the machine unless Options say
// otherwise: what its owner calls it, a Mac's Computer Name (System
// Settings › General › About), or else its host name.
func MachineName() string {
	computer, _ := exec.Command("scutil", "--get", "ComputerName").Output()
	host, _ := os.Hostname()
	return machineName(string(computer), host)
}

// machineName is the computer's name if it has one, or the host name
// without the .local Bonjour adds.
func machineName(computer, host string) string {
	if name := strings.TrimSpace(computer); name != "" {
		return name
	}
	return strings.TrimSuffix(host, ".local")
}
