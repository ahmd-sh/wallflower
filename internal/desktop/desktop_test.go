package desktop

import (
	"reflect"
	"testing"
)

func scan(parts ...string) []Notification {
	var s Scanner
	var found []Notification
	for _, p := range parts {
		found = append(found, s.Scan([]byte(p))...)
	}
	return found
}

func TestEachKind(t *testing.T) {
	for _, tc := range []struct {
		name, out string
		want      []Notification
	}{
		{"osc 9", "\x1b]9;build done\x07", []Notification{{Body: "build done"}}},
		{"osc 9 ended by ST", "\x1b]9;build done\x1b\\", []Notification{{Body: "build done"}}},
		{"osc 777", "\x1b]777;notify;Claude Code;Claude needs your permission to use Bash\x07",
			[]Notification{{Title: "Claude Code", Body: "Claude needs your permission to use Bash"}}},
		{"osc 777 title only", "\x1b]777;notify;Done;\x07", []Notification{{Body: "Done"}}},
		{"osc 99", "\x1b]99;;Hello\x1b\\", []Notification{{Body: "Hello"}}},
		{"osc 99 in parts", "\x1b]99;i=1:d=0;Tests\x1b\\\x1b]99;i=1:p=body;all 3 pass\x1b\\",
			[]Notification{{Title: "Tests", Body: "all 3 pass"}}},
		{"osc 99 base64", "\x1b]99;e=1;SGVsbG8=\x1b\\", []Notification{{Body: "Hello"}}},
		{"tmux passthrough", "\x1bPtmux;\x1b\x1b]777;notify;Claude Code;waiting\x07\x1b\\",
			[]Notification{{Title: "Claude Code", Body: "waiting"}}},
		{"bell", "done\x07", []Notification{{Bell: true}}},
		{"bell in passthrough", "\x1bPtmux;\x07\x1b\\", []Notification{{Bell: true}}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := scan(tc.out); !reflect.DeepEqual(got, tc.want) {
				t.Errorf("got %+v, want %+v", got, tc.want)
			}
		})
	}
}

func TestOthersArentNotifications(t *testing.T) {
	for name, out := range map[string]string{
		"title":          "\x1b]0;~/dev\x07",
		"hyperlink":      "\x1b]8;;https://example.com\x1b\\link\x1b]8;;\x1b\\",
		"progress":       "\x1b]9;4;1;50\x07",
		"directory":      "\x1b]9;9;C:\\Users\x07",
		"other 777":      "\x1b]777;preexec\x07",
		"other DCS":      "\x1bPq#0;2;0;0;0#0~~@@vv@@~~$\x1b\\",
		"sgr":            "\x1b[1;31mred\x1b[0m",
		"empty":          "\x1b]9;\x07",
		"cut by another": "\x1b]9;half\x1b[0m",
		"apc":            "\x1b_Gf=100;\x07\x1b\\",
	} {
		if got := scan(out); len(got) != 0 {
			t.Errorf("%s: got %+v", name, got)
		}
	}
}

func TestSplitAcrossReads(t *testing.T) {
	out := "ls\r\n\x1bPtmux;\x1b\x1b]9;tests pass\x07\x1b\\$ "
	for i := 1; i < len(out); i++ {
		got := scan(out[:i], out[i:])
		if want := []Notification{{Body: "tests pass"}}; !reflect.DeepEqual(got, want) {
			t.Fatalf("split at %d: got %+v", i, got)
		}
	}
}

func TestShownAsText(t *testing.T) {
	got := scan("\x1b]777;notify;Title;line one\nline\ttwo \xff\x07")
	want := []Notification{{Title: "Title", Body: "line one\nline two"}}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got %+v, want %+v", got, want)
	}
}

func TestLongOnesAreDropped(t *testing.T) {
	long := make([]byte, maxOSC+10)
	for i := range long {
		long[i] = 'a'
	}
	if got := scan("\x1b]9;" + string(long) + "\x07"); len(got) != 0 {
		t.Errorf("got %d notifications", len(got))
	}
	if got := scan("\x1b]9;" + string(long[:300]) + "\x07"); len([]rune(got[0].Body)) != 300 {
		t.Errorf("a 300-character body came out as %d", len([]rune(got[0].Body)))
	}
}

func TestABellWithANotificationIsPartOfIt(t *testing.T) {
	notified := "\x1bPtmux;\x1b\x1b]9;Claude needs your permission\x07\x1b\\"
	want := []Notification{{Body: "Claude needs your permission"}}
	if got := scan(notified + "\x1b[?25l\x1b(B\x07"); !reflect.DeepEqual(got, want) {
		t.Errorf("rung with it: got %+v", got)
	}
	if got := scan(notified, "\x07\x07"); !reflect.DeepEqual(got, want) {
		t.Errorf("rung in the next read: got %+v", got)
	}
	want = append(want, Notification{Bell: true})
	if got := scan(notified + "\r\nnext\x07"); !reflect.DeepEqual(got, want) {
		t.Errorf("rung after printing: got %+v", got)
	}
	if got := scan("\x07\x1b]9;tests pass\x07"); !reflect.DeepEqual(got, []Notification{{Bell: true}, {Body: "tests pass"}}) {
		t.Errorf("rung before: got %+v", got)
	}
}
