package qr

import (
	"strings"
	"testing"
)

func TestSmallestVersion(t *testing.T) {
	for _, c := range []struct {
		n, size int
		level   Level
	}{
		{17, 21, L}, {18, 25, L}, {230, 53, L}, {231, 57, L}, {14, 21, M}, {15, 25, M},
	} {
		code, err := Encode([]byte(strings.Repeat("a", c.n)), c.level)
		if err != nil {
			t.Fatal(err)
		}
		if code.Size != c.size {
			t.Errorf("%d bytes at %d: size %d, want %d", c.n, c.level, code.Size, c.size)
		}
	}
	if _, err := Encode(make([]byte, 3000), L); err == nil {
		t.Error("3000 bytes fit")
	}
}

func TestFinders(t *testing.T) {
	c, _ := Encode([]byte("wallflower"), L)
	ring := func(x, y int) {
		for d := 0; d < 7; d++ {
			for _, p := range [][2]int{{x + d, y}, {x + d, y + 6}, {x, y + d}, {x + 6, y + d}} {
				if !c.Dark(p[0], p[1]) {
					t.Fatalf("finder at %d,%d: %v light", x, y, p)
				}
			}
		}
	}
	ring(0, 0)
	ring(c.Size-7, 0)
	ring(0, c.Size-7)
}

func TestTerminal(t *testing.T) {
	c, _ := Encode([]byte("wallflower"), L)
	lines := strings.Split(strings.TrimSuffix(c.Terminal(2), "\n"), "\n")
	if want := (c.Size + 4 + 1) / 2; len(lines) != want {
		t.Fatalf("%d lines, want %d", len(lines), want)
	}
	if !strings.Contains(lines[1], "█") {
		t.Errorf("no full blocks where the finders are: %q", lines[1])
	}
}
