package gateway

import (
	"context"
	"strings"
	"testing"

	"github.com/ahmd-sh/wallflower/internal/protocol"
)

func TestMachineName(t *testing.T) {
	for _, c := range []struct{ computer, host, want string }{
		{"vishingstar\n", "vishingstar.local", "vishingstar"},
		{"Ahmed’s MacBook Pro\n", "Ahmeds-MacBook-Pro.local", "Ahmed’s MacBook Pro"},
		{"", "vishingstar.local", "vishingstar"},
		{"", "build-box", "build-box"},
	} {
		if got := machineName(c.computer, c.host); got != c.want {
			t.Errorf("machineName(%q, %q) = %q, want %q", c.computer, c.host, got, c.want)
		}
	}
	for info, want := range map[string]string{
		"PRETTY_HOSTNAME=\"Marwan's Laptop\"\nCHASSIS=laptop\n": "Marwan's Laptop",
		"CHASSIS=laptop\nPRETTY_HOSTNAME=gamer\n":               "gamer",
		"CHASSIS=laptop\n": "",
	} {
		if got := pretty(info); got != want {
			t.Errorf("pretty(%q) = %q, want %q", info, got, want)
		}
	}
	if name := MachineName(); name == "" || strings.HasSuffix(name, ".local") {
		t.Errorf("MachineName() = %q", name)
	}
}

func TestHelloNamesTheMachine(t *testing.T) {
	c := tmuxBackend.connectOpts(t, context.Background(), 60, 20, Options{Machine: "vishingstar"})
	_, m := c.expect("hello", func(_ protocol.Frame, m protocol.Message) bool { _, ok := m.(*protocol.ServerHello); return ok })
	if got := m.(*protocol.ServerHello).Machine; got != "vishingstar" {
		t.Errorf("hello names the machine %q, want vishingstar", got)
	}
}
