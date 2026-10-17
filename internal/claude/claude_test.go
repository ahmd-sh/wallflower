package claude

import (
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"testing"

	"github.com/ahmd-sh/wallflower/internal/protocol"
)

// Lines shaped like Claude Code's transcripts, trimmed to the fields read.
var conversation = []string{
	`{"type":"permission-mode","permissionMode":"default","sessionId":"s"}`,
	`{"type":"user","uuid":"u1","message":{"role":"user","content":"\n\n<pasted_content id=\"7\">\nfix the tests\n</pasted_content id=\"7\">"}}`,
	`{"type":"user","uuid":"m1","isMeta":true,"message":{"role":"user","content":"[Image: original 1280x2856]"}}`,
	`{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":[{"type":"thinking","thinking":"","signature":"x"}]}}`,
	`{"type":"assistant","uuid":"a2","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"go test ./...","description":"Run tests"}}]}}`,
	`{"type":"assistant","uuid":"a3","isSidechain":true,"message":{"role":"assistant","content":[{"type":"text","text":"a subagent's"}]}}`,
	`{"type":"user","uuid":"u2","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","is_error":true,"content":"\u001b[31mFAIL\u001b[0m x_test.go"}]}}`,
	`{"type":"assistant","uuid":"a4","message":{"role":"assistant","content":[{"type":"text","text":"**Fixed.** All pass."}]}}`,
	`{"type":"user","uuid":"u3","message":{"role":"user","content":"<command-name>/compact</command-name>"}}`,
	`{"type":"user","uuid":"u4","message":{"role":"user","content":"<local-command-stdout>Compacted</local-command-stdout>"}}`,
	`{"type":"user","uuid":"u5","isCompactSummary":true,"message":{"role":"user","content":"This session is being continued..."}}`,
	`{"type":"user","uuid":"u6","message":{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBORw0KGgo="}},{"type":"text","text":"look"}]}}`,
	`{"type":"user","uuid":"u7","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user for tool use]"}]}}`,
	`not json`,
}

func TestParse(t *testing.T) {
	p := New(t.TempDir()).Parser()
	var got []protocol.Entry
	for _, l := range conversation {
		got = append(got, p.Line([]byte(l))...)
	}
	want := []protocol.Entry{
		{ID: "u1.0", Role: "user", Kind: "text", Text: "fix the tests"},
		{ID: "a2.0", Role: "assistant", Kind: "tool", Name: "Bash", Text: "go test ./..."},
		{ID: "a2.0", Role: "assistant", Kind: "tool", Name: "Bash", Text: "go test ./...", Output: "FAIL x_test.go", Done: true, Error: true},
		{ID: "a4.0", Role: "assistant", Kind: "text", Text: "**Fixed.** All pass."},
		{ID: "u5.0", Role: "user", Kind: "note", Text: "This session is being continued..."},
		{ID: "u6.0", Role: "user", Kind: "image", Mime: "image/png", Data: "iVBORw0KGgo="},
		{ID: "u6.1", Role: "user", Kind: "text", Text: "look"},
		{ID: "u7.0", Role: "user", Kind: "note", Text: "[Request interrupted by user for tool use]"},
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got\n%+v\nwant\n%+v", got, want)
	}
}

func TestBigImagesAndOutputs(t *testing.T) {
	p := New(t.TempDir()).Parser()
	big := strings.Repeat("A", maxImage+1)
	got := p.Line([]byte(`{"type":"user","uuid":"u","message":{"content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"` + big + `"}}]}}`))
	if len(got) != 1 || got[0].Kind != "note" || got[0].Data != "" {
		t.Errorf("big image: %+v", got)
	}
	p.Line([]byte(`{"type":"assistant","uuid":"a","message":{"content":[{"type":"tool_use","id":"t","name":"Read","input":{"file_path":"/x"}}]}}`))
	long := strings.Repeat("é", maxOutput)
	got = p.Line([]byte(`{"type":"user","uuid":"r","message":{"content":[{"type":"tool_result","tool_use_id":"t","content":[{"type":"text","text":"` + long + `"},{"type":"image"}]}]}}`))
	if len(got) != 1 || len(got[0].Output) > maxOutput+len("…") || !strings.HasSuffix(got[0].Output, "…") || !strings.HasPrefix(got[0].Output, "é") {
		t.Errorf("long output: %d bytes", len(got[0].Output))
	}
}

func TestRunning(t *testing.T) {
	dir := t.TempDir()
	write := func(name, body string) {
		t.Helper()
		if err := os.MkdirAll(filepath.Dir(filepath.Join(dir, name)), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, name), []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	me := os.Getpid()
	parent := os.Getppid()
	write("sessions/"+strconv.Itoa(me)+".json", `{"pid":`+strconv.Itoa(me)+`,"sessionId":"aaaa-1","cwd":"/Users/me/dev/x"}`)
	write("sessions/"+strconv.Itoa(parent)+".json", `{"pid":`+strconv.Itoa(parent)+`,"sessionId":"bbbb-2","cwd":"/Users/me/my.dir"}`)
	write("sessions/1.key", `{}`)
	write("sessions/999999.json", `{"pid":999999,"sessionId":"cccc-3","cwd":"/"}`) // no such process
	write("sessions/bad.json", `{"pid":`+strconv.Itoa(me)+`,"sessionId":"../../etc"}`)
	write("projects/-Users-me-dev-x-moved/aaaa-1.jsonl", "")

	got := New(dir).Running()
	want := map[int]string{
		me:     filepath.Join(dir, "projects/-Users-me-dev-x-moved/aaaa-1.jsonl"), // where it is
		parent: filepath.Join(dir, "projects/-Users-me-my-dir/bbbb-2.jsonl"),      // where it will be
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got %v\nwant %v", got, want)
	}
}
