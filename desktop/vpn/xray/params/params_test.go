package params

import (
	"encoding/json"
	"strings"
	"testing"
)

const goodKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

func raw(fp string) json.RawMessage {
	return json.RawMessage(`{"uuid":"123e4567-e89b-12d3-a456-426614174000","variants":[{"transport":"tcp","host":"n.example","port":443,"flow":"xtls-rprx-vision","security":"reality","sni":"s.example","pbk":"` + goodKey + `","sid":"ab","fp":"` + fp + `"}]}`)
}

func TestParse(t *testing.T) {
	p, err := ParseParams(raw("chrome"))
	if err != nil || len(p.Variants) != 1 || p.Variants[0].FP != "chrome" {
		t.Fatalf("%+v %v", p, err)
	}
	if p, err = ParseParams(raw("")); err != nil || p.Variants[0].FP != "firefox" {
		t.Fatalf("default fingerprint: %+v %v", p, err)
	}
	for _, fp := range []string{"unsafe", "hellogolang", "random", "nonsense"} {
		if _, err := ParseParams(raw(fp)); err == nil || !strings.Contains(err.Error(), "fingerprint") {
			t.Errorf("%s accepted: %v", fp, err)
		}
	}
	if _, err := ParseParams(json.RawMessage(`{"uuid":"x","variants":[]}`)); err == nil {
		t.Error("bad uuid accepted")
	}
}
