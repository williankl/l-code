package dev.lemonadecode.imports.impl

/**
 * Made-up herdr output in the exact shape herdr 0.9.5 writes it: `herdr session list --json` and a
 * session's `session.json`, format version 3. Every name, folder and id here is invented.
 */
internal object HerdrFixtures {
  const val Home = "/home/me"
  const val SessionDir = "/home/me/.config/herdr"
  const val NamedSessionDir = "/home/me/.config/herdr/sessions/work"

  const val StorefrontChat = "0b6f3c2e-4a51-4f7d-9d8e-1c2b3a4d5e6f"
  const val RateLimitChat = "7e9d8c7b-6a5f-4e3d-8c2b-1a0f9e8d7c6b"
  const val RateLimitOtherTabChat = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

  /** What `herdr session list --json` prints with only the default session, stopped. */
  val sessionList: String = """
    {
      "sessions": [
        {
          "default": true,
          "name": "default",
          "running": false,
          "session_dir": "$SessionDir",
          "socket_path": "$SessionDir/herdr.sock"
        }
      ]
    }
  """.trimIndent()

  /** The default session running, and a named one, stopped. */
  val twoSessions: String = """
    {
      "sessions": [
        {"default": true, "name": "default", "running": true, "session_dir": "$SessionDir", "socket_path": "$SessionDir/herdr.sock"},
        {"default": false, "name": "work", "running": false, "session_dir": "$NamedSessionDir", "socket_path": "$NamedSessionDir/herdr.sock"}
      ]
    }
  """.trimIndent()

  /**
   * Four workspaces: storefront, with Claude Code in its one pane; a linked worktree of it, named by
   * hand, with Claude in the focused pane of a split and in another tab; the home folder, running
   * Codex; and a worktree of a repository with no workspace open, listed before it would be.
   */
  val layout: String = """
    {
      "version": 3,
      "workspaces": [
        {
          "id": "w9",
          "custom_name": null,
          "identity_cwd": "/home/me/code/billing.worktrees/fix-tax",
          "worktree_space": {
            "key": "/home/me/code/billing/.git",
            "label": "billing",
            "repo_root": "/home/me/code/billing",
            "checkout_path": "/home/me/code/billing.worktrees/fix-tax",
            "is_linked_worktree": true
          },
          "public_pane_numbers": {"9": 1},
          "next_public_pane_number": 2,
          "public_tab_numbers": [1],
          "next_public_tab_number": 2,
          "tabs": [
            {"custom_name": null, "layout": {"Pane": 9}, "panes": {"9": {"cwd": "/home/me/code/billing.worktrees/fix-tax"}}, "zoomed": false, "focused": 9, "root_pane": 9}
          ],
          "active_tab": 0
        },
        {
          "id": "w1",
          "custom_name": null,
          "identity_cwd": "/home/me/code/storefront",
          "worktree_space": {
            "key": "/home/me/code/storefront/.git",
            "label": "storefront",
            "repo_root": "/home/me/code/storefront",
            "checkout_path": "/home/me/code/storefront",
            "is_linked_worktree": false
          },
          "public_pane_numbers": {"1": 1},
          "next_public_pane_number": 2,
          "public_tab_numbers": [1],
          "next_public_tab_number": 2,
          "tabs": [
            {
              "custom_name": null,
              "layout": {"Pane": 1},
              "panes": {
                "1": {
                  "cwd": "/home/me/code/storefront",
                  "agent_session": {"source": "herdr:claude", "agent": "claude", "kind": "id", "value": "$StorefrontChat"}
                }
              },
              "zoomed": false,
              "focused": 1,
              "root_pane": 1
            }
          ],
          "active_tab": 0
        },
        {
          "id": "w2",
          "custom_name": "rate limit",
          "identity_cwd": "/home/me/code/storefront.worktrees/rate-limit",
          "worktree_space": {
            "key": "/home/me/code/storefront/.git",
            "label": "storefront",
            "repo_root": "/home/me/code/storefront",
            "checkout_path": "/home/me/code/storefront.worktrees/rate-limit",
            "is_linked_worktree": true
          },
          "public_pane_numbers": {"2": 1, "3": 2, "4": 3},
          "next_public_pane_number": 4,
          "public_tab_numbers": [1, 2],
          "next_public_tab_number": 3,
          "tabs": [
            {
              "custom_name": null,
              "layout": {"Split": {"direction": "Horizontal", "ratio": 0.5, "first": {"Pane": 2}, "second": {"Pane": 3}}},
              "panes": {
                "2": {"cwd": "/home/me/code/storefront.worktrees/rate-limit"},
                "3": {
                  "cwd": "/home/me/code/storefront.worktrees/rate-limit",
                  "agent_session": {"source": "herdr:claude", "agent": "claude", "kind": "id", "value": "$RateLimitChat"}
                }
              },
              "zoomed": false,
              "focused": 3,
              "root_pane": 2
            },
            {
              "custom_name": "tests",
              "layout": {"Pane": 4},
              "panes": {
                "4": {
                  "cwd": "/home/me/code/storefront.worktrees/rate-limit",
                  "agent_session": {"source": "herdr:claude", "agent": "claude", "kind": "id", "value": "$RateLimitOtherTabChat"}
                }
              },
              "zoomed": false,
              "focused": 4,
              "root_pane": 4
            }
          ],
          "active_tab": 0
        },
        {
          "id": "w3",
          "custom_name": null,
          "identity_cwd": "/home/me",
          "public_pane_numbers": {"5": 1},
          "next_public_pane_number": 2,
          "public_tab_numbers": [1],
          "next_public_tab_number": 2,
          "tabs": [
            {
              "custom_name": null,
              "layout": {"Pane": 5},
              "panes": {
                "5": {
                  "cwd": "/home/me",
                  "agent_session": {"source": "herdr:codex", "agent": "codex", "kind": "id", "value": "019a0000-0000-7000-8000-000000000000"}
                }
              },
              "zoomed": false,
              "focused": 5,
              "root_pane": 5
            }
          ],
          "active_tab": 0
        }
      ],
      "active": 1,
      "selected": 1,
      "sidebar_width": null,
      "sidebar_section_split": null,
      "collapsed_space_keys": []
    }
  """.trimIndent()

  /** One workspace for the named session `work`. */
  val workLayout: String = """
    {
      "version": 3,
      "workspaces": [
        {
          "id": "w1",
          "custom_name": "infra",
          "identity_cwd": "/home/me/code/infra",
          "tabs": [{"custom_name": null, "layout": {"Pane": 1}, "panes": {"1": {"cwd": "/home/me/code/infra"}}, "zoomed": false, "focused": 1, "root_pane": 1}],
          "active_tab": 0
        }
      ],
      "active": 0,
      "selected": 0,
      "collapsed_space_keys": []
    }
  """.trimIndent()

  /** A workspace from before herdr had tabs: its one layout and panes kept in the workspace itself. */
  val legacyLayout: String = """
    {
      "workspaces": [
        {
          "custom_name": null,
          "layout": {"Split": {"direction": "Vertical", "ratio": 0.5, "first": {"Pane": 7}, "second": {"Pane": 8}}},
          "panes": {
            "7": {"cwd": "/home/me/code/old"},
            "8": {"cwd": "/home/me/code/old/docs"}
          },
          "zoomed": false,
          "focused": 8,
          "root_pane": null
        }
      ],
      "active": 0,
      "selected": 0
    }
  """.trimIndent()

  /** What a newer herdr might write. */
  val newerLayout: String = """{"version": 4, "workspaces": [], "selected": 0}"""

  /** Agent references Lemonade Code must not run: a value the shell would read as code, a path, another source. */
  val untrustedAgents: String = """
    {
      "version": 3,
      "workspaces": [
        {
          "id": "w1",
          "identity_cwd": "/home/me/code/a",
          "tabs": [{"layout": {"Pane": 1}, "panes": {"1": {"cwd": "/home/me/code/a", "agent_session": {"source": "herdr:claude", "agent": "claude", "kind": "id", "value": "x; rm -rf ~"}}}, "zoomed": false}],
          "active_tab": 0
        },
        {
          "id": "w2",
          "identity_cwd": "/home/me/code/b",
          "tabs": [{"layout": {"Pane": 2}, "panes": {"2": {"cwd": "/home/me/code/b", "agent_session": {"source": "custom:claude", "agent": "claude", "kind": "id", "value": "abc"}}}, "zoomed": false}],
          "active_tab": 0
        },
        {
          "id": "w3",
          "identity_cwd": "/home/me/code/c",
          "tabs": [{"layout": {"Pane": 3}, "panes": {"3": {"cwd": "/home/me/code/c", "agent_session": {"source": "herdr:claude", "agent": "claude", "kind": "path", "value": "/tmp/session"}}}, "zoomed": false}],
          "active_tab": 0
        }
      ],
      "selected": 0
    }
  """.trimIndent()
}
