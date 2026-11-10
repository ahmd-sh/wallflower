package rex

import (
	"errors"
	"os"

	"github.com/ahmd-sh/wallflower/internal/mux"
)

// The server's sessions, each call on a control connection of its own.

// with runs f on a control connection to the server, attached to session
// if it's set.
func (s Server) with(session string, f func(c *control) error) error {
	endpoint := s.Endpoint
	if endpoint == "" {
		endpoint = defaultEndpoint()
	}
	c, err := dial(endpoint)
	if err != nil {
		return err
	}
	defer c.close()
	go func() {
		for range c.events {
		}
	}()
	if session != "" {
		if err := c.call("session.attach", map[string]string{"session_id": session}, nil); err != nil {
			return err
		}
	}
	return f(c)
}

// Sessions lists the server's sessions. With no server there are none,
// and none can be started: the server is Rex's to start.
func (s Server) Sessions() (mux.Listing, error) {
	var list []mux.Listed
	err := s.with("", func(c *control) error {
		var ls struct {
			Sessions []struct {
				ID    string `json:"session_id"`
				Label string `json:"label"`
			} `json:"sessions"`
		}
		if err := c.call("session.list", nil, &ls); err != nil {
			return err
		}
		for _, l := range ls.Sessions {
			var att struct {
				View struct {
					Windows []struct{} `json:"windows"`
				} `json:"view"`
			}
			if err := c.call("session.attach", map[string]string{"session_id": l.ID}, &att); err != nil {
				return err
			}
			c.call("session.detach", map[string]string{"session_id": l.ID}, nil)
			list = append(list, mux.Listed{ID: l.ID, Name: l.Label, Windows: len(att.View.Windows)})
		}
		return nil
	})
	if isNoServer(err) {
		return mux.Listing{}, nil
	}
	return mux.Listing{Running: true, Sessions: list, Can: []string{mux.CanNew, mux.CanRename, mux.CanKill}}, err
}

func isNoServer(err error) bool {
	var no *noServer
	return errors.As(err, &no)
}

func (s Server) NewSession(name string) (string, error) {
	options := map[string]any{}
	if home, err := os.UserHomeDir(); err == nil {
		options["cwd"] = home
	}
	args := map[string]any{"initial_windows": []any{map[string]any{
		"layout": map[string]any{"block": map[string]any{"flavor": shell, "options": options}},
	}}}
	if name != "" {
		args["label"] = name
	}
	var created struct {
		ID string `json:"session_id"`
	}
	err := s.with("", func(c *control) error { return c.call("session.create", args, &created) })
	return created.ID, err
}

func (s Server) RenameSession(session, name string) error {
	return s.with(session, func(c *control) error {
		return c.call("session.set_label", map[string]string{"session_id": session, "label": name}, nil)
	})
}

func (s Server) KillSession(session string) error {
	return s.with(session, func(c *control) error {
		return c.call("session.destroy", map[string]string{"session_id": session}, nil)
	})
}
