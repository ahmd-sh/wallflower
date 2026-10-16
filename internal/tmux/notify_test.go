package tmux

import (
	"bytes"
	"reflect"
	"testing"
)

func TestUnescape(t *testing.T) {
	cases := map[string]string{
		`plain`:             "plain",
		`\033[1mhi\015\012`: "\x1b[1mhi\r\n",
		`back\134slash`:     `back\slash`,
		`✓ utf8`:            "✓ utf8",
		`trailing\03`:       `trailing\03`,
		`not\8octal`:        `not\8octal`,
	}
	for in, want := range cases {
		if got := unescape(in); !bytes.Equal(got, []byte(want)) {
			t.Errorf("unescape(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestParseNotification(t *testing.T) {
	cases := []struct {
		line string
		want Notification
	}{
		{`%output %1 a\033b`, Notification{Type: "output", Pane: "%1", Data: []byte("a\x1bb")}},
		{`%extended-output %2 15 : x\015`, Notification{Type: "extended-output", Pane: "%2", AgeMs: 15, Data: []byte("x\r")}},
		{`%extended-output %2 15 future args : x : y`, Notification{Type: "extended-output", Pane: "%2", AgeMs: 15, Data: []byte("x : y")}},
		{`%layout-change @1 {"V":2} {"V":2} *Z`, Notification{Type: "layout-change", Window: "@1", Layout: `{"V":2}`, VisibleLayout: `{"V":2}`, WindowFlags: "*Z"}},
		{`%layout-change @1 {"V":2} {"V":2} `, Notification{Type: "layout-change", Window: "@1", Layout: `{"V":2}`, VisibleLayout: `{"V":2}`}},
		{`%window-add @3`, Notification{Type: "window-add", Window: "@3"}},
		{`%window-renamed @0 claude: fix tests`, Notification{Type: "window-renamed", Window: "@0", Name: "claude: fix tests"}},
		{`%window-pane-changed @1 %4`, Notification{Type: "window-pane-changed", Window: "@1", Pane: "%4"}},
		{`%session-changed $0 o`, Notification{Type: "session-changed", Session: "$0", Name: "o"}},
		{`%session-renamed $0 probe2`, Notification{Type: "session-renamed", Session: "$0", Name: "probe2"}},
		{`%session-window-changed $0 @2`, Notification{Type: "session-window-changed", Session: "$0", Window: "@2"}},
		{`%client-session-changed /dev/ttys003 $1 work`, Notification{Type: "client-session-changed", Client: "/dev/ttys003", Session: "$1", Name: "work"}},
		{`%subscription-changed agent $0 @0 0 %0 : waiting`, Notification{Type: "subscription-changed", Name: "agent", Session: "$0", Window: "@0", Pane: "%0", Value: "waiting"}},
		{`%subscription-changed agent $0 @0 0 %0 : `, Notification{Type: "subscription-changed", Name: "agent", Session: "$0", Window: "@0", Pane: "%0"}},
		{`%subscription-changed win $0 @0 0 - : zsh`, Notification{Type: "subscription-changed", Name: "win", Session: "$0", Window: "@0", Value: "zsh"}},
		{`%pane-mode-changed %5`, Notification{Type: "pane-mode-changed", Pane: "%5"}},
		{`%sessions-changed`, Notification{Type: "sessions-changed"}},
		{`%exit`, Notification{Type: "exit"}},
		{`%exit server exited`, Notification{Type: "exit", Value: "server exited"}},
		{`%some-future-thing a b`, Notification{Type: "some-future-thing", Value: "a b"}},
	}
	for _, tc := range cases {
		got, ok := parseNotification(tc.line)
		if !ok || !reflect.DeepEqual(got, tc.want) {
			t.Errorf("parseNotification(%q)\n got %+v\nwant %+v", tc.line, got, tc.want)
		}
	}
	if _, ok := parseNotification("not a notification"); ok {
		t.Error("parsed a line without a leading %")
	}
}

func TestParseLayoutFloating(t *testing.T) {
	// Captured from tmux 3.8: two tiled panes and two floating panes.
	const s = `{"V":2,"L":{"t":"h","w":120,"h":40,"x":0,"y":0,"c":[{"t":"p","w":60,"h":40,"x":0,"y":0,"l":2,"i":0,"I":"%0"},{"t":"p","w":59,"h":40,"x":61,"y":0,"l":1,"i":1,"I":"%1"},{"t":"p","w":38,"h":8,"x":4,"y":2,"l":0,"i":2,"z":1,"I":"%2"},{"t":"p","w":28,"h":6,"x":8,"y":4,"a":true,"i":3,"z":0,"I":"%3"}]}}`
	l, err := ParseLayout(s)
	if err != nil {
		t.Fatal(err)
	}
	panes := l.Root.Panes()
	if len(panes) != 4 {
		t.Fatalf("got %d panes, want 4", len(panes))
	}
	var floating []string
	for _, p := range panes {
		if p.Floating() {
			floating = append(floating, p.Pane)
		}
	}
	if !reflect.DeepEqual(floating, []string{"%2", "%3"}) {
		t.Errorf("floating = %v, want [%%2 %%3]", floating)
	}
	if !panes[3].Active || *panes[3].Z != 0 {
		t.Errorf("want %%3 active at the front, got %+v", panes[3])
	}
	if _, err := ParseLayout("6809,120x40,0,0[120x20,0,0,3,120x19,0,21,4]"); err == nil {
		t.Error("old layout format parsed without error")
	}
}
