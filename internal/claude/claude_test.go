package claude

import (
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"testing"

	"github.com/ahmd-sh/wallflower/internal/protocol"
	"github.com/ahmd-sh/wallflower/internal/transcript"
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
	`{"type":"user","uuid":"u8","promptSource":"system","message":{"role":"user","content":"<task-notification>\n<task-id>b1</task-id>\n<status>completed</status>\n<summary>Background command \"Sleep\" completed (exit code 0)</summary>\n</task-notification>"}}`,
	`{"type":"user","uuid":"u9","promptSource":"system","message":{"role":"user","content":"<something-else>no summary</something-else>"}}`,
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
		{ID: "u8.0", Role: "user", Kind: "note", Text: `Background command "Sleep" completed (exit code 0)`},
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

func TestContext(t *testing.T) {
	p := New("").Parser().(transcript.Measure)
	line := func(s string) { p.(transcript.Parser).Line([]byte(s)) }
	if p.Context() != 0 {
		t.Errorf("before any reply: %d", p.Context())
	}
	line(`{"type":"assistant","uuid":"a","message":{"content":[],"usage":{"input_tokens":2,"cache_creation_input_tokens":736,"cache_read_input_tokens":543056,"output_tokens":319}}}`)
	if p.Context() != 544113 {
		t.Errorf("after a reply: %d", p.Context())
	}
	line(`{"type":"assistant","uuid":"s","isSidechain":true,"message":{"content":[],"usage":{"input_tokens":9000}}}`)
	line(`{"type":"assistant","uuid":"e","message":{"content":[],"usage":{"input_tokens":0,"output_tokens":0}}}`)
	if p.Context() != 544113 {
		t.Errorf("a subagent's reply, or one without usage, changed it: %d", p.Context())
	}
	line(`{"type":"system","subtype":"compact_boundary","uuid":"c","content":"Conversation compacted","compactMetadata":{"trigger":"manual","preTokens":544113,"postTokens":17967}}`)
	if p.Context() != 0 {
		t.Errorf("after compacting, before the next reply: %d", p.Context())
	}
}

// A conversation rewound from three to edited, as claude 2.1.302 wrote it, with
// parallel tool calls on the way: each result goes on from its own call.
var rewound = []string{
	`{"type":"user","uuid":"u1","parentUuid":null,"message":{"content":"say just: one"}}`,
	`{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"content":[{"type":"text","text":"one"}],"usage":{"input_tokens":100}}}`,
	`{"type":"system","subtype":"turn_duration","uuid":"s1","parentUuid":"a1"}`,
	`{"type":"user","uuid":"u2","parentUuid":"s1","message":{"content":"two, with two tools"}}`,
	`{"type":"assistant","uuid":"a2","parentUuid":"u2","message":{"content":[{"type":"tool_use","id":"A","name":"Read","input":{"file_path":"/a"}}]}}`,
	`{"type":"assistant","uuid":"a3","parentUuid":"a2","message":{"content":[{"type":"tool_use","id":"B","name":"Read","input":{"file_path":"/b"}}]}}`,
	`{"type":"user","uuid":"r1","parentUuid":"a2","message":{"content":[{"type":"tool_result","tool_use_id":"A","content":"a"}]}}`,
	`{"type":"user","uuid":"r2","parentUuid":"a3","message":{"content":[{"type":"tool_result","tool_use_id":"B","content":"b"}]}}`,
	`{"type":"assistant","uuid":"a4","parentUuid":"r2","message":{"content":[{"type":"text","text":"two"}],"usage":{"input_tokens":200}}}`,
	`{"type":"system","subtype":"turn_duration","uuid":"s2","parentUuid":"a4"}`,
	`{"type":"user","uuid":"u3","parentUuid":"s2","message":{"content":"say just: three"}}`,
	`{"type":"assistant","uuid":"a5","parentUuid":"u3","message":{"content":[{"type":"text","text":"three"}],"usage":{"input_tokens":300}}}`,
	`{"type":"system","subtype":"turn_duration","uuid":"s3","parentUuid":"a5"}`,
	`{"type":"user","uuid":"u4","parentUuid":"s2","message":{"content":"say just: edited"}}`,
	`{"type":"assistant","uuid":"a6","parentUuid":"s3","message":{"content":[{"type":"text","text":"late, on the branch left behind"}]}}`,
	`{"type":"assistant","uuid":"a7","parentUuid":"u4","message":{"content":[{"type":"text","text":"edited"}],"usage":{"input_tokens":250}}}`,
}

func TestRewound(t *testing.T) {
	p := New("").Parser()
	var got []protocol.Entry
	at := map[string]int{}
	for _, l := range rewound {
		for _, e := range p.Line([]byte(l)) {
			if i, ok := at[e.ID]; ok {
				got[i] = e // a tool's result
			} else {
				at[e.ID] = len(got)
				got = append(got, e)
			}
		}
	}
	got = p.(transcript.Brancher).Kept(got)
	var ids []string
	for _, e := range got {
		ids = append(ids, e.ID+"="+e.Text)
	}
	if want := "u1.0=say just: one a1.0=one u2.0=two, with two tools a2.0=/a a3.0=/b a4.0=two u4.0=say just: edited a7.0=edited"; strings.Join(ids, " ") != want {
		t.Errorf("got  %s\nwant %s", strings.Join(ids, " "), want)
	}
	if !got[3].Done || !got[4].Done {
		t.Errorf("parallel results: %+v %+v", got[3], got[4])
	}
	if b := p.(transcript.Brancher).Branches(); b != 1 {
		t.Errorf("branches: %d", b)
	}
	if c := p.(transcript.Measure).Context(); c != 250 {
		t.Errorf("context: %d", c)
	}

	// Until the reply on the new branch, how full the context is isn't known.
	p = New("").Parser()
	for _, l := range rewound[:14] {
		p.Line([]byte(l))
	}
	if c := p.(transcript.Measure).Context(); c != 0 {
		t.Errorf("context after going back: %d", c)
	}
}

func TestPlans(t *testing.T) {
	p := New("").Parser()
	got := p.Line([]byte(`{"type":"assistant","uuid":"a","message":{"content":[{"type":"tool_use","id":"t","name":"Write","input":{"file_path":"/Users/me/.claude/plans/fix-it.md","content":"# Plan\n\n1. fix it\n"}}]}}`))
	if len(got) != 2 || got[0].Kind != "tool" || got[1].Kind != "text" || got[1].Text != "# Plan\n\n1. fix it" || got[1].ID != "a.0.plan" {
		t.Errorf("plan: %+v", got)
	}
	got = p.Line([]byte(`{"type":"assistant","uuid":"b","message":{"content":[{"type":"tool_use","id":"u","name":"Write","input":{"file_path":"/Users/me/dev/plans/notes.md","content":"not a plan"}}]}}`))
	if len(got) != 1 {
		t.Errorf("any other write: %+v", got)
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
	write("sessions/"+strconv.Itoa(me)+".json", `{"pid":`+strconv.Itoa(me)+`,"sessionId":"aaaa-1","cwd":"/Users/me/dev/x","status":"waiting","statusUpdatedAt":1792209270006}`)
	write("sessions/"+strconv.Itoa(parent)+".json", `{"pid":`+strconv.Itoa(parent)+`,"sessionId":"bbbb-2","cwd":"/Users/me/my.dir","status":"shell","statusUpdatedAt":1792209271000}`)
	write("sessions/1.key", `{}`)
	write("sessions/999999.json", `{"pid":999999,"sessionId":"cccc-3","cwd":"/"}`) // no such process
	write("sessions/bad.json", `{"pid":`+strconv.Itoa(me)+`,"sessionId":"../../etc"}`)
	write("projects/-Users-me-dev-x-moved/aaaa-1.jsonl", "")

	got := New(dir).Running()
	want := map[int]transcript.Session{
		me:     {Path: filepath.Join(dir, "projects/-Users-me-dev-x-moved/aaaa-1.jsonl"), State: protocol.StateWaiting, Since: 1792209270006}, // where it is
		parent: {Path: filepath.Join(dir, "projects/-Users-me-my-dir/bbbb-2.jsonl"), State: protocol.StateIdle, Since: 1792209271000},         // where it will be; done, with a background task left running
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got %v\nwant %v", got, want)
	}
}
