#!/usr/bin/env python3
"""Reads a window the way Accerciser and Orca do: through the AT-SPI registry.

Usage: atspi-walk.py <application name>

Finds the application on the accessibility bus, prints the tree it exposes, and checks what a
screen reader needs from it: the application, its window, a text field and a button named
"Save", with roles, states, extents and an action. Then it presses the button through the
Action interface, which is the call Orca makes. Exit 0 only if every check holds.
"""
import sys
import time

import gi

gi.require_version("Atspi", "2.0")
from gi.repository import Atspi  # noqa: E402


def children(node):
    return [node.get_child_at_index(i) for i in range(node.get_child_count())]


def dump(node, depth=0, out=None):
    out = out if out is not None else []
    out.append((depth, node))
    for child in children(node):
        dump(child, depth + 1, out)
    return out


def find_application(name, seconds):
    deadline = time.time() + seconds
    while time.time() < deadline:
        desktop = Atspi.get_desktop(0)
        for app in children(desktop):
            if app is not None and app.get_name() == name:
                return app
        time.sleep(0.5)
    return None


def states_of(node):
    return {s.value_nick for s in node.get_state_set().get_states()}


def main():
    name = sys.argv[1]
    Atspi.init()
    app = find_application(name, 60)
    if app is None:
        seen = [a.get_name() for a in children(Atspi.get_desktop(0)) if a is not None]
        print(f"fail: no application named {name!r} on the accessibility bus; saw {seen}")
        return 1
    # The tree is published after the first frame, so give it a moment to be there.
    deadline = time.time() + 30
    nodes = []
    while time.time() < deadline:
        nodes = dump(app)
        if any(n.get_name() == "Save" for _, n in nodes):
            break
        time.sleep(0.5)
    for depth, node in nodes:
        extents = Atspi.Component.get_extents(node, Atspi.CoordType.WINDOW) if node.get_component_iface() else None
        shape = f" {extents.x},{extents.y} {extents.width}x{extents.height}" if extents else ""
        print(f"{'  ' * depth}{node.get_role_name()} {node.get_name()!r}{shape} {sorted(states_of(node))}")

    failures = []
    if app.get_role_name() != "application":
        failures.append(f"the application's role is {app.get_role_name()!r}")
    windows = [n for d, n in nodes if d == 1]
    if not windows or windows[0].get_role_name() != "frame":
        failures.append("the application has no window with the role frame")
    buttons = [n for _, n in nodes if n.get_role_name() == "push button" and n.get_name() == "Save"]
    fields = [n for _, n in nodes if n.get_role_name() == "text"]
    if not buttons:
        failures.append("no push button named Save")
    if not fields:
        failures.append("no text field")
    if buttons:
        button = buttons[0]
        states = states_of(button)
        for wanted in ("enabled", "focusable", "showing"):
            if wanted not in states:
                failures.append(f"the button is not {wanted}: {sorted(states)}")
        extents = Atspi.Component.get_extents(button, Atspi.CoordType.WINDOW)
        if extents.width <= 0 or extents.height <= 0:
            failures.append(f"the button has no size: {extents.width}x{extents.height}")
        if Atspi.Action.get_n_actions(button) < 1:
            failures.append("the button offers no action")
        elif not Atspi.Action.do_action(button, 0):
            failures.append("pressing the button through the Action interface was refused")
    if fields:
        states = states_of(fields[0])
        for wanted in ("editable", "focusable", "enabled"):
            if wanted not in states:
                failures.append(f"the text field is not {wanted}: {sorted(states)}")
    if failures:
        for failure in failures:
            print("fail:", failure)
        return 1
    print("ok: the window is readable through AT-SPI and its button can be pressed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
