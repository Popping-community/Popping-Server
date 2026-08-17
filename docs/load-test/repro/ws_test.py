"""Does a like broadcast on one instance reach a subscriber on the other?

The broker is registered with enableSimpleBroker, which keeps subscriptions in
process memory, so the expectation is no. Asserting that from config alone is
weak - a subscriber that silently failed to connect would look identical to one
that connected and received nothing. Hence the control: the same subscriber is
sent a like on its own instance afterwards, and must receive that one.
"""
import json
import time
import uuid

import websocket

TOPIC = "/topic/like-updates"
POST_ID = 121


def frame(command, headers, body=""):
    lines = [command] + [f"{k}:{v}" for k, v in headers.items()]
    return "\n".join(lines) + "\n\n" + body + "\0"


def connect(host):
    ws = websocket.create_connection(f"ws://{host}:9091/ws/websocket", timeout=10)
    ws.send(frame("CONNECT", {"accept-version": "1.2", "host": "/"}))
    reply = ws.recv()
    assert reply.startswith("CONNECTED"), f"{host}: {reply[:60]}"
    return ws


def send_like(ws, like_type):
    payload = json.dumps({
        "targetId": POST_ID,
        "targetType": "POST",
        "type": like_type,
        "guestIdentifier": str(uuid.uuid4()),
    })
    ws.send(frame("SEND", {
        "destination": "/app/like/add",
        "content-type": "application/json",
        "content-length": str(len(payload.encode())),
    }, payload))


def drain(ws, seconds):
    """Collect MESSAGE frames arriving within the window."""
    got = []
    ws.settimeout(seconds)
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            got.append(ws.recv())
        except Exception:
            break
    return [m for m in got if m.startswith("MESSAGE")]


subscriber = connect("popping-app-2")
subscriber.send(frame("SUBSCRIBE", {"id": "sub-0", "destination": TOPIC}))
time.sleep(1)
print("subscriber connected to app-2 and subscribed to", TOPIC)

# 1. like sent to the OTHER instance
publisher_1 = connect("popping-app-1")
send_like(publisher_1, "LIKE")
print("sent a like on app-1")
cross = drain(subscriber, 5)
print(f"  -> app-2 subscriber received {len(cross)} message(s)")

# 2. control: like sent to the subscriber's OWN instance
publisher_2 = connect("popping-app-2")
send_like(publisher_2, "DISLIKE")
print("sent a like on app-2 (control)")
same = drain(subscriber, 5)
print(f"  -> app-2 subscriber received {len(same)} message(s)")

print()
print("cross-instance :", "REACHED" if cross else "NOT REACHED")
print("same-instance  :", "REACHED" if same else "NOT REACHED")
if same and not cross:
    print("=> broadcast does not cross instances (subscriber verified working)")
elif not same:
    print("=> INCONCLUSIVE: the control failed, so the client itself is suspect")
else:
    print("=> broadcast crossed instances - contradicts enableSimpleBroker")
